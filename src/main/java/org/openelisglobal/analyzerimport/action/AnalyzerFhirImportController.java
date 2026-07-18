package org.openelisglobal.analyzerimport.action;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.hibernate.ObjectNotFoundException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Specimen;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.service.QCResultProcessingService;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.Analyzer.AnalyzerStatus;
import org.openelisglobal.analyzerresults.service.AnalyzerResultsService;
import org.openelisglobal.analyzerresults.valueholder.AnalyzerResults;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.util.StringUtil;
import org.openelisglobal.test.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 Bundle import endpoint for analyzer results.
 *
 * <p>
 * Receives a FHIR R4 transaction Bundle containing DiagnosticReport,
 * Observation, and Specimen resources from the analyzer bridge. Maps
 * Observations to {@link AnalyzerResults} staging rows — the same table used by
 * HL7, ASTM, and FILE import paths.
 *
 * <p>
 * This is the unified entry point for all analyzer results regardless of source
 * protocol. The bridge handles all format-specific parsing (HL7 v2, ASTM
 * LIS2-A2, Excel, CSV) and normalizes to FHIR R4.
 *
 * <p>
 * FHIR → AnalyzerResults mapping:
 * <ul>
 * <li>Specimen.identifier → accessionNumber</li>
 * <li>Observation.code.coding[0].code → testName</li>
 * <li>Observation.value[x] → result</li>
 * <li>Observation.valueQuantity.unit → units</li>
 * <li>Observation.subject → in-bundle Patient.identifier → patientHint (wire
 * patient identity, LIS-239; absent Patient/subject → null)</li>
 * <li>X-Analyzer-Id header → analyzerId</li>
 * </ul>
 */
@RestController
public class AnalyzerFhirImportController extends org.openelisglobal.common.rest.BaseRestController {

    private static final String CLASS_NAME = "AnalyzerFhirImportController";
    private static final String OPENELIS_FHIR_TAG_SYSTEM = "http://openelis-global.org/fhir/tags";
    private static final String CALIBRATION_TAG = "CALIBRATION";
    // Identifier system the bridge stamps on the Patient resource it builds from
    // the wire patient identity (FhirBundleBuilder.ANALYZER_PATIENT_ID_SYSTEM).
    private static final String ANALYZER_PATIENT_ID_SYSTEM = "http://openelis-global.org/fhir/analyzer-patient-id";
    private static final String LOINC_SYSTEM = "http://loinc.org";
    private static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
    // LIS-271: an analyzer's onboard clock can be badly wrong (the real MAGLUMI
    // X3 bench clock was ~16 months off) yet still be a syntactically valid
    // timestamp, so it never trips the existing parse-exception fallback. A gap
    // this large between the analyzer-reported time and OE's own receive time is
    // flagged for review — QA-approved policy (Pinote) is non-blocking: flag +
    // record both timestamps, never hold or reject on skew alone.
    private static final long CLOCK_SKEW_THRESHOLD_MILLIS = 24L * 60 * 60 * 1000;
    private static final String CLOCK_SKEW_ISSUE_REASON = "clock-skew";

    @Autowired
    private AnalyzerResultsService analyzerResultsService;

    @Autowired
    private AnalyzerService analyzerService;

    @Autowired
    private FhirContext fhirContext;

    // Not @Autowired — Spring doesn't expose an ObjectMapper bean in this
    // webapp's context (see AnalyzerRestController for the same pattern).
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String[] PROFILE_PROTOCOL_DIRS = { "astm", "hl7", "file" };
    private static final int MIN_PROFILE_MATCH_SCORE = 2;

    @Autowired
    private QCResultProcessingService qcResultProcessingService;

    @Autowired
    private TestService testService;

