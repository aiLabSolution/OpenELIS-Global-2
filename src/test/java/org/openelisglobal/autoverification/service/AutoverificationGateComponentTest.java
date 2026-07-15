package org.openelisglobal.autoverification.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.action.beanitems.AnalyzerResultItem;
import org.openelisglobal.analyzerresults.service.AnalyzerResultsAcceptServiceImpl;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.service.QCResultService;
import org.openelisglobal.qc.service.QCRuleViolationService;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCRuleViolation;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LIS-55 / Stage 5 §S5.4 component gate: the autoverification decision,
 * exercised end to end through the real analyzer accept path
 * ({@code acceptAndPersist}) against the real database.
 *
 * <p>
 * AC1 — a synthetic run with an out-of-control (1₃ₛ, the codebase's canonical
 * spelling of 1_3s) QC result blocks autorelease of that run's patient results:
 * the persisted analysis stays at TechnicalAcceptance with no released date,
 * and the hold reason (violated rule named) is recorded.
 *
 * <p>
 * AC2 — an in-control run auto-finalizes an in-range, within-delta result
 * (Finalized + releasedDate + "Auto-verified by system" note = the status=final
 * / verified_by=system contract; core has no verified_by column), while an
 * out-of-range and a delta-flagged result in the same accepted batch are each
 * held with their reason recorded.
 *
 * <p>
 * The QC violations are produced by the REAL Westgard engine: the test posts a
 * QC value through {@code QCResultService.createQCResult} and waits for the
 * async evaluation listener to classify the run (the listener rewrites
 * {@code QCResult.resultStatus} to ACCEPTED/REJECTED in the same transaction
 * that persists the violations, so polling that status is race-free).
 *
 * <p>
 * Note this test class commits real rows (the base class runs with
 * {@code Propagation.NOT_SUPPORTED}); all fixture ids live in the 78xx range
 * and accessions under the AVGT prefix, and runtime rows are removed in
 * {@link #tearDown()}.
 */
public class AutoverificationGateComponentTest extends BaseWebContextSensitiveTest {

    private static final String ANALYZER_ID = "7801";
    private static final String TEST_ID = "7810";
    private static final String CONTROL_LOT_ID = "lot-avg-001";
    private static final int EVALUATION_TIMEOUT_MS = 15000;

    @Autowired
    private AnalyzerResultsAcceptServiceImpl acceptService;

    @Autowired
    private AutoverificationGateService gateService;

    @Autowired
    private QCResultService qcResultService;

    @Autowired
    private QCResultDAO qcResultDAO;

    @Autowired
    private QCRuleViolationService violationService;

    @Autowired
    private SampleService sampleService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private IStatusService statusService;

    private AutoverificationGateServiceImpl gateTarget;
    private DeltaCheckService originalDeltaCheckService;

    @Before
    public void setUpGate() throws Exception {
        executeDataSetWithStateManagement("testdata/autoverification-gate.xml");

        // Heal status_of_sample: executeDataSetWithStateManagement TRUNCATEs
        // every table a dataset names and never restores, so any earlier test
        // whose fixture declares status_of_sample (e.g. analyzer-results.xml)
        // leaves it holding only that fixture's rows for the rest of the fork
        // — and the accept path then resolves statuses to -1. Idempotently
        // re-insert the canonical rows this flow needs (same pattern as
        // ensureAuditSystemUser) and refresh the id cache.
        ensureStatusRow("7901", "791", "Testing Started", "ORDER");
        ensureStatusRow("7902", "792", "SampleEntered", "SAMPLE");
        ensureStatusRow("7903", "793", "Technical Acceptance", "ANALYSIS");
        ensureStatusRow("7904", "794", "Technical Rejected", "ANALYSIS");
        ensureStatusRow("7905", "795", "Finalized", "ANALYSIS");
        ensureStatusRow("7906", "796", "Not Tested", "ANALYSIS");
        statusService.refreshCache();

        // default limit (no gender, age 0..Infinity) so the accept path's
        // unknown patient resolves it; normal range 40-60. Raw SQL because
        // ResultLimit.ageLimitsAreDefault() demands max_age = +Infinity, which
        // DBUnit's DOUBLE typecast cannot express.
        jdbcTemplate.update("INSERT INTO clinlims.result_limits (id, test_id, test_result_type_id, min_age, max_age,"
                + " low_normal, high_normal, low_valid, high_valid, lastupdated)"
                + " VALUES (7830, 7810, 7820, 0, 'Infinity'::float8, 40.0, 60.0, 0.0, 1000.0, now())");

        gateTarget = AopTestUtils.getUltimateTargetObject(gateService);
        originalDeltaCheckService = (DeltaCheckService) ReflectionTestUtils.getField(gateTarget, "deltaCheckService");
        ReflectionTestUtils.setField(gateTarget, "enabled", true);
    }

    @After
    public void tearDown() {
        // the gate bean is an application-context singleton — restore it so no
        // other web-context test sees an enabled gate or a stubbed delta engine
        if (gateTarget != null) {
            ReflectionTestUtils.setField(gateTarget, "enabled", false);
            ReflectionTestUtils.setField(gateTarget, "deltaCheckService", originalDeltaCheckService);
        }
        jdbcTemplate.update("DELETE FROM clinlims.result_limits WHERE id = 7830");

        // the base class runs without a test transaction, so accepted rows and
        // QC evaluation output are real commits — remove them
        jdbcTemplate.update("DELETE FROM clinlims.qc_alert WHERE violation_id IN"
                + " (SELECT id FROM clinlims.qc_rule_violation WHERE instrument_id = 7801)");
        jdbcTemplate.update("DELETE FROM clinlims.qc_rule_violation WHERE instrument_id = 7801");
        jdbcTemplate.update("DELETE FROM clinlims.qc_result WHERE instrument_id = 7801");
        jdbcTemplate.update("DELETE FROM clinlims.note WHERE reference_id IN (SELECT a.id FROM clinlims.analysis a"
                + " JOIN clinlims.sample_item si ON a.sampitem_id = si.id"
                + " JOIN clinlims.sample s ON si.samp_id = s.id WHERE s.accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.result WHERE analysis_id IN (SELECT a.id FROM clinlims.analysis a"
                + " JOIN clinlims.sample_item si ON a.sampitem_id = si.id"
                + " JOIN clinlims.sample s ON si.samp_id = s.id WHERE s.accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.analysis WHERE sampitem_id IN"
                + " (SELECT si.id FROM clinlims.sample_item si JOIN clinlims.sample s ON si.samp_id = s.id"
                + " WHERE s.accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.sample_item WHERE samp_id IN"
                + " (SELECT id FROM clinlims.sample WHERE accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.sample_human WHERE samp_id IN"
                + " (SELECT id FROM clinlims.sample WHERE accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.observation_history WHERE sample_id IN"
                + " (SELECT id FROM clinlims.sample WHERE accession_number LIKE 'AVGT%')");
        jdbcTemplate.update("DELETE FROM clinlims.sample WHERE accession_number LIKE 'AVGT%'");
        jdbcTemplate.update("DELETE FROM clinlims.patient WHERE id = 7861");
        jdbcTemplate.update("DELETE FROM clinlims.person WHERE id = 7860");
    }

    /**
     * Seed a pre-existing ORDERED sample with a NotStarted analysis for accession
     * AVGT500 — the pilot's primary workflow, where the accept path UPDATES an
     * existing analysis (bumping its @Version) instead of inserting a new one. Raw
     * SQL because the status_id FKs use the ensured status rows, which do not exist
     * yet at DBUnit fixture-load time.
     */
    private void seedOrderedSample() {
        // resolve status ids through the (freshly refreshed) StatusService —
        // in a standalone run the canonical seed rows exist with their own
        // ids and the ensured 79xx rows were skipped
        String startedId = statusService.getStatusID(StatusService.OrderStatus.Started);
        String sampleEnteredId = statusService.getStatusID(StatusService.SampleStatus.Entered);
        String notStartedId = statusService.getStatusID(AnalysisStatus.NotStarted);

        jdbcTemplate.update("INSERT INTO clinlims.person (id, lastupdated) VALUES (7860, now())");
        jdbcTemplate.update("INSERT INTO clinlims.patient (id, person_id, lastupdated) VALUES (7861, 7860, now())");
        jdbcTemplate.update(
                "INSERT INTO clinlims.sample (id, accession_number, domain, status_id, entered_date,"
                        + " received_date, lastupdated) VALUES (7862, 'AVGT500', 'H', ?::numeric, now(), now(), now())",
                startedId);
        jdbcTemplate.update("INSERT INTO clinlims.sample_human (id, samp_id, patient_id, lastupdated)"
                + " VALUES (7863, 7862, 7861, now())");
        jdbcTemplate.update("INSERT INTO clinlims.sample_item (id, samp_id, sort_order, typeosamp_id, status_id,"
                + " lastupdated) VALUES (7864, 7862, 1, 7815, ?::numeric, now())", sampleEnteredId);
        jdbcTemplate.update(
                "INSERT INTO clinlims.analysis (id, sampitem_id, test_id, status_id, analysis_type,"
                        + " revision, lastupdated) VALUES (7865, 7864, 7810, ?::numeric, 'MANUAL', '1', now())",
                notStartedId);
    }

    private void ensureStatusRow(String id, String code, String name, String statusType) {
        jdbcTemplate.update(
                "INSERT INTO clinlims.status_of_sample (id, code, name, description, status_type, lastupdated)"
                        + " SELECT ?::numeric, ?::numeric, ?, ?, ?, now() WHERE NOT EXISTS"
                        + " (SELECT 1 FROM clinlims.status_of_sample WHERE name = ? AND status_type = ?)",
                id, code, name, name, statusType, name, statusType);
    }

    // ---------------------------------------------------------------
    // Drivers and probes
    // ---------------------------------------------------------------

    private AnalyzerResultItem item(String stagedId, String accession, String value, int groupingNumber) {
        AnalyzerResultItem item = new AnalyzerResultItem();
        item.setId(stagedId);
        item.setResult(value);
        item.setTestId(TEST_ID);
        item.setAccessionNumber(accession);
        item.setTestName("Potassium AVG");
        item.setUnits("mmol/L");
        item.setSampleGroupingNumber(groupingNumber);
        item.setIsAccepted(true);
        item.setIsRejected(false);
        item.setIsDeleted(false);
        item.setIsControl(false);
        item.setTestResultType("N");
        item.setAnalyzerId(ANALYZER_ID);
        // Analysis.setCompletedDateForDisplay parses the configured display
        // format: the test DB seeds default date locale fr-FR, whose
        // date.format.formatKey is dd/MM/yyyy
        item.setCompleteDate("15/07/2026");
        return item;
    }

    private void accept(AnalyzerResultItem... items) {
        acceptService.acceptAndPersist(new ArrayList<>(List.of(items)), TEST_SYS_USER_ID);
    }

    /**
     * Post a QC value through the real pipeline and wait for the async Westgard
     * evaluation to classify it. The listener persists violations and rewrites
     * resultStatus in one REQUIRES_NEW transaction, so a non-PENDING status implies
     * the violations are committed and visible.
     */
    private QCResult runQC(String value, String expectedStatus) throws InterruptedException {
        QCResult qcResult = qcResultService.createQCResult(ANALYZER_ID, TEST_ID, CONTROL_LOT_ID, "NORMAL",
                new BigDecimal(value), "mmol/L", LocalDateTime.now());
        assertNotNull("QC result must be created", qcResult);

        long deadline = System.currentTimeMillis() + EVALUATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            QCResult current = qcResultDAO.get(qcResult.getId()).orElse(null);
            if (current != null && !"PENDING".equals(current.getResultStatus())) {
                assertEquals("Westgard run classification", expectedStatus, current.getResultStatus());
                return current;
            }
            Thread.sleep(250);
        }
        fail("QC result " + qcResult.getId() + " was never evaluated (still PENDING after " + EVALUATION_TIMEOUT_MS
                + "ms)");
        return null;
    }

    private Analysis findSingleAnalysis(String accession) {
        Sample sample = sampleService.getSampleByAccessionNumber(accession);
        assertNotNull("accept must have created sample " + accession, sample);
        List<Analysis> analyses = analysisService.getAnalysesBySampleId(sample.getId());
        assertEquals("exactly one analysis expected for " + accession, 1, analyses.size());
        return analyses.get(0);
    }

    private List<String> gateNotes(Analysis analysis) {
        return jdbcTemplate.queryForList("SELECT text FROM clinlims.note WHERE reference_id = ? AND subject = ?",
                String.class, Integer.valueOf(analysis.getId()), AutoverificationGateServiceImpl.NOTE_SUBJECT);
    }

    private String technicalAcceptanceId() {
        return statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);
    }

    private String finalizedId() {
        return statusService.getStatusID(AnalysisStatus.Finalized);
    }

    private void assertHeld(String accession, String... reasonFragments) {
        Analysis analysis = findSingleAnalysis(accession);
        assertEquals("held analysis must stay at TechnicalAcceptance", technicalAcceptanceId(), analysis.getStatusId());
        assertNull("held analysis must not carry a released date", analysis.getReleasedDate());
        List<String> notes = gateNotes(analysis);
        assertEquals("exactly one hold note expected for " + accession, 1, notes.size());
        assertTrue("hold note must say it is a hold, got: " + notes.get(0),
                notes.get(0).contains("Autoverification hold"));
        for (String fragment : reasonFragments) {
            assertTrue("hold reason must contain '" + fragment + "', got: " + notes.get(0),
                    notes.get(0).contains(fragment));
        }
    }

    private void assertAutoFinalized(String accession) {
        Analysis analysis = findSingleAnalysis(accession);
        assertEquals("clean analysis must be auto-finalized", finalizedId(), analysis.getStatusId());
        assertNotNull("auto-finalized analysis must carry a released date", analysis.getReleasedDate());
        List<String> notes = gateNotes(analysis);
        assertEquals("exactly one system note expected for " + accession, 1, notes.size());
        assertTrue("system attribution must be recorded, got: " + notes.get(0),
                notes.get(0).contains("Auto-verified by system"));
        assertEquals("persisted Result row must exist for the finalized analysis", Integer.valueOf(1),
                jdbcTemplate.queryForObject("SELECT count(*) FROM clinlims.result WHERE analysis_id = ?", Integer.class,
                        Integer.valueOf(analysis.getId())));
    }

    // ---------------------------------------------------------------
    // AC1 — out-of-control (1₃ₛ) QC run blocks autorelease
    // ---------------------------------------------------------------

    @Test
    public void outOfControlQcRun_blocksAutoreleaseOfRunsPatientResults() throws Exception {
        // value 135 against mean=100 SD=5 -> z=7.0 -> 1₃ₛ REJECTION (and 1₂ₛ)
        runQC("135.0", "REJECTED");

        // guard: the engine really persisted an unresolved 1₃ₛ REJECTION
        List<QCRuleViolation> active = violationService.findActiveRejections(ANALYZER_ID, TEST_ID);
        assertEquals("one active REJECTION expected", 1, active.size());
        assertEquals("1₃ₛ", active.get(0).getRuleCode());
        assertEquals("UNRESOLVED", active.get(0).getResolutionStatus());

        // in-range patient result on the same (instrument, test) run — ONLY
        // the QC leg can hold it
        accept(item("7851", "AVGT100", "50", 1));

        assertHeld("AVGT100", "QC out of control", "1₃ₛ");
    }

    // ---------------------------------------------------------------
    // AC2 — in-control run: clean auto-finalizes, flagged results held
    // ---------------------------------------------------------------

    @Test
    public void inControlRun_autoFinalizesCleanResult_holdsOutOfRangeAndDeltaFlagged() throws Exception {
        // value 102 -> z=0.4 -> in control, no violations
        runQC("102.0", "ACCEPTED");
        assertTrue("in-control run must leave no blocking violations",
                violationService.findActiveRejections(ANALYZER_ID, TEST_ID).isEmpty());

        // stand-in delta engine (LIS-54 not landed): flags exactly the result
        // whose value is 51
        ReflectionTestUtils.setField(gateTarget, "deltaCheckService",
                (DeltaCheckService) (analysis, result) -> "51".equals(result.getValue())
                        ? DeltaCheckVerdict.flagged("simulated delta engine: change vs prior result exceeds threshold")
                        : DeltaCheckVerdict.pass());

        // one batch: clean (50, in 40-60), out-of-range (75), delta-flagged (51)
        accept(item("7852", "AVGT200", "50", 1), item("7853", "AVGT201", "75", 2), item("7854", "AVGT202", "51", 3));

        assertAutoFinalized("AVGT200");
        assertHeld("AVGT201", "out of reference range", "above");
        assertHeld("AVGT202", "delta check flagged", "exceeds threshold");
    }

    // ---------------------------------------------------------------
    // Severity discrimination and default-off behavior
    // ---------------------------------------------------------------

    @Test
    public void warningOnlyViolation_doesNotBlockAutorelease() throws Exception {
        // value 112 -> z=2.4 -> 1₂ₛ WARNING only, no REJECTION -> run ACCEPTED
        runQC("112.0", "ACCEPTED");
        List<QCRuleViolation> all = violationService.findByInstrument(ANALYZER_ID);
        assertEquals("exactly the 1₂ₛ WARNING expected", 1, all.size());
        assertEquals("WARNING", all.get(0).getSeverity());
        assertTrue("a WARNING must not be a blocking violation",
                violationService.findActiveRejections(ANALYZER_ID, TEST_ID).isEmpty());

        accept(item("7855", "AVGT300", "50", 1));

        assertAutoFinalized("AVGT300");
    }

    @Test
    public void preExistingOrderedAnalysis_cleanResult_autoFinalizes() throws Exception {
        // Regression for the adversarial-review P1: the accept path UPDATES a
        // pre-existing ordered analysis (status NotStarted -> TA), bumping its
        // @Version — a gate that merges the grouping's stale detached copy
        // dies with StaleObjectStateException and silently releases nothing.
        // The gate must re-load by id inside its own transaction.
        seedOrderedSample();
        runQC("102.0", "ACCEPTED");

        accept(item("7857", "AVGT500", "50", 1));

        Analysis analysis = findSingleAnalysis("AVGT500");
        assertEquals("pre-existing ordered analysis must auto-finalize", finalizedId(), analysis.getStatusId());
        assertNotNull(analysis.getReleasedDate());
        List<String> notes = gateNotes(analysis);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("Auto-verified by system"));
    }

    @Test
    public void gateDisabled_leavesAcceptedResultsInHumanValidationQueue() {
        ReflectionTestUtils.setField(gateTarget, "enabled", false);

        accept(item("7856", "AVGT400", "50", 1));

        Analysis analysis = findSingleAnalysis("AVGT400");
        assertEquals("with the gate off, accept behaves exactly as before LIS-55", technicalAcceptanceId(),
                analysis.getStatusId());
        assertNull(analysis.getReleasedDate());
        assertTrue("no autoverification note of any kind may exist", gateNotes(analysis).isEmpty());
    }
}
