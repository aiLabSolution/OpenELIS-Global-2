package org.openelisglobal.analyzerimport.action;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.Analyzer.AnalyzerStatus;
import org.openelisglobal.analyzerresults.service.AnalyzerResultsService;
import org.openelisglobal.analyzerresults.valueholder.AnalyzerResults;
import org.openelisglobal.test.service.TestService;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

public class AnalyzerFhirImportControllerTest extends BaseWebContextSensitiveTest {

    @Mock
    private AnalyzerResultsService analyzerResultsService;

    @Mock
    private AnalyzerService analyzerService;

    @Mock
    private TestService testService;

    private AnalyzerFhirImportController controller;
    private Object originalAnalyzerResultsService;
    private Object originalAnalyzerService;
    private Object originalTestService;
    private Object originalFhirContext;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        SecurityContextHolder.clearContext();
        MockitoAnnotations.initMocks(this);
        controller = webApplicationContext.getBean(AnalyzerFhirImportController.class);
        originalAnalyzerResultsService = ReflectionTestUtils.getField(controller, "analyzerResultsService");
        originalAnalyzerService = ReflectionTestUtils.getField(controller, "analyzerService");
        originalTestService = ReflectionTestUtils.getField(controller, "testService");
        originalFhirContext = ReflectionTestUtils.getField(controller, "fhirContext");
        ReflectionTestUtils.setField(controller, "analyzerResultsService", analyzerResultsService);
        ReflectionTestUtils.setField(controller, "analyzerService", analyzerService);
        ReflectionTestUtils.setField(controller, "testService", testService);
        ReflectionTestUtils.setField(controller, "fhirContext", FhirContext.forR4());
    }

    @After
    public void tearDown() {
        ReflectionTestUtils.setField(controller, "analyzerResultsService", originalAnalyzerResultsService);
        ReflectionTestUtils.setField(controller, "analyzerService", originalAnalyzerService);
        ReflectionTestUtils.setField(controller, "testService", originalTestService);
        ReflectionTestUtils.setField(controller, "fhirContext", originalFhirContext);
    }

    @Test
    public void importFhirBundle_NoMappableObservations_ReturnsBadRequest() throws Exception {
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Specimen\",\n"
                + "        \"identifier\": [{\"value\": \"CNEG\"}]\n" + "      }\n" + "    },\n" + "    {\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"CONTROL\"}]}\n" + "      }\n" + "    }\n" + "  ]\n"
                + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorKey").value("analyzer.fhirImport.error.noObservations"));

        verify(analyzerResultsService, never()).insertAnalyzerResults(anyList(), eq("1"));
    }

    @Test
    public void importFhirBundle_CalibrationDiagnosticReport_ReturnsBadRequestAndDoesNotInsert() throws Exception {
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"SD1-CAL-001\"}]" + "}},"
                + "{\"resource\":{\"resourceType\":\"DiagnosticReport\","
                + "\"meta\":{\"tag\":[{\"system\":\"http://openelis-global.org/fhir/tags\",\"code\":\"CALIBRATION\"}]},"
                + "\"status\":\"preliminary\",\"code\":{\"text\":\"Analyzer Results\"},"
                + "\"specimen\":[{\"reference\":\"urn:uuid:specimen-1\"}]" + "}}" + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorKey").value("analyzer.fhirImport.error.calibrationRejected"));

        verify(analyzerResultsService, never()).insertAnalyzerResults(anyList(), eq("1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_ValidObservation_InsertsAnalyzerResults() throws Exception {
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Specimen\",\n"
                + "        \"identifier\": [{\"value\": \"HARN-FC-2026-00003\"}]\n" + "      }\n" + "    },\n"
                + "    {\n" + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"WBC\"}]},\n" + "        \"valueString\": \"Detected\"\n"
                + "      }\n" + "    }\n" + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults rawOnly = (AnalyzerResults) captor.getValue().get(0);
        org.junit.Assert.assertEquals("WBC", rawOnly.getRawCode());
        org.junit.Assert.assertNull(rawOnly.getLoinc());
        org.junit.Assert.assertNull(rawOnly.getRawUnit());
        org.junit.Assert.assertNull(rawOnly.getUcumValue());
        org.junit.Assert.assertEquals("UNMAPPED", rawOnly.getNormalizationStatus());
        org.junit.Assert.assertNull("no Patient in bundle — hint must stay null", rawOnly.getPatientHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_PatientResource_MapsWirePatientIdentityToPatientHint() throws Exception {
        // LIS-239: the bridge emits a Patient resource carrying the wire patient
        // identity (identifier under the analyzer-patient-id system) and points
        // each Observation.subject at it. The import must carry that identity
        // into analyzer_results.patient_hint; an Observation without a subject
        // stages with a null hint (no signal).
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:patient-1\",\"resource\":{" + "\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"http://openelis-global.org/fhir/analyzer-patient-id\","
                + "\"value\":\"PID2-0007\"}]}}," + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"HL7-UNKNOWN\"}],"
                + "\"subject\":{\"reference\":\"urn:uuid:patient-1\"}}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"subject\":{\"reference\":\"urn:uuid:patient-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"HGB\"}]},\"valueString\":\"13.1\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(2));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(2, inserted.size());
        org.junit.Assert.assertEquals("subject-linked observation must carry the wire patient identity", "PID2-0007",
                inserted.get(0).getPatientHint());
        org.junit.Assert.assertNull("subject-less observation must stage with a null hint",
                inserted.get(1).getPatientHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_PatientWithForeignIdentifierSystem_FallsBackToFirstIdentifier() throws Exception {
        // A Patient without the analyzer-patient-id system still identifies the
        // patient — fall back to its first valued identifier rather than dropping
        // the signal.
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:patient-9\",\"resource\":{" + "\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"urn:some:other:system\",\"value\":\"MRN-42\"}]}},"
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-9\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"subject\":{\"reference\":\"urn:uuid:patient-9\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        org.junit.Assert.assertEquals("MRN-42", ((AnalyzerResults) captor.getValue().get(0)).getPatientHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_DualCodedQuantity_PreservesProvenanceIndependentOfCodingOrder() throws Exception {
        org.openelisglobal.test.valueholder.Test whiteBloodCell = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(whiteBloodCell.getId()).thenReturn("55");
        when(testService.getTestsByLoincCode("6690-2")).thenReturn(List.of(whiteBloodCell));

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"LIS-133\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"}," + "\"code\":{\"coding\":["
                + "{\"system\":\"http://loinc.org\",\"code\":\"6690-2\"},{\"code\":\"WBC\"}]},"
                + "\"valueQuantity\":{\"value\":7.5,\"unit\":\"10^9/L\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"10*9/L\"}}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"}," + "\"code\":{\"coding\":["
                + "{\"code\":\"WBC\"},{\"system\":\"http://loinc.org\",\"code\":\"6690-2\"}]},"
                + "\"valueQuantity\":{\"value\":8.0,\"unit\":\"10^9/L\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"10*9/L\"}}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(2));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(2, inserted.size());
        for (AnalyzerResults staged : inserted) {
            org.junit.Assert.assertEquals("55", staged.getTestId());
            org.junit.Assert.assertEquals("WBC", staged.getRawCode());
            org.junit.Assert.assertEquals("10^9/L", staged.getRawUnit());
            org.junit.Assert.assertEquals("6690-2", staged.getLoinc());
            org.junit.Assert.assertEquals("10*9/L", staged.getUcumValue());
            org.junit.Assert.assertEquals("NORMALIZED", staged.getNormalizationStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_QualifiedQuantityComparator_StagesHumanReadableNumericResult() throws Exception {
        // LIS-252: off-scale qualified results ride as Quantity.comparator + magnitude.
        // OE must reconstruct the human-readable qualified value (<0.008, >1000,
        // <=0.01, >=500) and stage it as a numeric result — never drop the comparator.
        org.openelisglobal.test.valueholder.Test tsh = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(tsh.getId()).thenReturn("77");
        when(testService.getTestsByLoincCode("3016-3")).thenReturn(List.of(tsh));

        String obs = "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"3016-3\"}]},"
                + "\"valueQuantity\":{\"comparator\":\"%s\",\"value\":%s,\"unit\":\"uIU/mL\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"u[IU]/mL\"}}}";
        String bundleJson = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"LIS-252-Q\"}]}},"
                + String.format(obs, "<", "0.008") + "," + String.format(obs, ">", "1000") + ","
                + String.format(obs, "<=", "0.01") + "," + String.format(obs, ">=", "500") + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(4));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(List.of("<0.008", ">1000", "<=0.01", ">=500"),
                inserted.stream().map(AnalyzerResults::getResult).toList());
        for (AnalyzerResults staged : inserted) {
            org.junit.Assert.assertEquals("N", staged.getResultType());
            org.junit.Assert.assertEquals("77", staged.getTestId());
            org.junit.Assert.assertEquals("uIU/mL", staged.getUnits());
            org.junit.Assert.assertEquals("u[IU]/mL", staged.getUcumValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_PlainQuantity_StagesMagnitudeWithoutComparator() throws Exception {
        // Backward compatibility: an ordinary numeric Quantity must stage as the bare
        // magnitude with no comparator prefix (LIS-252).
        org.openelisglobal.test.valueholder.Test tsh = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(tsh.getId()).thenReturn("77");
        when(testService.getTestsByLoincCode("3016-3")).thenReturn(List.of(tsh));

        String bundleJson = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"LIS-252-PLAIN\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"3016-3\"}]},"
                + "\"valueQuantity\":{\"value\":2.31,\"unit\":\"uIU/mL\"}}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);
        org.junit.Assert.assertEquals("2.31", staged.getResult());
        org.junit.Assert.assertEquals("N", staged.getResultType());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_DualCodedTextResult_IsPartialWithoutUcum() throws Exception {
        org.openelisglobal.test.valueholder.Test whiteBloodCell = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(whiteBloodCell.getId()).thenReturn("55");
        when(testService.getTestsByLoincCode("6690-2")).thenReturn(List.of(whiteBloodCell));

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"LIS-133-TEXT\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"}," + "\"code\":{\"coding\":[{\"code\":\"WBC\"},"
                + "{\"system\":\"http://loinc.org\",\"code\":\"6690-2\"}]}," + "\"valueString\":\"Detected\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);
        org.junit.Assert.assertEquals("WBC", staged.getRawCode());
        org.junit.Assert.assertEquals("6690-2", staged.getLoinc());
        org.junit.Assert.assertNull(staged.getRawUnit());
        org.junit.Assert.assertNull(staged.getUcumValue());
        org.junit.Assert.assertEquals("PARTIAL", staged.getNormalizationStatus());
    }

    /**
     * LIS-122/LIS-123: the bridge now emits one Specimen per wire specimen
     * (multi-OBR / multi-O-record transmissions) with each Observation referencing
     * its own Specimen, plus a Patient resource for identity. Each Observation must
     * stage under its own accession — never collapsed onto a shared one — and the
     * Patient resource must pass through harmlessly.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_MultiSpecimenBundle_StagesEachObservationUnderItsOwnAccession() throws Exception {
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:patient-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Patient\",\n"
                + "        \"identifier\": [{\"value\": \"778\"}]\n" + "      }\n" + "    },\n" + "    {\n"
                + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n" + "      \"resource\": {\n"
                + "        \"resourceType\": \"Specimen\",\n" + "        \"identifier\": [{\"value\": \"SPEC-A\"}],\n"
                + "        \"subject\": {\"reference\": \"urn:uuid:patient-1\"}\n" + "      }\n" + "    },\n"
                + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-2\",\n" + "      \"resource\": {\n"
                + "        \"resourceType\": \"Specimen\",\n" + "        \"identifier\": [{\"value\": \"SPEC-B\"}]\n"
                + "      }\n" + "    },\n" + "    {\n" + "      \"resource\": {\n"
                + "        \"resourceType\": \"Observation\",\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"subject\": {\"reference\": \"urn:uuid:patient-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"GLU\"}]},\n" + "        \"valueString\": \"98\"\n"
                + "      }\n" + "    },\n" + "    {\n" + "      \"resource\": {\n"
                + "        \"resourceType\": \"Observation\",\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-2\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"NA\"}]},\n" + "        \"valueString\": \"140\"\n"
                + "      }\n" + "    }\n" + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(2));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(2, inserted.size());
        org.junit.Assert.assertEquals("SPEC-A", inserted.get(0).getAccessionNumber());
        org.junit.Assert.assertEquals("GLU", inserted.get(0).getTestName());
        org.junit.Assert.assertEquals("SPEC-B", inserted.get(1).getAccessionNumber());
        org.junit.Assert.assertEquals("NA", inserted.get(1).getTestName());
    }

    /**
     * The Observation.subject.identifier fallback reads an INLINE identifier as the
     * accession. A subject that is only a reference to a Patient resource (what the
     * bridge emits) must never be misread as an accession: an Observation with no
     * Specimen reference and a reference-only subject is skipped, not staged under
     * the reference string or the patient's MRN.
     */
    @Test
    public void importFhirBundle_SubjectReferenceOnlyWithoutSpecimen_IsSkippedNotStagedUnderMrn() throws Exception {
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:patient-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Patient\",\n"
                + "        \"identifier\": [{\"value\": \"778\"}]\n" + "      }\n" + "    },\n" + "    {\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"subject\": {\"reference\": \"urn:uuid:patient-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"GLU\"}]},\n" + "        \"valueString\": \"98\"\n"
                + "      }\n" + "    }\n" + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));

        verify(analyzerResultsService, never()).insertAnalyzerResults(anyList(), eq("1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_QcTaggedObservation_PersistsControlResult() throws Exception {
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Specimen\",\n"
                + "        \"identifier\": [{\"value\": \"CNEG\"}]\n" + "      }\n" + "    },\n" + "    {\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"meta\": {\"tag\": [{\"system\": \"http://openelis-global.org/fhir/tags\", \"code\": \"QC\", \"display\": \"Quality Control\"}]},\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"VIH-1\"}]},\n"
                + "        \"valueString\": \"Positive\"\n" + "      }\n" + "    }\n" + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(1, inserted.size());
        org.junit.Assert.assertTrue("QC tag should map to isControl=true", inserted.get(0).getIsControl());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_MappedQcResult_IsReadOnlyToBlockPatientAccept() throws Exception {
        // A QC (MSH-16=2) result that DOES resolve to a test (mapped case) must be
        // marked read-only at ingest so the accept path
        // (AnalyzerResultsAcceptServiceImpl
        // skips read-only items) can never carry a control into a patient
        // Result/Analysis. Without the guard a mapped control row has readOnly=false
        // and
        // is acceptable as a patient result: the bundle-level calibration reject only
        // covers MSH-16=1, and per-item readOnly does not incorporate isControl.
        org.openelisglobal.test.valueholder.Test glucose = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(glucose.getId()).thenReturn("55");
        when(testService.getTestsByLoincCode("2345-7")).thenReturn(List.of(glucose));

        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Specimen\",\n"
                + "        \"identifier\": [{\"value\": \"CNEG\"}]\n" + "      }\n" + "    },\n" + "    {\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"meta\": {\"tag\": [{\"system\": \"http://openelis-global.org/fhir/tags\", \"code\": \"QC\"}]},\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"2345-7\"}]},\n"
                + "        \"valueQuantity\": {\"value\": 95, \"unit\": \"mg/dL\"}\n" + "      }\n" + "    }\n"
                + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(1, inserted.size());
        AnalyzerResults qc = inserted.get(0);
        org.junit.Assert.assertEquals("QC LOINC 2345-7 must resolve to the mapped test", "55", qc.getTestId());
        org.junit.Assert.assertTrue("QC tag should map to isControl=true", qc.getIsControl());
        org.junit.Assert.assertTrue("mapped QC result must be read-only so it cannot be accepted as a patient result",
                qc.isReadOnly());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_MissingAnalyzerRowForNumericHeader_StillStagesResult() throws Exception {
        when(analyzerService.get("999")).thenThrow(new org.hibernate.ObjectNotFoundException("999",
                org.openelisglobal.analyzer.valueholder.Analyzer.class.getName()));
        String bundleJson = "{\n" + "  \"resourceType\": \"Bundle\",\n" + "  \"type\": \"transaction\",\n"
                + "  \"entry\": [\n" + "    {\n" + "      \"fullUrl\": \"urn:uuid:specimen-1\",\n"
                + "      \"resource\": {\n" + "        \"resourceType\": \"Specimen\",\n"
                + "        \"identifier\": [{\"value\": \"DEV01269990000000001\"}]\n" + "      }\n" + "    },\n"
                + "    {\n" + "      \"resource\": {\n" + "        \"resourceType\": \"Observation\",\n"
                + "        \"specimen\": {\"reference\": \"urn:uuid:specimen-1\"},\n"
                + "        \"code\": {\"coding\": [{\"code\": \"WBC\"}]},\n" + "        \"valueString\": \"42\"\n"
                + "      }\n" + "    }\n" + "  ]\n" + "}";

        mockMvc.perform(post("/analyzer/fhir").header("X-Analyzer-Id", "999").contentType(MediaType.APPLICATION_JSON)
                .content(bundleJson)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(1, inserted.size());
        org.junit.Assert.assertNull(inserted.get(0).getAnalyzerId());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_UnknownSourceWithDevice_CreatesStubAndStagesResult() throws Exception {
        // Transparent-pipe contract: first message from a new analyzer (no existing
        // match by id/name/serial) must land in staging via a find-or-create
        // PENDING_REGISTRATION stub keyed on Device.identifier[source-ip].
        when(analyzerService.findByDiscoveredSourceId("10.0.0.42")).thenReturn(Optional.empty());
        when(analyzerService.insert(any(Analyzer.class))).thenReturn("777");
        Analyzer createdStub = new Analyzer();
        createdStub.setId("777");
        createdStub.setName("GeneXpert");
        createdStub.setStatus(AnalyzerStatus.PENDING_REGISTRATION);
        createdStub.setDiscoveredSourceId("10.0.0.42");
        when(analyzerService.get("777")).thenReturn(createdStub);

        String bundleJson = "{"
                + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:device-1\",\"resource\":{"
                + "\"resourceType\":\"Device\","
                + "\"identifier\":[{\"system\":\"https://openelis-global.org/fhir/source-ip\",\"value\":\"10.0.0.42\"}],"
                + "\"deviceName\":[{\"name\":\"GeneXpert\",\"type\":\"manufacturer-name\"}]"
                + "}},"
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"XPERT-001\"}]"
                + "}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"EV\"}]},"
                + "\"valueString\":\"DETECTED\"}}"
                + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1))
                .andExpect(jsonPath("$.analyzerId").value("777"));

        ArgumentCaptor<Analyzer> stubCaptor = ArgumentCaptor.forClass(Analyzer.class);
        verify(analyzerService).insert(stubCaptor.capture());
        Analyzer submittedStub = stubCaptor.getValue();
        org.junit.Assert.assertEquals(AnalyzerStatus.PENDING_REGISTRATION, submittedStub.getStatus());
        org.junit.Assert.assertEquals("10.0.0.42", submittedStub.getDiscoveredSourceId());
        org.junit.Assert.assertEquals("GeneXpert", submittedStub.getName());

        ArgumentCaptor<List> resultsCaptor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(resultsCaptor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) resultsCaptor.getValue();
        org.junit.Assert.assertEquals(1, inserted.size());
        org.junit.Assert.assertEquals("777", inserted.get(0).getAnalyzerId());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_QCObservationWithLotNumberAndControlLevelExtensions_setsFieldsOnAR() throws Exception {
        // FHIR extensions on a QC observation must propagate to the staged
        // AnalyzerResults so QCResultProcessingService can use them for the
        // Tier 1 (lotNumber) and Tier 2 (controlLevel) lot resolution.
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"QC-RUN-001\"}]" + "}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"meta\":{\"tag\":[{\"system\":\"http://openelis-global.org/fhir/tags\",\"code\":\"QC\",\"display\":\"Quality Control\"}]},"
                + "\"extension\":["
                + "{\"url\":\"http://openelis-global.org/fhir/qc/lot-number\",\"valueString\":\"LOT-LPC-001\"},"
                + "{\"url\":\"http://openelis-global.org/fhir/qc/control-level\",\"valueString\":\"LPC\"}" + "],"
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"GLU\"}]}," + "\"valueString\":\"105\"" + "}}" + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertEquals(1, inserted.size());
        AnalyzerResults ar = inserted.get(0);
        org.junit.Assert.assertTrue("QC tag should map to isControl=true", ar.getIsControl());
        org.junit.Assert.assertEquals("lot-number extension must populate AR.lotNumber", "LOT-LPC-001",
                ar.getLotNumber());
        org.junit.Assert.assertEquals("control-level extension must populate AR.controlLevel", "LPC",
                ar.getControlLevel());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_QCObservationWithoutExtensions_leavesLotAndLevelNull() throws Exception {
        // Backwards-compat: a QC observation without the new extensions must
        // not poison AR fields with stale state — both must read null on the
        // staged record so QCResultProcessingService falls through to Tier 3.
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"QC-RUN-002\"}]" + "}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"meta\":{\"tag\":[{\"system\":\"http://openelis-global.org/fhir/tags\",\"code\":\"QC\"}]},"
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"GLU\"}]}," + "\"valueString\":\"100\"" + "}}" + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        List<AnalyzerResults> inserted = (List<AnalyzerResults>) captor.getValue();
        org.junit.Assert.assertNull("Absent lot-number extension must leave AR.lotNumber null",
                inserted.get(0).getLotNumber());
        org.junit.Assert.assertNull("Absent control-level extension must leave AR.controlLevel null",
                inserted.get(0).getControlLevel());
    }

    @Test
    public void importFhirBundle_ReusesExistingStubForKnownSourceId() throws Exception {
        // Idempotency: subsequent bundles from the same source hit the fast path
        // via findByDiscoveredSourceId and do NOT create a duplicate stub.
        Analyzer existingStub = new Analyzer();
        existingStub.setId("555");
        existingStub.setName("GeneXpert");
        existingStub.setStatus(AnalyzerStatus.PENDING_REGISTRATION);
        existingStub.setDiscoveredSourceId("10.0.0.42");
        when(analyzerService.findByDiscoveredSourceId("10.0.0.42")).thenReturn(Optional.of(existingStub));

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:device-1\",\"resource\":{" + "\"resourceType\":\"Device\","
                + "\"identifier\":[{\"system\":\"https://openelis-global.org/fhir/source-ip\",\"value\":\"10.0.0.42\"}],"
                + "\"deviceName\":[{\"name\":\"GeneXpert\",\"type\":\"manufacturer-name\"}]" + "}},"
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"XPERT-002\"}]" + "}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"}," + "\"code\":{\"coding\":[{\"code\":\"EV\"}]},"
                + "\"valueString\":\"DETECTED\"}}" + "]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.analyzerId").value("555"));

        verify(analyzerService, never()).insert(any(Analyzer.class));
    }

    @Test
    public void importFhirBundle_OverlongAccession_RejectsBundleNamingField() throws Exception {
        // LIS-244: accession_number is identity-bearing — truncating it would
        // change sample-matching semantics (wrong-sample attach risk), so an
        // over-length value rejects the whole bundle with 400. The bridge treats
        // 4xx as non-retryable and dead-letters the bundle instead of re-POSTing
        // it forever (the retry-poison this slice closes).
        String overlong = "A".repeat(AnalyzerResults.ACCESSION_NUMBER_MAX_LENGTH + 1);
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"" + overlong + "\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorKey").value("analyzer.fhirImport.error.fieldTooLong"))
                .andExpect(jsonPath("$.errorArgs.field").value("accession_number"))
                .andExpect(jsonPath("$.errorArgs.maxLength")
                        .value(String.valueOf(AnalyzerResults.ACCESSION_NUMBER_MAX_LENGTH)));

        verify(analyzerResultsService, never()).insertAnalyzerResults(anyList(), eq("1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_MaxLengthAccession_IsAccepted() throws Exception {
        // Boundary: exactly 25 chars must pass — liquibase 031 widened the DB
        // column to VARCHAR(25) for 10-char SITEYEARNUM prefixes, so enforcing
        // the stale 20 would reject legitimate accessions.
        String maxLength = "B".repeat(AnalyzerResults.ACCESSION_NUMBER_MAX_LENGTH);
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"" + maxLength + "\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        org.junit.Assert.assertEquals(maxLength, ((AnalyzerResults) captor.getValue().get(0)).getAccessionNumber());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_OverlongPatientHint_TruncatedToColumnWidth() throws Exception {
        // LIS-244: patient_hint is a comparison-only signal — both the original
        // and any re-export pass through this same boundary, so identical
        // truncation preserves the conflict check while curing the insert
        // failure (bundle retry poison).
        String overlongHint = "H".repeat(AnalyzerResults.PATIENT_HINT_MAX_LENGTH + 20);
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:patient-1\",\"resource\":{" + "\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"http://openelis-global.org/fhir/analyzer-patient-id\","
                + "\"value\":\"" + overlongHint + "\"}]}}," + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-244\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"subject\":{\"reference\":\"urn:uuid:patient-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        org.junit.Assert.assertEquals(overlongHint.substring(0, AnalyzerResults.PATIENT_HINT_MAX_LENGTH),
                ((AnalyzerResults) captor.getValue().get(0)).getPatientHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_WhitespacePaddedPatientHint_StoredTrimmed() throws Exception {
        // LIS-244 trim hygiene: whitespace variance between an original and a
        // re-export must not create a stored difference — trim at the boundary
        // so the persisted hint is canonical.
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:patient-1\",\"resource\":{" + "\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"http://openelis-global.org/fhir/analyzer-patient-id\","
                + "\"value\":\"  PID2-0007  \"}]}}," + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-245\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"subject\":{\"reference\":\"urn:uuid:patient-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]},\"valueString\":\"7.5\"}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        org.junit.Assert.assertEquals("PID2-0007", ((AnalyzerResults) captor.getValue().get(0)).getPatientHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_OverlongSignalFields_TruncatedAndStillStage() throws Exception {
        // LIS-244: raw_code / loinc / raw_unit / ucum_value are provenance and
        // mapping signals, not identity — truncate to column width so the row
        // still stages (read-only when unmapped) instead of poisoning the whole
        // bundle. The derived import_issue_reason ("unmapped_loinc:" + code)
        // must also fit its VARCHAR(200).
        String overlongCode = "C".repeat(300);
        String overlongLoinc = "1".repeat(AnalyzerResults.LOINC_MAX_LENGTH + 10);
        String overlongUnit = "u".repeat(AnalyzerResults.RAW_UNIT_MAX_LENGTH + 10);
        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-246\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"}," + "\"code\":{\"coding\":["
                + "{\"system\":\"http://loinc.org\",\"code\":\"" + overlongLoinc + "\"}," + "{\"code\":\""
                + overlongCode + "\"}]}," + "\"valueQuantity\":{\"value\":7.5,\"unit\":\"" + overlongUnit + "\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"" + overlongUnit + "\"}}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);
        org.junit.Assert.assertEquals(AnalyzerResults.RAW_CODE_MAX_LENGTH, staged.getRawCode().length());
        org.junit.Assert.assertEquals(AnalyzerResults.LOINC_MAX_LENGTH, staged.getLoinc().length());
        org.junit.Assert.assertEquals(AnalyzerResults.RAW_UNIT_MAX_LENGTH, staged.getRawUnit().length());
        org.junit.Assert.assertEquals(AnalyzerResults.UCUM_VALUE_MAX_LENGTH, staged.getUcumValue().length());
        org.junit.Assert.assertTrue("unmapped row must stage read-only", staged.isReadOnly());
        org.junit.Assert.assertTrue("derived import_issue_reason must fit its column",
                staged.getImportIssueReason().length() <= AnalyzerResults.IMPORT_ISSUE_REASON_MAX_LENGTH);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_SkewedAnalyzerClock_FlagsClockSkewWithoutBlocking() throws Exception {
        // LIS-271: the real MAGLUMI X3 bench clock was found ~16 months wrong. A
        // syntactically valid but implausible effectiveDateTime never trips the
        // existing parse-exception fallback — a wrong-but-parseable timestamp
        // sails right through — so it has to be flagged via import_issue_reason
        // instead. Policy (Pinote, QA-approved) is non-blocking: the row still
        // stages and inserts, and completeDate keeps trusting the analyzer's
        // onboard clock verbatim (unchanged accept/hold behavior — LIS-128's
        // cross-day guard still keys off it).
        java.time.Instant analyzerClock = java.time.Instant.now().minus(400, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String effectiveDateTime = java.time.format.DateTimeFormatter.ISO_INSTANT.format(analyzerClock);

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-271-SKEW\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"code\":\"WBC\"}]}," + "\"effectiveDateTime\":\"" + effectiveDateTime + "\","
                + "\"valueString\":\"7.5\"}}]}";

        long beforeImport = System.currentTimeMillis();
        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));
        long afterImport = System.currentTimeMillis();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);

        org.junit.Assert.assertNotNull("skewed clock must be flagged", staged.getImportIssueReason());
        org.junit.Assert.assertTrue("import_issue_reason must record the clock-skew flag",
                staged.getImportIssueReason().contains("clock-skew"));
        org.junit.Assert.assertTrue(
                "import_issue_reason must also retain the coexisting unmapped-LOINC reason — "
                        + "appendImportIssueReason must comma-join, not clobber (LIS-271)",
                staged.getImportIssueReason().contains("unmapped_loinc:WBC"));
        org.junit.Assert.assertEquals("completeDate must still reflect the analyzer-reported time verbatim",
                analyzerClock.getEpochSecond(), staged.getCompleteDate().toInstant().getEpochSecond());
        org.junit.Assert.assertNotNull("importReceivedTime must be recorded for provenance",
                staged.getImportReceivedTime());
        org.junit.Assert.assertTrue("importReceivedTime must reflect OE's own receive clock, not the analyzer's clock",
                staged.getImportReceivedTime().getTime() >= beforeImport
                        && staged.getImportReceivedTime().getTime() <= afterImport);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_LoincMappedResultWithSkewedClock_StagesAcceptableAndFlagsClockSkew() throws Exception {
        // LIS-271 P1 fix: the policy is flag-never-block, and the regression it
        // forbids is a skewed-but-LOINC-mapped result becoming unacceptable. Unlike
        // importFhirBundle_SkewedAnalyzerClock_FlagsClockSkewWithoutBlocking (which
        // uses an UNMAPPED code and so is read-only for an unrelated reason), this
        // uses a LOINC-mapped code so readOnly is driven ONLY by the clock-skew path
        // — proving clock skew alone never flips readOnly or drops testId.
        org.openelisglobal.test.valueholder.Test whiteBloodCell = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(whiteBloodCell.getId()).thenReturn("55");
        when(testService.getTestsByLoincCode("6690-2")).thenReturn(List.of(whiteBloodCell));

        java.time.Instant analyzerClock = java.time.Instant.now().minus(400, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String effectiveDateTime = java.time.format.DateTimeFormatter.ISO_INSTANT.format(analyzerClock);

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-271-MAPPED-SKEW\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"6690-2\"}]},"
                + "\"effectiveDateTime\":\"" + effectiveDateTime + "\","
                + "\"valueQuantity\":{\"value\":7.5,\"unit\":\"10^9/L\"}}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);

        org.junit.Assert.assertFalse(
                "a LOINC-mapped result must stay acceptable (readOnly=false) despite a skewed analyzer clock — "
                        + "clock-skew flags, it never blocks/holds (LIS-271 policy)",
                staged.isReadOnly());
        org.junit.Assert.assertNotNull("LOINC resolution must still set testId when the clock is skewed",
                staged.getTestId());
        org.junit.Assert.assertEquals("55", staged.getTestId());
        org.junit.Assert.assertNotNull("skewed clock must still be flagged even though the result is mapped",
                staged.getImportIssueReason());
        org.junit.Assert.assertTrue("import_issue_reason must record the clock-skew flag",
                staged.getImportIssueReason().contains("clock-skew"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importFhirBundle_InRangeAnalyzerClock_DoesNotFlagClockSkew() throws Exception {
        org.openelisglobal.test.valueholder.Test whiteBloodCell = org.mockito.Mockito
                .mock(org.openelisglobal.test.valueholder.Test.class);
        when(whiteBloodCell.getId()).thenReturn("55");
        when(testService.getTestsByLoincCode("6690-2")).thenReturn(List.of(whiteBloodCell));

        java.time.Instant analyzerClock = java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String effectiveDateTime = java.time.format.DateTimeFormatter.ISO_INSTANT.format(analyzerClock);

        String bundleJson = "{" + "\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"urn:uuid:specimen-1\",\"resource\":{"
                + "\"resourceType\":\"Specimen\",\"identifier\":[{\"value\":\"ACC-271-OK\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Observation\","
                + "\"specimen\":{\"reference\":\"urn:uuid:specimen-1\"},"
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"6690-2\"}]},"
                + "\"effectiveDateTime\":\"" + effectiveDateTime + "\","
                + "\"valueQuantity\":{\"value\":7.5,\"unit\":\"10^9/L\"}}}]}";

        mockMvc.perform(post("/analyzer/fhir").contentType(MediaType.APPLICATION_JSON).content(bundleJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultsInserted").value(1));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzerResultsService).insertAnalyzerResults(captor.capture(), eq("1"));
        AnalyzerResults staged = (AnalyzerResults) captor.getValue().get(0);

        org.junit.Assert.assertNull("an in-range analyzer clock must not be flagged as skewed",
                staged.getImportIssueReason());
        org.junit.Assert.assertNotNull("importReceivedTime is always recorded, skewed or not",
                staged.getImportReceivedTime());
        org.junit.Assert.assertEquals("completeDate must still reflect the analyzer-reported time",
                analyzerClock.getEpochSecond(), staged.getCompleteDate().toInstant().getEpochSecond());
    }
}