    @PostMapping(value = "/analyzer/fhir", consumes = { "application/fhir+json", MediaType.APPLICATION_JSON_VALUE,
            MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> importFhirBundle(HttpServletRequest request,
            @RequestHeader(value = "X-Analyzer-Id", required = false) String analyzerId) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            String bundleJson = new String(request.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, bundleJson);

            if (bundle.getType() != Bundle.BundleType.TRANSACTION && bundle.getType() != Bundle.BundleType.BATCH) {
                response.put("success", false);
                response.put("error", "analyzer.fhirImport.error.invalidBundleType");
                response.put("errorKey", "analyzer.fhirImport.error.invalidBundleType");
                response.put("errorArgs", Map.of("bundleType", String.valueOf(bundle.getType())));
                return ResponseEntity.badRequest().body(response);
            }

            if (containsCalibrationReport(bundle)) {
                response.put("success", false);
                response.put("error", "analyzer.fhirImport.error.calibrationRejected");
                response.put("errorKey", "analyzer.fhirImport.error.calibrationRejected");
                return ResponseEntity.badRequest().body(response);
            }

            // Resolve analyzer from bridge identifier (composite like "MINDRAY-BC-5380")
            Analyzer analyzer = null;
            if (analyzerId != null && !analyzerId.isBlank()) {
                // Try numeric ID first (direct DB lookup)
                if (isNumericId(analyzerId)) {
                    analyzer = tryGetAnalyzerById(analyzerId);
                }
                if (analyzer == null) {
                    analyzer = tryFindAnalyzerByIdentifier(analyzerId);
                }
                if (analyzer == null) {
                    // Try by name
                    analyzer = tryGetAnalyzerByName(analyzerId);
                }
            }

            // Fallback: try to resolve analyzer from Device resource in the bundle.
            // Prefer identifier-based resolution (stable) over device name (display-only).
            // Also capture the first Device for find-or-create-stub if no match.
            Device firstDevice = null;
            if (analyzer == null) {
                for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                    Resource resource = entry.getResource();
                    if (resource instanceof Device device) {
                        if (firstDevice == null) {
                            firstDevice = device;
                        }
                        // Try identifiers first (machineId, sourceId, pattern match)
                        if (device.hasIdentifier()) {
                            List<String> identifierValues = device.getIdentifier().stream()
                                    .map(org.hl7.fhir.r4.model.Identifier::getValue)
                                    .filter(v -> v != null && !v.isBlank()).toList();
                            analyzer = tryFindAnalyzerByIdentifier(identifierValues);
                        }
                        // Fall back to serial number (machineId)
                        if (analyzer == null && device.hasSerialNumber()) {
                            analyzer = tryFindAnalyzerByIdentifier(device.getSerialNumber());
                        }
                        // Last resort: device display name
                        if (analyzer == null && device.hasDeviceName()) {
                            String deviceName = device.getDeviceNameFirstRep().getName();
                            if (deviceName != null && !deviceName.isBlank()) {
                                analyzer = tryGetAnalyzerByName(deviceName);
                            }
                        }
                        if (analyzer != null) {
                            LogEvent.logInfo(CLASS_NAME, "importFhirBundle",
                                    "Resolved analyzer from bundle Device resource");
                            break;
                        }
                    }
                }
            }

            // Transparent-pipe principle (companion to bridge change in PR #34):
            // if we still don't have an analyzer but the bundle has a Device, create
            // a PENDING_REGISTRATION stub so the results can land in staging instead
            // of failing on the analyzer_results.analyzer_id NOT NULL constraint.
            // The bridge always forwards now (no more dead-letter on unknown source),
            // so this code path activates for first-message-from-new-analyzer.
            // Refs: feedback_bridge_transparent_fhir_pipe.md.
            if (analyzer == null && firstDevice != null) {
                analyzer = findOrCreateStubFromDevice(firstDevice, request);
            }

            // Collect Specimen identifiers for accession number lookup
            Map<String, String> specimenAccessions = new LinkedHashMap<>();
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Resource resource = entry.getResource();
                if (resource instanceof Specimen specimen) {
                    String fullUrl = entry.getFullUrl();
                    String accession = null;
                    if (specimen.hasIdentifier()) {
                        accession = specimen.getIdentifierFirstRep().getValue();
                    }
                    if (accession != null && fullUrl != null) {
                        specimenAccessions.put(fullUrl, accession);
                    }
                }
            }

