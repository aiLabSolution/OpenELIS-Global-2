package org.openelisglobal.dataexchange.fhir.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Specimen;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.dataexchange.fhir.FhirConfig;
import org.openelisglobal.localization.valueholder.Localization;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.sampleitem.valueholder.SampleItem;
import org.openelisglobal.test.service.TestService;
import org.openelisglobal.typeofsample.valueholder.TypeOfSample;

/**
 * S4.3 (LIS-43) FHIR conformance gate: from a <b>finalized</b> result's
 * {@link DiagnosticReport}, the referenced {@link Specimen} and {@link Device}
 * must (a) build as FHIR R4 resources that pass HAPI instance validation
 * (<code>$validate</code>) and (b) <i>resolve and link</i> from the report.
 *
 * <p>
 * Linkage proven here (the slice's premise, "Specimen and Device resources
 * resolve and link from the DiagnosticReport"):
 * <ul>
 * <li><b>DiagnosticReport &rarr; Specimen</b> — direct, via
 * {@code DiagnosticReport.specimen}; the reference id-part equals the id of the
 * Specimen produced by
 * {@link FhirTransformServiceImpl#transformToSpecimen}.</li>
 * <li><b>DiagnosticReport &rarr; Device</b> — transitive, via
 * {@code DiagnosticReport.result &rarr; Observation.device}; the reference
 * id-part equals the id of the Device produced by
 * {@link FhirTransformServiceImpl#transformAnalyzerToDevice}.</li>
 * </ul>
 *
 * <p>
 * Like the S4.1 gate ({@code FhirResultDiagnosticReportValidationTest}) this is
 * a pure JUnit unit test — no Spring context, no Testcontainers/DB. It drives
 * the production transform methods with mocked collaborators and validates the
 * emitted resources against the base R4 StructureDefinitions via
 * {@link FhirInstanceValidator}. Only <code>ERROR</code>/<code>FATAL</code>
 * issues fail the test; terminology <code>WARNING</code>s are tolerated because
 * no terminology server is wired in (SNOMED/UCUM/local codes cannot be resolved
 * offline), which is out of scope for structural conformance.
 */
@RunWith(MockitoJUnitRunner.class)
public class FhirDiagnosticReportSpecimenDeviceLinkageValidationTest {

    private static final String SPECIMEN_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String ANALYSIS_UUID = "22222222-2222-2222-2222-222222222222";
    private static final String RESULT_UUID = "33333333-3333-3333-3333-333333333333";
    private static final String DEVICE_UUID = "44444444-4444-4444-4444-444444444444";

    /**
     * Base-spec R4 instance validator, shared across the suite (construction is
     * expensive).
     */
    private static FhirValidator fhirValidator;

    @BeforeClass
    public static void initValidator() {
        FhirContext fhirContext = FhirContext.forR4();
        ValidationSupportChain support = new ValidationSupportChain(new DefaultProfileValidationSupport(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext));
        fhirValidator = fhirContext.newValidator();
        fhirValidator.registerValidatorModule(new FhirInstanceValidator(support));
    }

    @Mock
    private ResultService resultService;

    @Mock
    private SampleHumanService sampleHumanService;

    @Mock
    private IStatusService statusService;

    @Mock
    private FhirConfig fhirConfig;

    @Mock
    private TestService testService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private FhirFacilityOrganizationService facilityOrganizationService;

    @InjectMocks
    private FhirTransformServiceImpl fhirTransformService;

