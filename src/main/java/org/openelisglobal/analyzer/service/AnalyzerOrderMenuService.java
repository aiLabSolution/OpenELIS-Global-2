package org.openelisglobal.analyzer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.spring.util.SpringContext;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves the pending order menu for one accession or analyzer barcode — the
 * ordered-but-unresulted tests as LOINC. This answers analyzer host queries
 * (ASTM Q-record or HL7 QRY^R02 via the bridge): the analyzer scans a barcoded
 * tube, the bridge asks OE what work is pending for that identifier, and
 * translates the returned LOINCs to the analyzer's own codes. OE stays
 * analyzer-agnostic and speaks LOINC only, the same interlingua as the
 * {@link AnalyzerOrderDispatchService} push path.
 *
 * <p>
 * "Unresulted" is defined by exclusion: Canceled, SampleRejected, Finalized,
 * TechnicalAcceptance and (legacy) NonConforming analyses are already resulted
 * or dead. Rejected analyses (TechnicalRejected, BiologistRejected) stay in the
 * menu — they are re-run work the analyzer should pick up.
 */
@Service
public class AnalyzerOrderMenuService {

    // A machine-generated analyzer barcode: short alpha prefix followed by a
    // long digit run (H99S synthetic seed: DEV01260000000000002). Only
    // identifiers of this shape get embedded-accession canonicalization —
    // human-entered accessions, QC labels ("QC-01") and typos ("1002") must
    // stay exact-match-only, or a miss could silently resolve to another
    // patient's sample. Widen only against real wire captures.
    private static final Pattern BARCODE_SHAPE = Pattern.compile("[A-Za-z]{1,5}\\d{10,}");

    @Autowired
    private SampleService sampleService;

    @Autowired
    private AnalysisService analysisService;

    // Deliberately NOT @Autowired: nothing in core field-injects
    // IStatusService — its @PostConstruct dependency graph must not be pulled
    // into early context bootstrap. Resolved at first use, per the
    // SpringContext.getBean convention used elsewhere (tests inject a mock).
    private IStatusService statusService;

    /**
     * Resolve the pending order menu for an accession or analyzer barcode, or null
     * when no sample exists for it.
     */
    public OrderMenu getOrderMenu(String sampleIdentifier) {
        if (sampleIdentifier == null || sampleIdentifier.isBlank()) {
            throw new IllegalArgumentException("accessionNumber required");
        }

        String trimmed = sampleIdentifier.trim();
        Sample sample = sampleService.getSampleByAccessionNumber(trimmed);
        if (sample == null) {
            sample = resolveBarcodeAlias(trimmed);
        }
        if (sample == null) {
            return null;
        }

        // Same LOINC resolution as the dispatch push path (preserve order,
        // de-dupe), but restricted to analyses still awaiting a result.
        List<String> loincCodes = new ArrayList<>();
        for (Analysis analysis : analysisService.getAnalysesBySampleIdExcludedByStatusId(sample.getId(),
                resultedOrDeadStatusIds())) {
            Test test = analysis.getTest();
            String loinc = test != null ? test.getLoinc() : null;
            if (loinc != null && !loinc.isBlank() && !loincCodes.contains(loinc)) {
                loincCodes.add(loinc);
            }
        }

        OrderMenu menu = new OrderMenu();
        menu.accessionNumber = sample.getAccessionNumber();
        menu.patientId = resolvePatientId(sample);
        menu.loincCodes = loincCodes;
        return menu;
    }

    private String resolvePatientId(Sample sample) {
        Patient patient = sampleService.getPatient(sample);
        return patient != null ? patient.getId() : sample.getId();
    }

    /**
     * Resolve a barcode-shaped identifier to its sample via embedded-accession
     * candidates. The exact accession lookup has already been tried and missed.
     * Candidates that resolve to more than one distinct sample are refused
     * outright: an ORF answered with the wrong sample's pending orders makes the
     * analyzer adopt that accession and post results under another patient, so
     * ambiguity fails closed — the analyzer simply gets no worklist and the
     * operator investigates.
     */
    private Sample resolveBarcodeAlias(String identifier) {
        Sample resolved = null;
        for (String candidate : barcodeAccessionCandidates(identifier)) {
            Sample match = sampleService.getSampleByAccessionNumber(candidate);
            if (match == null) {
                continue;
            }
            if (resolved != null && !resolved.getId().equals(match.getId())) {
                LogEvent.logWarn(this.getClass().getSimpleName(), "resolveBarcodeAlias",
                        "barcode identifier " + identifier + " matches accessions " + resolved.getAccessionNumber()
                                + " and " + match.getAccessionNumber() + "; refusing ambiguous host-query lookup");
                return null;
            }
            resolved = match;
        }
        return resolved;
    }

    static List<String> barcodeAccessionCandidates(String identifier) {
        if (!BARCODE_SHAPE.matcher(identifier).matches()) {
            return List.of();
        }
        String digits = identifier.replaceAll("\\D", "");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i < digits.length(); i++) {
            String suffix = stripLeadingZeros(digits.substring(i));
            if (!suffix.isBlank()) {
                candidates.add(suffix);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+", "");
        return stripped.isBlank() ? "0" : stripped;
    }

    private Set<String> resultedOrDeadStatusIds() {
        IStatusService status = statusService();
        Set<String> excluded = new HashSet<>();
        excluded.add(status.getStatusID(AnalysisStatus.Canceled));
        excluded.add(status.getStatusID(AnalysisStatus.SampleRejected));
        excluded.add(status.getStatusID(AnalysisStatus.Finalized));
        excluded.add(status.getStatusID(AnalysisStatus.TechnicalAcceptance));
        excluded.add(status.getStatusID(AnalysisStatus.NonConforming_depricated));
        return excluded;
    }

    private IStatusService statusService() {
        if (statusService == null) {
            statusService = SpringContext.getBean(IStatusService.class);
        }
        return statusService;
    }

    public static class OrderMenu {
        public String accessionNumber;
        // Optional correlation aid, same convention as the push path: the
        // analyzer keys on the accession, not the patient.
        public String patientId;
        public List<String> loincCodes;
    }
}
