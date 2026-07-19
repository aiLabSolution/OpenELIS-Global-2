package org.openelisglobal.result.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;

public class AnalyzerResultsControllerTest extends BaseWebContextSensitiveTest {

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/analyzer-results.xml");
    }

    @Test
    public void showRestAnalyzerResults_ShouldReturnResultList_WhenQueriedByAnalyzerId() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList").isArray())
                .andExpect(jsonPath("$.resultList[0].accessionNumber").value("ACC123456"));
    }

    // LIS-158: the correction picker keys off row.duplicateAnalyzerResultId, so the
    // GET JSON must actually carry it (analyzerResultsToAnalyzerResultItem maps
    // it).
    @Test
    public void showRestAnalyzerResults_ShouldExposeDuplicateAnalyzerResultId() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList[0].duplicateAnalyzerResultId").value("1001"));
    }

    // LIS-126: the unmatched-sample confirmation checkbox keys off
    // row.unmatchedSample, so the GET JSON must carry it — computed
    // server-side per accession (no sample exists for ACC123456 in the
    // fixture), never bound from the client POST.
    @Test
    public void showRestAnalyzerResults_ShouldExposeUnmatchedSample() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList[0].unmatchedSample").value(true));
    }

    @Test
    public void showRestAnalyzerResults_ShouldExposeStoredNormalizationProvenance() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList[0].rawCode").value("GLU"))
                .andExpect(jsonPath("$.resultList[0].rawUnit").value("mmol/L"))
                .andExpect(jsonPath("$.resultList[0].loinc").value("2345-7"))
                .andExpect(jsonPath("$.resultList[0].ucumValue").value("mmol/L"))
                .andExpect(jsonPath("$.resultList[0].normalizationStatus").value("NORMALIZED"));
    }

    @Test
    public void showRestAnalyzerResults_ShouldExposeAnalyzerRangeAndFlag() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList[0].referenceRange").value("3.9-6.1 mmol/L"))
                .andExpect(jsonPath("$.resultList[0].abnormalFlag").value("N"));
    }

    // LIS-270: the staging UI raises a "verify accession against patient"
    // warning off row.wirePatientIdentityAbsent, so the GET JSON must carry it —
    // computed server-side from a blank patientHint (the fixture rows carry no
    // patient_hint, mirroring the SNIBE MAGLUMI X3's bare P|1 wire). The
    // safety-critical direction is that the flag FIRES when identity is absent:
    // a false negative would silence the only downstream signal on this wire.
    @Test
    public void showRestAnalyzerResults_ShouldFlagWirePatientIdentityAbsent_WhenNoPatientHint() throws Exception {
        mockMvc.perform(get("/rest/AnalyzerResults").param("id", "2001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultList[0].wirePatientIdentityAbsent").value(true));
    }
}
