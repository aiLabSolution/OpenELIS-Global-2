package org.openelisglobal.analyzerresults.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.action.beanitems.AnalyzerResultItem;
import org.openelisglobal.patient.util.PatientUtil;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LIS-126: patient-safety coverage for the unmatched-sample accept gate.
 *
 * <p>
 * When a staged group's accession resolves to no human-entered sample (the
 * walk-up analyzer case — the bridge fills the accession slot with the patient
 * MRN or the literal {@code HL7-UNKNOWN}), accept used to silently
 * find-or-create a Sample under the shared
 * {@code PatientUtil.getUnknownPatient()} placeholder and commit live
 * TechnicalAcceptance results onto it, with no signal to the technician that no
 * patient identity was ever verified. These tests pin the fix: accept of such a
 * group is blocked fail-closed unless the technician explicitly confirms
 * committing under the unidentified patient
 * ({@code unmatchedAction=ACCEPT_UNKNOWN}), and the confirmed commit carries an
 * audit note. Control (QC) groups and rejections are never gated.
 * </p>
 */
public class AnalyzerResultsAcceptUnmatchedGateTest extends BaseWebContextSensitiveTest {

    @Autowired
    private AnalyzerResultsAcceptServiceImpl acceptService;

    @Autowired
    private AnalyzerResultsService analyzerResultsService;

    @Autowired
    private SampleService sampleService;

    @Autowired
    private SampleHumanService sampleHumanService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ResultService resultService;

