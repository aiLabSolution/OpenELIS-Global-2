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
}
