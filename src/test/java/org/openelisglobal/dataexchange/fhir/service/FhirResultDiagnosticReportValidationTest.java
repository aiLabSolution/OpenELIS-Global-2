package org.openelisglobal.dataexchange.fhir.service;

import static org.junit.Assert.assertEquals;
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
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.analysis.valueholder.Analysis;
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

/**
 * S4.1 (LIS-41) FHIR conformance gate: a <b>finalized</b> result must transform
 * to a FHIR R4 {@link DiagnosticReport} + {@link Observation} that pass HAPI
 * instance validation (<code>$validate</code>).
 *
 * <p>
 * This is a pure JUnit unit test — no Spring context, no Testcontainers/DB. It
 * drives the production {@link FhirTransformServiceImpl} transform methods with
 * mocked collaborators and validates the emitted resources against the base R4
 * StructureDefinitions via {@link FhirInstanceValidator}. Only
 * <code>ERROR</code>/<code>FATAL</code> issues fail the test; terminology
 * <code>WARNING</code>s are tolerated because no terminology server is wired in
 * (LOINC/UCUM codes cannot be resolved offline), which is out of scope for
 * structural conformance.
 */
@RunWith(MockitoJUnitRunner.class)
public class FhirResultDiagnosticReportValidationTest {

    /**
     * Base-spec R4 instance validator, shared across the suite (construction is
     * expensive).
     */
    private static FhirValidator fhirValidator;

    @BeforeClass
    public static void initValidator() {
        FhirContext fhirContext = FhirContext.forR4();
        // Structure definitions (DefaultProfileValidationSupport, backed by
        // hapi-fhir-validation-resources-r4) + terminology so code-system lookups
        // (LOINC, status
        // value sets) resolve instead of erroring.
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
    private FhirFacilityOrganizationService facilityOrganizationService;

    @InjectMocks
    private FhirTransformServiceImpl fhirTransformService;

    @Test
    public void finalizedNumericResult_transformsToValidateCleanDiagnosticReportAndObservation() {
        final String finalizedStatusId = "6";

        // A finalized, numeric observation: Hemoglobin 12.5 g/dL (LOINC 718-7).
        Localization testName = mock(Localization.class);
        when(testName.getEnglish()).thenReturn("Hemoglobin");

        org.openelisglobal.test.valueholder.Test test = mock(org.openelisglobal.test.valueholder.Test.class);
        when(test.getId()).thenReturn("1");
        when(test.getLoinc()).thenReturn("718-7");
        when(test.getName()).thenReturn("Hemoglobin");
        when(test.getLocalizedTestName()).thenReturn(testName);

        Sample sample = mock(Sample.class);
        SampleItem sampleItem = mock(SampleItem.class);
        when(sampleItem.getSample()).thenReturn(sample);
        when(sampleItem.getFhirUuidAsString()).thenReturn("11111111-1111-1111-1111-111111111111");

        Analysis analysis = mock(Analysis.class);
        when(analysis.getTest()).thenReturn(test);
        when(analysis.getSampleItem()).thenReturn(sampleItem);
        when(analysis.getStatusId()).thenReturn(finalizedStatusId);
        when(analysis.getFhirUuidAsString()).thenReturn("22222222-2222-2222-2222-222222222222");
        when(analysis.getReleasedDate()).thenReturn(new Timestamp(1_700_000_000_000L));

        Result result = mock(Result.class);
        when(result.getAnalysis()).thenReturn(analysis);
        when(result.getFhirUuidAsString()).thenReturn("33333333-3333-3333-3333-333333333333");
        when(result.getValue()).thenReturn("12.5");
        when(result.getValue(true)).thenReturn("12.5");
        when(result.getResultType()).thenReturn("N");

        // Collaborators the transform path touches for a finalized numeric result.
        when(statusService.getStatusID(AnalysisStatus.Finalized)).thenReturn(finalizedStatusId);
        when(fhirConfig.getOeFhirSystem()).thenReturn("http://openelis-global.org");
        when(testService.get("1")).thenReturn(test);
        when(resultService.getResultsByAnalysis(analysis)).thenReturn(List.of(result));
        when(resultService.getUOM(result)).thenReturn("g/dL");
        when(sampleHumanService.getPatientForSample(sample)).thenReturn(null);
        // facilityOrganizationService.getFacilityId() defaults to null -> no facility
        // identifier.

        // Production transform under test.
        Observation observation = fhirTransformService.transformResultToObservation(result);
        DiagnosticReport diagnosticReport = fhirTransformService.transformResultToDiagnosticReport(analysis);

        // A finalized result is FINAL on both resources (the slice's premise).
        assertEquals(ObservationStatus.FINAL, observation.getStatus());
        assertEquals(DiagnosticReportStatus.FINAL, diagnosticReport.getStatus());

        // The acceptance gate: both pass FHIR R4 instance validation ($validate).
        assertNoFhirErrors("Observation", fhirValidator.validateWithResult(observation));
        assertNoFhirErrors("DiagnosticReport", fhirValidator.validateWithResult(diagnosticReport));
    }

    private static void assertNoFhirErrors(String label, ValidationResult validationResult) {
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
                .filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
                        || message.getSeverity() == ResultSeverityEnum.FATAL)
                .collect(Collectors.toList());
        assertTrue(label + " failed FHIR $validate with ERROR/FATAL issues: " + errors, errors.isEmpty());
    }
}