    @Autowired
    private DataSource dataSource;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/analyzer-results-unmatched.xml");
        PatientUtil.invalidateUnknownPatients();
        PatientUtil.getUnknownPatient();
    }

    @After
    public void tearDown() {
        PatientUtil.invalidateUnknownPatients();
    }

    private AnalyzerResultItem buildItem(String id, String result, String accessionNumber, int sampleGroupingNumber,
            boolean accepted, boolean rejected) {
        AnalyzerResultItem item = new AnalyzerResultItem();
        item.setId(id);
        item.setResult(result);
        item.setReadOnly(false);
        item.setTestId("4001");
        item.setAccessionNumber(accessionNumber);
        item.setTestName("Potassium");
        item.setSampleGroupingNumber(sampleGroupingNumber);
        item.setIsAccepted(accepted);
        item.setIsRejected(rejected);
        item.setIsDeleted(false);
        item.setIsControl(false);
        item.setTestResultType("N");
        // the display format the GET flow feeds back (MM/dd/yyyy) — the
        // persist path parses it with DateUtil.getDateFormat()
        item.setCompleteDate("07/01/2025");
        return item;
    }

    @Test
    public void acceptNoSampleGroup_withoutConfirmation_blocksFailClosed() {
        AnalyzerResultItem walkUp = buildItem("3010", "4.1", "WALKUP01", 1, true, false);
        // no unmatchedAction — the tech was never asked, so accept must not proceed

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(walkUp));

        UnmatchedSampleException ex = assertThrows(UnmatchedSampleException.class,
                () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
        assertTrue("message must name the blocked accession", ex.getMessage().contains("WALKUP01"));

        assertNotNull("staging row must remain, not silently deleted",
                analyzerResultsService.readAnalyzerResults("3010"));
        assertNull("no Sample may be minted for the unconfirmed accession",
                sampleService.getSampleByAccessionNumber("WALKUP01"));
    }

    @Test
    public void acceptNoSampleGroup_withConfirmation_persistsUnderUnknownPatientWithAuditNote() {
        AnalyzerResultItem walkUp = buildItem("3010", "4.1", "WALKUP01", 1, true, false);
        walkUp.setUnmatchedAction("ACCEPT_UNKNOWN");

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(walkUp));
        acceptService.acceptAndPersist(items, TEST_SYS_USER_ID);

        Sample sample = sampleService.getSampleByAccessionNumber("WALKUP01");
        assertNotNull("confirmed accept must create the sample", sample);
        Patient patient = sampleHumanService.getPatientForSample(sample);
        assertNotNull(patient);
        assertEquals("confirmed accept commits under the unidentified-patient placeholder", "UNKNOWN_",
                patient.getPerson().getLastName());
        assertNull("staging row must be consumed", analyzerResultsService.readAnalyzerResults("3010"));
        assertTrue("the explicit decision must be audit-noted on the result",
                walkUp.getNote() != null && walkUp.getNote().contains("WALKUP01"));

        Analysis analysis = analysisService.getAnalysisByAccessionAndTestId("WALKUP01", "4001").get(0);
        List<Result> results = resultService.getResultsByAnalysis(analysis);
        Result acceptedResult = results.get(results.size() - 1);
        assertEquals("K", acceptedResult.getRawCode());
        assertEquals("mmol/L", acceptedResult.getRawUnit());
        assertEquals("2823-3", acceptedResult.getLoinc());
        assertEquals("mmol/L", acceptedResult.getUcumValue());
        assertEquals("NORMALIZED", acceptedResult.getStatus());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals("accept must append one immutable result version", Integer.valueOf(1),
                jdbc.queryForObject("SELECT count(*) FROM clinlims.result_version WHERE result_id = ?",
                        Integer.class, Long.valueOf(acceptedResult.getId())));
        assertEquals("K", jdbc.queryForObject(
                "SELECT raw_code FROM clinlims.result_version WHERE result_id = ? AND version_number = 1",
                String.class, Long.valueOf(acceptedResult.getId())));
        assertEquals("NORMALIZED", jdbc.queryForObject(
                "SELECT status FROM clinlims.result_version WHERE result_id = ? AND version_number = 1",
                String.class, Long.valueOf(acceptedResult.getId())));
    }

    @Test
    public void secondArrivalOnAnalyzerCreatedSample_stillRequiresConfirmation() {
        // First walk-up arrival, explicitly confirmed: creates the
        // Unknown-patient sample and its NotRegistered/NotRegistered record
        // statuses.
        AnalyzerResultItem first = buildItem("3010", "4.1", "WALKUP01", 1, true, false);
        first.setUnmatchedAction("ACCEPT_UNKNOWN");
        acceptService.acceptAndPersist(new ArrayList<>(List.of(first)), TEST_SYS_USER_ID);

        // The find-or-attach leg: the accession now resolves to a sample, but
        // one no human ever verified — attaching more results must be gated
        // exactly like the create leg.
        assertTrue("analyzer-created sample must still require confirmation",
                acceptService.requiresUnmatchedConfirmation("WALKUP01"));

        AnalyzerResultItem second = buildItem("3011", "5.2", "WALKUP01", 1, true, false);
        assertThrows(UnmatchedSampleException.class,
                () -> acceptService.acceptAndPersist(new ArrayList<>(List.of(second)), TEST_SYS_USER_ID));
        assertNotNull("second staging row must remain after the blocked accept",
                analyzerResultsService.readAnalyzerResults("3011"));

        second.setUnmatchedAction("ACCEPT_UNKNOWN");
        acceptService.acceptAndPersist(new ArrayList<>(List.of(second)), TEST_SYS_USER_ID);
        assertNull("confirmed second accept must consume its staging row",
                analyzerResultsService.readAnalyzerResults("3011"));

        Analysis analysis = analysisService.getAnalysisByAccessionAndTestId("WALKUP01", "4001").get(0);
        List<Result> results = resultService.getResultsByAnalysis(analysis);
        Result updatedResult = results.get(results.size() - 1);
        assertEquals("5.2", updatedResult.getValue());
        assertEquals("K-SECOND", updatedResult.getRawCode());
        assertEquals("mEq/L", updatedResult.getRawUnit());
        assertEquals("2823-3", updatedResult.getLoinc());
        assertNull(updatedResult.getUcumValue());
        assertEquals("PARTIAL", updatedResult.getStatus());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals("the existing Result update must append version 2", Integer.valueOf(2),
                jdbc.queryForObject("SELECT count(*) FROM clinlims.result_version WHERE result_id = ?",
                        Integer.class, Long.valueOf(updatedResult.getId())));
        assertEquals("K-SECOND", jdbc.queryForObject(
                "SELECT raw_code FROM clinlims.result_version WHERE result_id = ? AND version_number = 2",
                String.class, Long.valueOf(updatedResult.getId())));
        assertEquals("PARTIAL", jdbc.queryForObject(
                "SELECT status FROM clinlims.result_version WHERE result_id = ? AND version_number = 2",
                String.class, Long.valueOf(updatedResult.getId())));
    }

    @Test
    public void rejectNoSampleGroup_notBlocked() {
        AnalyzerResultItem walkUp = buildItem("3010", "4.1", "WALKUP01", 1, false, true);
        // no unmatchedAction — rejection reports no live value, so it is never
        // gated; blocking it would leave the tech no way to discard garbage

        acceptService.acceptAndPersist(new ArrayList<>(List.of(walkUp)), TEST_SYS_USER_ID);
        assertNull("rejected staging row is consumed as before", analyzerResultsService.readAnalyzerResults("3010"));
    }

    @Test
    public void controlGroup_noOrderAccession_notBlocked() {
        // QC accessions never correspond to an order; gating them would block
        // every QC accept. isControl is hydrated from the DB row (LIS-158), so
        // a forged client flag cannot buy an exemption.
        AnalyzerResultItem control = buildItem("3020", "4.0", "QC-LOT-7", 1, true, false);

        acceptService.acceptAndPersist(new ArrayList<>(List.of(control)), TEST_SYS_USER_ID);
        assertNull("control staging row is consumed without confirmation",
                analyzerResultsService.readAnalyzerResults("3020"));
    }

    @Test
    public void humanEnteredSample_isNotGated() {
        assertFalse("an accession with a human-entered sample must not require confirmation",
                acceptService.requiresUnmatchedConfirmation("ORDERED01"));
    }
}
