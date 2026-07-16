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
}