    @Test
    public void finalizedResult_specimenAndDeviceValidateAndResolveFromDiagnosticReport() {
        final String finalizedStatusId = "6";

        // --- A finalized, numeric observation: Hemoglobin 12.5 g/dL (LOINC 718-7) ---
        Localization testName = mock(Localization.class);
        when(testName.getEnglish()).thenReturn("Hemoglobin");

        org.openelisglobal.test.valueholder.Test test = mock(org.openelisglobal.test.valueholder.Test.class);
        when(test.getId()).thenReturn("1");
        when(test.getLoinc()).thenReturn("718-7");
        when(test.getName()).thenReturn("Hemoglobin");
        when(test.getLocalizedTestName()).thenReturn(testName);

        TypeOfSample typeOfSample = mock(TypeOfSample.class);
        when(typeOfSample.getLocalAbbreviation()).thenReturn("BLD");

        Sample sample = mock(Sample.class);
        when(sample.getAccessionNumber()).thenReturn("ACC-001");
        when(sample.hasGpsCoordinates()).thenReturn(false);

        SampleItem sampleItem = mock(SampleItem.class);
        when(sampleItem.getFhirUuidAsString()).thenReturn(SPECIMEN_UUID);
        when(sampleItem.getSample()).thenReturn(sample);
        when(sampleItem.getStatusId()).thenReturn("1");
        when(sampleItem.getTypeOfSample()).thenReturn(typeOfSample);
        when(sampleItem.getCollectionDate()).thenReturn(new Timestamp(1_699_900_000_000L));

        Analysis analysis = mock(Analysis.class);
        when(analysis.getTest()).thenReturn(test);
        when(analysis.getSampleItem()).thenReturn(sampleItem);
        when(analysis.getStatusId()).thenReturn(finalizedStatusId);
        when(analysis.getFhirUuidAsString()).thenReturn(ANALYSIS_UUID);
        when(analysis.getReleasedDate()).thenReturn(new Timestamp(1_700_000_000_000L));

        Result result = mock(Result.class);
        when(result.getAnalysis()).thenReturn(analysis);
        when(result.getFhirUuidAsString()).thenReturn(RESULT_UUID);
        when(result.getValue()).thenReturn("12.5");
        when(result.getValue(true)).thenReturn("12.5");
        when(result.getResultType()).thenReturn("N");

        // The analyzer that produced the result -> its FHIR Device.
        Analyzer analyzer = mock(Analyzer.class);
        when(analyzer.ensureFhirUuid()).thenReturn(DEVICE_UUID);
        when(analyzer.getName()).thenReturn("Sysmex XN-1000");
        when(analyzer.getType()).thenReturn("Hematology Analyzer");
        when(analyzer.getMachineId()).thenReturn("AN-123");

        // Collaborators the transform paths touch.
        when(statusService.getStatusID(AnalysisStatus.Finalized)).thenReturn(finalizedStatusId);
        when(statusService.getSampleStatusForID("1")).thenReturn(null); // -> Specimen.status AVAILABLE
        when(fhirConfig.getOeFhirSystem()).thenReturn("http://openelis-global.org");
        when(testService.get("1")).thenReturn(test);
        when(resultService.getResultsByAnalysis(analysis)).thenReturn(List.of(result));
        when(resultService.getUOM(result)).thenReturn("g/dL");
        when(analysisService.getAnalysesBySampleItem(sampleItem)).thenReturn(List.of());
        when(sampleHumanService.getPatientForSample(sample)).thenReturn(null);
        // facilityOrganizationService.getFacilityId() defaults to null -> no facility
        // identifier.

        // --- Production transforms under test ---
        DiagnosticReport diagnosticReport = fhirTransformService.transformResultToDiagnosticReport(analysis);
        Specimen specimen = fhirTransformService.transformToSpecimen(sampleItem);
        Device device = fhirTransformService.transformAnalyzerToDevice(analyzer);
        Observation observation = fhirTransformService.transformResultToObservation(result);
        // The bundle wires Observation -> Device via this same seam (see
        // setDeviceReferenceAndInclude); exercise it rather than reconstruct the
        // reference.
        fhirTransformService.linkObservationToDevice(observation, analyzer);

        // A finalized result is FINAL on the report (the slice's premise).
        assertEquals(DiagnosticReportStatus.FINAL, diagnosticReport.getStatus());

        // (1) Both referenced resources are FHIR R4 $validate-clean.
        assertNoFhirErrors("Specimen", fhirValidator.validateWithResult(specimen));
        assertNoFhirErrors("Device", fhirValidator.validateWithResult(device));

        // (2) Specimen resolves directly from DiagnosticReport.specimen.
        List<Reference> reportSpecimens = diagnosticReport.getSpecimen();
        assertEquals("DiagnosticReport should reference exactly one Specimen", 1, reportSpecimens.size());
        assertEquals("DiagnosticReport.specimen must resolve to the built Specimen", specimenIdPart(specimen),
                reportSpecimens.get(0).getReferenceElement().getIdPart());
        assertEquals(SPECIMEN_UUID, specimenIdPart(specimen));

        // (3) The result Observation resolves from DiagnosticReport.result, ...
        assertTrue("DiagnosticReport.result must resolve to the built Observation",
                diagnosticReport.getResult().stream().map(r -> r.getReferenceElement().getIdPart())
                        .anyMatch(idPart -> idPart.equals(observation.getIdElement().getIdPart())));
        assertEquals(RESULT_UUID, observation.getIdElement().getIdPart());

        // ... and that Observation links to the Device that resolves from it.
        Reference observationDevice = observation.getDevice();
        assertNotNull("Observation must reference its producing Device", observationDevice.getReference());
        assertEquals("Observation.device must resolve to the built Device", device.getIdElement().getIdPart(),
                observationDevice.getReferenceElement().getIdPart());
        assertEquals(DEVICE_UUID, device.getIdElement().getIdPart());

        // (4) Coherence: report and observation agree on the same Specimen.
        assertEquals("Observation.specimen must match DiagnosticReport.specimen", SPECIMEN_UUID,
                observation.getSpecimen().getReferenceElement().getIdPart());
    }

    private static String specimenIdPart(Specimen specimen) {
        return specimen.getIdElement().getIdPart();
    }

    private static void assertNoFhirErrors(String label, ValidationResult validationResult) {
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
                .filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
                        || message.getSeverity() == ResultSeverityEnum.FATAL)
                .collect(Collectors.toList());
        assertTrue(label + " failed FHIR $validate with ERROR/FATAL issues: " + errors, errors.isEmpty());
    }
}