            // Collect Patient identifiers (wire patient identity) for the staging
            // patient hint — same-day two-patient collisions under a shared or
            // reused accession are only detectable at the accept boundary if this
            // survives into analyzer_results (LIS-239).
            Map<String, String> patientIdentifiers = new LinkedHashMap<>();
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Resource resource = entry.getResource();
                if (resource instanceof Patient patient) {
                    String fullUrl = entry.getFullUrl();
                    String identifier = findAnalyzerPatientId(patient);
                    if (identifier != null && fullUrl != null) {
                        patientIdentifiers.put(fullUrl, identifier);
                    }
                }
            }

            // Map Observations to AnalyzerResults
            List<AnalyzerResults> results = new ArrayList<>();
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Resource resource = entry.getResource();
                if (resource instanceof Observation obs) {
                    AnalyzerResults ar = mapObservationToAnalyzerResult(obs, specimenAccessions, patientIdentifiers,
                            analyzer);
                    if (ar != null) {
                        results.add(ar);
                    }
                }
            }

            if (results.isEmpty()) {
                response.put("success", false);
                response.put("error", "analyzer.fhirImport.error.noObservations");
                response.put("errorKey", "analyzer.fhirImport.error.noObservations");
                return ResponseEntity.badRequest().body(response);
            }

            String userId = getSysUserId(request);
            if (userId == null) {
                LogEvent.logWarn(CLASS_NAME, "importFhirBundle",
                        "Could not resolve sysUserId from request — using system default");
                userId = "1";
            }
            analyzerResultsService.insertAnalyzerResults(results, userId);

            // Process QC results through Westgard pipeline
            int qcProcessed = 0;
            for (AnalyzerResults ar : results) {
                if (ar.getIsControl() && ar.getTestId() != null && analyzer != null) {
                    processQCAnalyzerResult(ar, analyzer);
                    qcProcessed++;
                }
            }

            response.put("success", true);
            response.put("resultsInserted", results.size());
            response.put("qcResultsProcessed", qcProcessed);
            response.put("analyzerId", analyzer != null ? analyzer.getId() : null);
            LogEvent.logInfo(CLASS_NAME, "importFhirBundle", "Inserted " + results.size() + " results from FHIR Bundle"
                    + " (" + qcProcessed + " QC)" + (analyzer != null ? " for analyzer " + analyzer.getName() : ""));

            return ResponseEntity.ok(response);

        } catch (FieldTooLongException e) {
            // Identity-bearing wire value exceeds its staging column — reject the
            // bundle (400) instead of letting the insert fail with a 500 the
            // bridge would retry forever. 4xx is non-retryable bridge-side: the
            // bundle dead-letters to its rejected-bundles surface for an operator.
            LogEvent.logWarn(CLASS_NAME, "importFhirBundle", "Rejecting bundle: " + e.getMessage());
            response.put("success", false);
            response.put("error", "analyzer.fhirImport.error.fieldTooLong");
            response.put("errorKey", "analyzer.fhirImport.error.fieldTooLong");
            response.put("errorArgs", Map.of("field", e.field, "maxLength", String.valueOf(e.maxLength), "actualLength",
                    String.valueOf(e.actualLength)));
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            LogEvent.logError(e);
            LogEvent.logError(CLASS_NAME, "importFhirBundle",
                    "FHIR Bundle import failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            response.put("success", false);
            response.put("error", "analyzer.fhirImport.error.importFailed");
            response.put("errorKey", "analyzer.fhirImport.error.importFailed");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private boolean isNumericId(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private boolean containsCalibrationReport(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            if (resource instanceof DiagnosticReport report && report.hasMeta() && report.getMeta().hasTag()) {
                boolean calibration = report.getMeta().getTag().stream()
                        .anyMatch(t -> CALIBRATION_TAG.equals(t.getCode()) && (t.getSystem() == null
                                || t.getSystem().isBlank() || OPENELIS_FHIR_TAG_SYSTEM.equals(t.getSystem())));
                if (calibration) {
                    LogEvent.logWarn(CLASS_NAME, "importFhirBundle",
                            "Rejecting calibration DiagnosticReport from analyzer FHIR import");
                    return true;
                }
            }
        }
        return false;
    }

    private Analyzer tryGetAnalyzerById(String analyzerId) {
        try {
            return analyzerService.get(analyzerId);
        } catch (ObjectNotFoundException e) {
            LogEvent.logWarn(CLASS_NAME, "tryGetAnalyzerById",
                    "Analyzer id from header no longer exists: " + analyzerId);
            return null;
        }
    }

    private Analyzer tryFindAnalyzerByIdentifier(String identifier) {
        return analyzerService.findByIdentifierPatternMatch(identifier).orElse(null);
    }

    private Analyzer tryFindAnalyzerByIdentifier(List<String> identifiers) {
        return analyzerService.findByIdentifierPatternMatch(identifiers).orElse(null);
    }

    private Analyzer tryGetAnalyzerByName(String name) {
        return analyzerService.getByName(name).orElse(null);
    }

    /**
     * Resolve a LOINC-coded Observation to an OE2 Test, reusing the mechanism OE2
     * has used to import external FHIR orders for years
     * ({@code TaskInterpreterImpl.createTestFromFHIR} →
     * {@code TestService.getTestsByLoincCode}). Returns null when the Observation
     * carries no LOINC coding or no test matches — the caller then falls back to
     * the legacy analyzer-code mapping. Package-private for test.
     */
    org.openelisglobal.test.valueholder.Test resolveLoincTest(Observation obs) {
        if (obs == null || !obs.hasCode() || !obs.getCode().hasCoding()) {
            return null;
        }
        for (org.hl7.fhir.r4.model.Coding coding : obs.getCode().getCoding()) {
            if (LOINC_SYSTEM.equals(coding.getSystem()) && coding.hasCode()) {
                String loinc = coding.getCode();
                if (loinc != null && !loinc.isBlank()) {
                    List<org.openelisglobal.test.valueholder.Test> tests = testService.getTestsByLoincCode(loinc);
                    if (tests != null && !tests.isEmpty()) {
                        return tests.get(0);
                    }
                }
            }
        }
        return null;
    }

    /**
     * The wire patient identity the bridge stamped on this Patient resource — the
     * identifier under {@link #ANALYZER_PATIENT_ID_SYSTEM}, falling back to the
     * first identifier with a value. Trimmed — whitespace variance between an
     * original and a re-export must not read as a patient mismatch (LIS-244). Null
     * (never blank) when none.
     */
    private String findAnalyzerPatientId(Patient patient) {
        String fallback = null;
        for (org.hl7.fhir.r4.model.Identifier identifier : patient.getIdentifier()) {
            if (!identifier.hasValue() || identifier.getValue().isBlank()) {
                continue;
            }
            if (ANALYZER_PATIENT_ID_SYSTEM.equals(identifier.getSystem())) {
                return identifier.getValue().trim();
            }
            if (fallback == null) {
                fallback = identifier.getValue().trim();
            }
        }
        return fallback;
    }

    /**
     * Truncate a signal-only wire value to its staging column width, logging what
     * was dropped. Over-length values otherwise fail the whole bundle insert, which
     * the bridge retries forever (LIS-244). Never used for identity-bearing fields
     * — those reject via {@link FieldTooLongException} instead.
     */
    private static String truncateForStaging(String value, int maxLength, String field, String accession) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        LogEvent.logWarn(CLASS_NAME, "truncateForStaging", "Truncating over-length " + field + " (" + value.length()
                + " > " + maxLength + " chars) for accession=" + accession);
        return value.substring(0, maxLength);
    }

    /**
     * Add a reason to the staged row's import_issue_reason instead of clobbering
     * whatever is already there — an unmapped test AND a skewed analyzer clock can
     * both be true of the same Observation, and both need to survive to the
     * import-issues dashboard. Comma-joined and re-truncated to the column width
     * (LIS-244 pattern) so an accumulation of reasons can never fail the insert.
     */
    private static void appendImportIssueReason(AnalyzerResults ar, String reason) {
        String existing = ar.getImportIssueReason();
        String combined = (existing == null || existing.isBlank()) ? reason : existing + "," + reason;
        ar.setImportIssueReason(truncateForStaging(combined, AnalyzerResults.IMPORT_ISSUE_REASON_MAX_LENGTH,
                "import_issue_reason", ar.getAccessionNumber()));
    }

    /**
     * An identity-bearing wire value exceeds its staging column width — the bundle
     * must be rejected with a 400 naming the field, not truncated (truncation would
     * change matching semantics) and not left to fail the insert (a 500 the bridge
     * retries forever).
     */
    private static class FieldTooLongException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String field;
        private final int maxLength;
        private final int actualLength;

        FieldTooLongException(String field, int maxLength, int actualLength) {
            super(field + " length " + actualLength + " exceeds column width " + maxLength);
            this.field = field;
            this.maxLength = maxLength;
            this.actualLength = actualLength;
        }
    }

    private AnalyzerResults mapObservationToAnalyzerResult(Observation obs, Map<String, String> specimenAccessions,
            Map<String, String> patientIdentifiers, Analyzer analyzer) {

        AnalyzerResults ar = new AnalyzerResults();

        // Analyzer ID
        if (analyzer != null) {
            ar.setAnalyzerId(analyzer.getId());
        }

        // Accession number from Specimen reference
        if (obs.hasSpecimen()) {
            String specimenRef = obs.getSpecimen().getReference();
            String accession = specimenAccessions.get(specimenRef);
            if (accession != null) {
                ar.setAccessionNumber(accession);
            }
        }
        // Fallback: check Observation.subject.identifier
        if (ar.getAccessionNumber() == null && obs.hasSubject() && obs.getSubject().hasIdentifier()) {
            ar.setAccessionNumber(obs.getSubject().getIdentifier().getValue());
        }

        // Wire patient identity from the subject's in-bundle Patient (LIS-239).
        // Absent subject/Patient/identifier → null: a null hint carries no
        // signal and never blocks downstream. Comparison-only signal, so an
        // over-length value truncates — originals and re-exports pass through
        // this same boundary and truncate identically (LIS-244).
        if (obs.hasSubject() && obs.getSubject().hasReference()) {
            ar.setPatientHint(truncateForStaging(patientIdentifiers.get(obs.getSubject().getReference()),
                    AnalyzerResults.PATIENT_HINT_MAX_LENGTH, "patient_hint", ar.getAccessionNumber()));
        }

        if (ar.getAccessionNumber() == null) {
            LogEvent.logWarn(CLASS_NAME, "mapObservationToAnalyzerResult",
                    "Observation has no accession number — skipping");
            return null;
        }

        // accession_number is identity-bearing: truncating it would silently
        // change sample-matching semantics (wrong-sample attach risk), so an
        // over-length value rejects the whole bundle instead (LIS-244).
        if (ar.getAccessionNumber().length() > AnalyzerResults.ACCESSION_NUMBER_MAX_LENGTH) {
            throw new FieldTooLongException("accession_number", AnalyzerResults.ACCESSION_NUMBER_MAX_LENGTH,
                    ar.getAccessionNumber().length());
        }

        // Full wire values feed the LOINC/analyzer-code lookups below; only the
        // stored provenance copies truncate to their column widths (LIS-244).
        String loincCode = findLoincCode(obs);
        String rawCode = findRawAnalyzerCode(obs);
        ar.setLoinc(truncateForStaging(loincCode, AnalyzerResults.LOINC_MAX_LENGTH, "loinc", ar.getAccessionNumber()));
        ar.setRawCode(
                truncateForStaging(rawCode, AnalyzerResults.RAW_CODE_MAX_LENGTH, "raw_code", ar.getAccessionNumber()));

        // Raw analyzer code remains the legacy analyzer-test-map lookup key. For
        // older non-dual-coded payloads, retain the prior first-coding/text fallback.
        String testCode = rawCode;
        if (testCode == null && obs.hasCode() && obs.getCode().hasCoding()) {
            Coding coding = obs.getCode().getCodingFirstRep();
            testCode = coding.hasCode() ? coding.getCode() : coding.getDisplay();
        } else if (testCode == null && obs.hasCode() && obs.getCode().hasText()) {
            testCode = obs.getCode().getText();
        }

        LogEvent.logInfo(CLASS_NAME, "mapObservationToAnalyzerResult", "accession=" + ar.getAccessionNumber()
                + " testCode=" + testCode + " analyzerId=" + (analyzer != null ? analyzer.getId() : "null"));

        // Prefer LOINC resolution: the bridge now emits LOINC-coded Observations
        // (bridge owns analyzer-code↔LOINC), and OE2 resolves LOINC→test via the
        // SAME path it has used for external FHIR orders for years
        // (TaskInterpreterImpl.createTestFromFHIR → TestService.getTestsByLoincCode).
        // OE2 is analyzer-agnostic: it binds inbound results by LOINC only. The
        // bridge owns analyzer-code↔LOINC translation, so an Observation that
        // doesn't carry a resolvable LOINC is staged unmapped (no analyzer-code
        // binding in OE2). The result still stages — a tech can resolve it.
        org.openelisglobal.test.valueholder.Test loincTest = resolveLoincTest(obs);
        if (loincTest != null) {
            ar.setTestId(loincTest.getId());
            ar.setTestName(loincTest.getLocalizedName() != null ? loincTest.getLocalizedName() : testCode);
        } else {
            // LOINC didn't resolve — the analyzer/test isn't LOINC-coded yet, or the
            // bridge passed a raw analyzer code. Fall back to the lab's per-analyzer
            // analyzer-code→test mapping (analyzer_test_map via AnalyzerTestNameCache),
            // the path OE2 used before the LOINC interlingua. Without this fallback,
            // HL7/FILE/QC results carrying raw analyzer codes stage read-only and never
            // resolve — which breaks QC processing and result import for any analyzer
            // whose tests aren't LOINC-coded.
            org.openelisglobal.analyzerimport.util.MappedTestName mapped = (analyzer != null && testCode != null
                    && !testCode.isBlank())
                            ? org.openelisglobal.analyzerimport.util.AnalyzerTestNameCache.getInstance()
                                    .getMappedTestByAnalyzerId(analyzer.getId(), testCode)
                            : null;
            String mappedTestId = mapped != null ? mapped.getTestId() : null;
            if (mappedTestId != null) {
                ar.setTestId(mappedTestId);
                ar.setTestName(mapped.getOpenElisTestName() != null ? mapped.getOpenElisTestName() : testCode);
            } else {
                ar.setTestName(testCode);
                ar.setReadOnly(true);
                appendImportIssueReason(ar, "unmapped_loinc:" + testCode);
            }
        }

        // Result value
        if (obs.hasValueQuantity() && obs.getValueQuantity().hasValue()) {
            Quantity qty = obs.getValueQuantity();
            // Off-scale qualified results arrive as Quantity.comparator + magnitude
            // (bridge, LIS-252). Reconstruct the human-readable qualified value
            // (<0.008, >=500) — the comparator codes "<", "<=", ">=", ">" are the
            // reportable symbols — and stage it as a numeric result, exactly how OE
            // stores a manually entered qualified numeric at accept.
            String magnitude = qty.getValue().toPlainString();
            ar.setResult(qty.hasComparator() ? qty.getComparator().toCode() + magnitude : magnitude);
            ar.setUnits(qty.getUnit());
            ar.setRawUnit(truncateForStaging(qty.getUnit(), AnalyzerResults.RAW_UNIT_MAX_LENGTH, "raw_unit",
                    ar.getAccessionNumber()));
            if (UCUM_SYSTEM.equals(qty.getSystem()) && qty.hasCode()) {
                ar.setUcumValue(truncateForStaging(qty.getCode(), AnalyzerResults.UCUM_VALUE_MAX_LENGTH, "ucum_value",
                        ar.getAccessionNumber()));
            }
            ar.setResultType("N");
        } else if (obs.hasValueStringType()) {
            ar.setResult(obs.getValueStringType().getValue());
            ar.setResultType("A");
        } else if (obs.hasValueCodeableConcept()) {
            ar.setResult(obs.getValueCodeableConcept().hasText() ? obs.getValueCodeableConcept().getText()
                    : obs.getValueCodeableConcept().getCodingFirstRep().getDisplay());
            ar.setResultType("A");
        }

        if (ar.getResult() == null) {
            LogEvent.logWarn(CLASS_NAME, "mapObservationToAnalyzerResult", "Observation has no value — skipping");
            return null;
        }

        ar.setNormalizationStatus(normalizationStatus(ar.getLoinc(), ar.getUcumValue()));

        // QC/control flag from bridge tag. QC (MSH-16=2) rows are staged for the
        // Westgard pipeline and audit trail, but marked read-only so the analyzer-
        // results accept path (AnalyzerResultsAcceptServiceImpl#buildSampleGroupings
        // skips read-only items) can never carry a control into a patient
        // Result/Analysis. The bundle-level calibration reject above only covers
        // MSH-16=1, so QC needs its own fail-closed guard here — otherwise a mapped
        // control row (testId resolved, readOnly=false) is acceptable as a patient
        // result, since per-item readOnly does not incorporate isControl (LIS-95).
        if (obs.hasMeta() && obs.getMeta().hasTag()) {
            boolean isQc = obs.getMeta().getTag().stream().anyMatch(t -> "QC".equals(t.getCode()));
            ar.setIsControl(isQc);
            if (isQc) {
                ar.setReadOnly(true);
            }
        }

        // QC metadata from bridge extensions — used by
        // QCResultProcessingService.findMatchingControlLot to resolve to
        // the right qc_control_lot row directly. Populated by the bridge
        // when it could extract them (ASTM Q-segment OR matched FILE
        // qcRule operand). Carried in-memory only (transient on AR), so
        // no schema migration required.
        if (obs.hasExtension("http://openelis-global.org/fhir/qc/lot-number")) {
            org.hl7.fhir.r4.model.Extension lotExt = obs
                    .getExtensionByUrl("http://openelis-global.org/fhir/qc/lot-number");
            if (lotExt != null && lotExt.getValue() instanceof org.hl7.fhir.r4.model.StringType) {
                ar.setLotNumber(((org.hl7.fhir.r4.model.StringType) lotExt.getValue()).getValue());
            }
        }
        if (obs.hasExtension("http://openelis-global.org/fhir/qc/control-level")) {
            org.hl7.fhir.r4.model.Extension levelExt = obs
                    .getExtensionByUrl("http://openelis-global.org/fhir/qc/control-level");
            if (levelExt != null && levelExt.getValue() instanceof org.hl7.fhir.r4.model.StringType) {
                ar.setControlLevel(((org.hl7.fhir.r4.model.StringType) levelExt.getValue()).getValue());
            }
        }

        // Completion timestamp from effectiveDateTime — completeDate keeps trusting
        // the analyzer's onboard clock verbatim, exactly as before (LIS-128's
        // cross-day correction guard and the clinical completion time both key
        // off it; this slice does not change accept/hold behavior). importReceivedTime
        // records OE's own wall clock at the moment of processing, independent of
        // what the analyzer reported, so the row stays truthful about provenance
        // regardless of clock skew (LIS-271).
        Timestamp importReceivedTime = new Timestamp(System.currentTimeMillis());
        ar.setImportReceivedTime(importReceivedTime);
        Date analyzerReportedTime = null;
        if (obs.hasEffectiveDateTimeType()) {
            try {
                analyzerReportedTime = obs.getEffectiveDateTimeType().getValue();
                ar.setCompleteDate(new Timestamp(analyzerReportedTime.getTime()));
            } catch (Exception e) {
                ar.setCompleteDate(importReceivedTime);
            }
        } else {
            ar.setCompleteDate(importReceivedTime);
        }

        // Flag (never block) an implausible gap between the two clocks. A
        // syntactically valid-but-wrong analyzer timestamp never trips the
        // parse-exception fallback above, so this is the only place a bad
        // analyzer clock becomes visible.
        if (analyzerReportedTime != null) {
            long skewMillis = Math.abs(importReceivedTime.getTime() - analyzerReportedTime.getTime());
            if (skewMillis > CLOCK_SKEW_THRESHOLD_MILLIS) {
                LogEvent.logWarn(CLASS_NAME, "mapObservationToAnalyzerResult",
                        "Analyzer-reported completion time is " + skewMillis
                                + "ms from import receive time (> 24h threshold) for accession="
                                + ar.getAccessionNumber() + " — flagging clock-skew, not blocking (LIS-271)");
                appendImportIssueReason(ar, CLOCK_SKEW_ISSUE_REASON);
            }
        }

        // testId is set by the mapping lookup above; if unmapped, it stays null
        // and readOnly=true so the result shows as "configuration needed"
        if (ar.getTestId() == null) {
            ar.setReadOnly(true);
        }

        return ar;
    }

    private String findLoincCode(Observation observation) {
        if (observation == null || !observation.hasCode() || !observation.getCode().hasCoding()) {
            return null;
        }
        return observation.getCode().getCoding().stream()
                .filter(coding -> LOINC_SYSTEM.equals(coding.getSystem()) && coding.hasCode()).map(Coding::getCode)
                .filter(code -> code != null && !code.isBlank()).findFirst().orElse(null);
    }

    private String findRawAnalyzerCode(Observation observation) {
        if (observation == null || !observation.hasCode() || !observation.getCode().hasCoding()) {
            return null;
        }
        return observation.getCode().getCoding().stream()
                .filter(coding -> coding.getSystem() == null || coding.getSystem().isBlank()).filter(Coding::hasCode)
                .map(Coding::getCode).filter(code -> code != null && !code.isBlank()).findFirst().orElse(null);
    }

    private String normalizationStatus(String loinc, String ucumValue) {
        boolean hasLoinc = loinc != null && !loinc.isBlank();
        boolean hasUcum = ucumValue != null && !ucumValue.isBlank();
        if (hasLoinc && hasUcum) {
            return "NORMALIZED";
        }
        if (hasLoinc || hasUcum) {
            return "PARTIAL";
        }
        return "UNMAPPED";
    }

    /**
     * Transparent-pipe companion: find-or-create a PENDING_REGISTRATION stub
     * analyzer from the bundle's Device resource when no existing analyzer matched.
     * Called only after identifier/name/serial-number resolution has already
     * failed, so we're creating exactly for the "first message from a new analyzer"
     * path.
     *
     * <p>
     * The stable handle is the network sourceId the bridge emits as
     * {@code Device.identifier[system="https://openelis-global.org/fhir/source-ip"]}.
     * Without it we can't safely de-duplicate across subsequent bundles, so we
     * decline to stub (caller will surface the missing-analyzer error).
     *
     * <p>
     * Mirrors {@code AnalyzerRestController.reportDiscoveredSource} so the
     * side-channel endpoint and the FHIR path converge on identical stub shape.
     * Duplicate-key races are handled by re-lookup via discoveredSourceId.
     *
     * @param device  the first Device resource from the bundle
     * @param request the inbound HTTP request (for sysUserId resolution)
     * @return the existing or newly-created stub analyzer, or null if we could not
     *         derive a stable sourceId
     */
    private Analyzer findOrCreateStubFromDevice(Device device, HttpServletRequest request) {
        String sourceId = extractSourceIdFromDevice(device);
        if (sourceId == null || sourceId.isBlank()) {
            LogEvent.logWarn(CLASS_NAME, "findOrCreateStubFromDevice",
                    "Device has no identifier with system=source-ip — cannot create stub deterministically");
            return null;
        }

        // Fast path: stub already exists from a prior message.
        java.util.Optional<Analyzer> existing = analyzerService.findByDiscoveredSourceId(sourceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String displayName = deriveDisplayName(device, sourceId);

        Analyzer stub = new Analyzer();
        stub.ensureFhirUuid();
        stub.setName(displayName);
        stub.setStatus(AnalyzerStatus.PENDING_REGISTRATION);
        stub.setDiscoveredSourceId(sourceId);
        String userId = getSysUserId(request);
        stub.setSysUserId(userId != null ? userId : "1");

        try {
            String analyzerId = analyzerService.insert(stub);
            LogEvent.logInfo(CLASS_NAME, "findOrCreateStubFromDevice",
                    "Created PENDING_REGISTRATION stub analyzerId=" + analyzerId + " for sourceId=" + sourceId);
            applyMatchedProfile(analyzerId, device, stub.getSysUserId());
            return analyzerService.get(analyzerId);
        } catch (RuntimeException e) {
            if (isDuplicateKeyViolation(e)) {
                // Race: another bundle just created the same stub. Re-look it up.
                return analyzerService.findByDiscoveredSourceId(sourceId).orElse(null);
            }
            LogEvent.logError(CLASS_NAME, "findOrCreateStubFromDevice",
                    "Failed to insert stub for sourceId=" + sourceId + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Match the Device against the profile registry and apply the winning profile's
     * {@code default_test_mappings} to the newly-created stub.
     *
     * <p>
     * Without this step, stubs land with zero analyzer_test_map rows — every result
     * stays {@code read_only=true} until an admin manually configures mappings.
     * With the match-and-apply, the first message from a known analyzer family
     * (GeneXpert, Mindray, etc.) arrives with mappings pre-populated from the
     * profile's UNION of device-emitted codes, so results land accept-ready.
     *
     * <p>
     * Best-effort: mapping failures don't roll back the stub (separate
     * 
     * @Transactional boundary inside autoCreateTestMappings). Unknown analyzers
     *                with no profile match leave the stub bare and log a clear WARN
     *                so an operator can add a profile.
     */
    private void applyMatchedProfile(String analyzerId, Device device, String sysUserId) {
        try {
            ProfileMatch match = matchProfileFromDevice(device);
            if (match != null) {
                LogEvent.logInfo(CLASS_NAME, "applyMatchedProfile", "Matched profile " + match.fileName + " (score="
                        + match.score + ") for analyzer " + analyzerId + " — applying default_test_mappings");
                analyzerService.autoCreateTestMappings(analyzerId, match.config, sysUserId);
            } else {
                LogEvent.logWarn(CLASS_NAME, "applyMatchedProfile",
                        "No matching profile found for analyzer " + analyzerId + " — staging will remain read-only "
                                + "until admin creates a matching profile in /data/analyzer-profiles/");
            }
        } catch (RuntimeException e) {
            LogEvent.logWarn(CLASS_NAME, "applyMatchedProfile", "Profile apply failed for analyzer " + analyzerId
                    + " (stub persists): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Iterate all profile JSONs under {@code $ANALYZER_PROFILES_DIR} (default
     * {@code /data/analyzer-profiles}) and return the highest-scoring match for the
     * Device, or null if no profile scores at least
     * {@link #MIN_PROFILE_MATCH_SCORE}. Matching is data-driven — drop a new
     * profile in the directory and it becomes selectable without code change.
     */
    private ProfileMatch matchProfileFromDevice(Device device) {
        Path baseDir = resolveProfilesBaseDir();
        if (!Files.isDirectory(baseDir)) {
            return null;
        }
        String mfr = device.hasManufacturer() ? device.getManufacturer() : null;
        String name = device.hasDeviceName() ? device.getDeviceNameFirstRep().getName() : null;
        String senderToken = extractSenderTokenFromDevice(device);

        ProfileMatch best = null;
        for (String protocol : PROFILE_PROTOCOL_DIRS) {
            Path protocolDir = baseDir.resolve(protocol);
            if (!Files.isDirectory(protocolDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(protocolDir)) {
                List<Path> files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
                for (Path file : files) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> profile = objectMapper.readValue(file.toFile(), Map.class);
                        int score = scoreProfile(profile, mfr, name, senderToken);
                        if (score >= MIN_PROFILE_MATCH_SCORE && (best == null || score > best.score)) {
                            best = new ProfileMatch(file.getFileName().toString(), profile, score);
                        }
                    } catch (IOException ioe) {
                        LogEvent.logWarn(CLASS_NAME, "matchProfileFromDevice",
                                "Skipping unreadable profile " + file + ": " + ioe.getMessage());
                    }
                }
            } catch (IOException e) {
                LogEvent.logWarn(CLASS_NAME, "matchProfileFromDevice",
                        "Failed to list " + protocolDir + ": " + e.getMessage());
            }
        }
        return best;
    }

    private int scoreProfile(Map<String, Object> profile, String deviceMfr, String deviceName, String senderToken) {
        int score = 0;
        String profileMfr = asString(profile.get("manufacturer"));
        String profileName = asString(profile.get("analyzer_name"));
        String pattern = asString(profile.get("identifier_pattern"));

        if (deviceMfr != null && profileMfr != null && profileMfr.equalsIgnoreCase(deviceMfr)) {
            score += 2;
        }
        if (deviceName != null && profileName != null && profileName.toUpperCase().contains(deviceName.toUpperCase())) {
            score += 2;
        }
        if (pattern != null && !pattern.isBlank()) {
            try {
                Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                if (deviceName != null && p.matcher(deviceName).find()) {
                    score += 1;
                }
                if (senderToken != null && p.matcher(senderToken).find()) {
                    score += 1;
                }
            } catch (PatternSyntaxException ignored) {
                // Malformed profile regex — score only via other signals.
            }
        }
        return score;
    }

    private String extractSenderTokenFromDevice(Device device) {
        if (!device.hasIdentifier()) {
            return null;
        }
        return device.getIdentifier().stream()
                .filter(id -> "https://openelis-global.org/fhir/sender-token".equals(id.getSystem()))
                .map(org.hl7.fhir.r4.model.Identifier::getValue).filter(v -> v != null && !v.isBlank()).findFirst()
                .orElse(null);
    }

    private Path resolveProfilesBaseDir() {
        String dir = System.getenv("ANALYZER_PROFILES_DIR");
        if (dir == null || dir.isBlank()) {
            dir = "/data/analyzer-profiles";
        }
        return Path.of(dir);
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private record ProfileMatch(String fileName, Map<String, Object> config, int score) {
    }

    private String extractSourceIdFromDevice(Device device) {
        if (!device.hasIdentifier()) {
            return null;
        }
        return device.getIdentifier().stream()
                .filter(id -> "https://openelis-global.org/fhir/source-ip".equals(id.getSystem()))
                .map(org.hl7.fhir.r4.model.Identifier::getValue).filter(v -> v != null && !v.isBlank()).findFirst()
                .orElse(null);
    }

    private String deriveDisplayName(Device device, String sourceId) {
        // Prefer device name (e.g. "GeneXpert"), fall back to sender-token
        // identifier (e.g. "LA2M3^GeneXpert^6.2"), last resort "Unknown (ip)".
        String name = null;
        if (device.hasDeviceName()) {
            name = device.getDeviceNameFirstRep().getName();
        }
        if ((name == null || name.isBlank()) && device.hasIdentifier()) {
            name = device.getIdentifier().stream()
                    .filter(id -> "https://openelis-global.org/fhir/sender-token".equals(id.getSystem()))
                    .map(org.hl7.fhir.r4.model.Identifier::getValue).filter(v -> v != null && !v.isBlank()).findFirst()
                    .orElse(null);
        }
        if (name == null || name.isBlank()) {
            name = "Unknown (" + sourceId + ")";
        }
        // Analyzer.name is VARCHAR(100)
        if (name.length() > 100) {
            name = name.substring(0, 97) + "...";
        }
        return name;
    }

    private boolean isDuplicateKeyViolation(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("duplicate key") || msg.contains("unique constraint"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /**
     * Route a QC-flagged AnalyzerResult to the Westgard QC pipeline.
     *
     * <p>
     * The accession number for QC samples is typically the control lot number or
     * control ID assigned by the lab. The QCResultProcessingService looks up the
     * matching {@code QCControlLot} by lot number + test + instrument and creates a
     * {@code QCResult} that triggers Westgard rule evaluation.
     */
    private void processQCAnalyzerResult(AnalyzerResults ar, Analyzer analyzer) {
        try {
            // An off-scale qualified control value (<0.008, >=500) carries a
            // comparator; strip it so Westgard math sees the magnitude rather than
            // throwing and dropping the control out of QC processing (LIS-252).
            BigDecimal resultValue = new BigDecimal(StringUtil.stripLeadingComparator(ar.getResult()));
            LocalDateTime timestamp = ar.getCompleteDate() != null
                    ? ar.getCompleteDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                    : LocalDateTime.now();

            qcResultProcessingService.processQCResult(analyzer.getId(), ar.getTestId(), ar.getAccessionNumber(),
                    ar.getLotNumber(), ar.getControlLevel(), resultValue, ar.getUnits(), timestamp);
        } catch (NumberFormatException e) {
            LogEvent.logWarn(CLASS_NAME, "processQCAnalyzerResult",
                    "QC result value is not numeric — skipping QC processing: " + ar.getResult());
        } catch (Exception e) {
            LogEvent.logError(CLASS_NAME, "processQCAnalyzerResult", "QC processing failed for accession="
                    + ar.getAccessionNumber() + " test=" + ar.getTestId() + ": " + e.getMessage());
        }
    }
}
