package org.openelisglobal.autoverification.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.autoverification.dao.DeltaCheckConfigDAO;
import org.openelisglobal.autoverification.dao.DeltaCheckPriorResultDAO;
import org.openelisglobal.autoverification.valueholder.DeltaCheckConfig;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit coverage for the delta-check engine (LIS-54 / S5.3): per-analyte
 * prior-vs-current test vectors must produce flag-or-pass EXACTLY at the
 * configured threshold boundary (AC1), an implausible jump must flag while a
 * within-threshold change passes (AC2), and everything the engine cannot
 * honestly compare (no config, no prior, non-numeric values, unit mismatch)
 * must come back NOT_EVALUABLE — never FLAGGED, never PASS.
 *
 * <p>
 * Boundary semantics under test: a change EQUAL to the configured threshold
 * passes; only a strictly greater change flags. Comparisons are exact decimal
 * arithmetic (BigDecimal, relative leg cross-multiplied), so 10% of 200 is
 * exactly 20 — no binary floating-point smear at the boundary.
 *
 * <p>
 * Persistence-level behavior — that the prior-result query really selects the
 * patient's most recent Finalized result through the
 * Patient-&gt;Sample-&gt;SampleItem-&gt;Analysis-&gt;Result chain — is the
 * component test's job ({@code AutoverificationGateComponentTest}).
 */
public class DeltaCheckServiceImplTest {

    private static final String FINALIZED_STATUS_ID = "25";
    private static final String TEST_ID = "4001";
    private static final String ANALYSIS_ID = "9001";
    private static final String PATIENT_ID = "601";

    private DeltaCheckServiceImpl engine;
    private DeltaCheckConfigDAO configDAO;
    private DeltaCheckPriorResultDAO priorResultDAO;
    private IStatusService statusService;

    @Before
    public void setUp() {
        configDAO = mock(DeltaCheckConfigDAO.class);
        priorResultDAO = mock(DeltaCheckPriorResultDAO.class);
        statusService = mock(IStatusService.class);
        when(statusService.getStatusID(AnalysisStatus.Finalized)).thenReturn(FINALIZED_STATUS_ID);

        engine = new DeltaCheckServiceImpl();
        ReflectionTestUtils.setField(engine, "deltaCheckConfigDAO", configDAO);
        ReflectionTestUtils.setField(engine, "priorResultDAO", priorResultDAO);
        ReflectionTestUtils.setField(engine, "statusService", statusService);
    }

    // ---------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------

    private Analysis analysis() {
        Analysis analysis = new Analysis();
        analysis.setId(ANALYSIS_ID);
        org.openelisglobal.test.valueholder.Test test = new org.openelisglobal.test.valueholder.Test();
        test.setId(TEST_ID);
        analysis.setTest(test);
        return analysis;
    }

    private Result numericResult(String value, String ucumUnit) {
        Result result = new Result();
        result.setResultType("N");
        result.setValue(value);
        result.setUcumValue(ucumUnit);
        return result;
    }

    private DeltaCheckConfig config(String absoluteChange, String relativeChangePercent) {
        DeltaCheckConfig config = new DeltaCheckConfig();
        config.setId("cfg-1");
        config.setTestId(TEST_ID);
        config.setAbsoluteChange(absoluteChange == null ? null : new BigDecimal(absoluteChange));
        config.setRelativeChangePercent(relativeChangePercent == null ? null : new BigDecimal(relativeChangePercent));
        config.setActive(true);
        return config;
    }

    private void givenConfig(DeltaCheckConfig config) {
        when(configDAO.findActiveByTestId(TEST_ID)).thenReturn(config);
    }

    private void givenPrior(String value, String ucumUnit) {
        when(priorResultDAO.findPatientIdForAnalysis(ANALYSIS_ID)).thenReturn(PATIENT_ID);
        when(priorResultDAO.findMostRecentPriorFinalResult(PATIENT_ID, TEST_ID, ANALYSIS_ID, FINALIZED_STATUS_ID))
                .thenReturn(numericResult(value, ucumUnit));
    }

    private DeltaCheckVerdict evaluate(String currentValue) {
        return evaluate(currentValue, "mmol/L");
    }

    private DeltaCheckVerdict evaluate(String currentValue, String ucumUnit) {
        return engine.evaluate(analysis(), numericResult(currentValue, ucumUnit));
    }

    private void assertOutcome(DeltaCheckVerdict.Outcome expected, DeltaCheckVerdict verdict) {
        assertEquals("verdict outcome (reason: " + verdict.getReason() + ")", expected, verdict.getOutcome());
    }

    // ---------------------------------------------------------------
    // AC1 — absolute threshold: exact boundary vectors
    // ---------------------------------------------------------------

    @Test
    public void absoluteThreshold_changeExactlyAtThreshold_passes() {
        givenConfig(config("5.0", null));
        givenPrior("100", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("105.0"));
        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("95.0"));
    }

    @Test
    public void absoluteThreshold_changeJustOverThreshold_flags() {
        givenConfig(config("5.0", null));
        givenPrior("100", "mmol/L");

        DeltaCheckVerdict up = evaluate("105.01");
        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, up);
        assertTrue("reason must name the values and threshold, got: " + up.getReason(),
                up.getReason().contains("105.01") && up.getReason().contains("100")
                        && up.getReason().contains("absolute threshold 5.0"));

        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, evaluate("94.99"));
    }

    @Test
    public void absoluteThreshold_withinThreshold_passes() {
        givenConfig(config("5.0", null));
        givenPrior("100", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("102"));
        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("100"));
    }

    // ---------------------------------------------------------------
    // AC1 — relative threshold: exact boundary vectors (no float smear)
    // ---------------------------------------------------------------

    @Test
    public void relativeThreshold_changeExactlyAtThreshold_passes() {
        givenConfig(config(null, "10"));
        givenPrior("200", "mmol/L");

        // 10% of 200 is exactly 20 — 220/180 sit exactly on the boundary
        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("220"));
        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("180"));
    }

    @Test
    public void relativeThreshold_changeJustOverThreshold_flags() {
        givenConfig(config(null, "10"));
        givenPrior("200", "mmol/L");

        DeltaCheckVerdict verdict = evaluate("220.1");
        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, verdict);
        assertTrue("reason must name the relative threshold, got: " + verdict.getReason(),
                verdict.getReason().contains("relative threshold 10%"));

        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, evaluate("179.9"));
    }

    @Test
    public void relativeThreshold_zeroPrior_anyNonZeroChangeFlags_zeroChangePasses() {
        givenConfig(config(null, "50"));
        givenPrior("0", "mmol/L");

        // a relative change from a zero prior is unbounded — any movement flags
        DeltaCheckVerdict verdict = evaluate("0.1");
        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, verdict);
        assertTrue("reason must call out the zero prior, got: " + verdict.getReason(),
                verdict.getReason().contains("zero prior"));

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("0.0"));
    }

    // ---------------------------------------------------------------
    // AC1 — both thresholds configured: either exceeded flags
    // ---------------------------------------------------------------

    @Test
    public void bothThresholds_absoluteExceededRelativeNot_flags() {
        // abs 5, rel 50%: prior 100 -> 106 is Δ6 (abs exceeded) but only 6%
        givenConfig(config("5", "50"));
        givenPrior("100", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, evaluate("106"));
    }

    @Test
    public void bothThresholds_relativeExceededAbsoluteNot_flags() {
        // abs 5, rel 50%: prior 2 -> 5 is Δ3 (under abs) but 150%
        givenConfig(config("5", "50"));
        givenPrior("2", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, evaluate("5"));
    }

    @Test
    public void bothThresholds_neitherExceeded_passes() {
        givenConfig(config("5", "50"));
        givenPrior("100", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("103"));
    }

    // ---------------------------------------------------------------
    // AC2 — implausible jump flags, within-threshold change passes
    // ---------------------------------------------------------------

    @Test
    public void implausibleJump_flagsWithVerbatimReason() {
        // potassium-style config: abs 1.0 mmol/L — 4.1 -> 7.9 is a classic
        // transcription/sample-mix-up jump
        givenConfig(config("1.0", null));
        givenPrior("4.1", "mmol/L");

        DeltaCheckVerdict verdict = evaluate("7.9");
        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, verdict);
        assertTrue("hold-note reason must carry the full comparison, got: " + verdict.getReason(),
                verdict.getReason().contains("7.9") && verdict.getReason().contains("4.1")
                        && verdict.getReason().contains("3.8"));
    }

    @Test
    public void withinThresholdChange_passes() {
        givenConfig(config("1.0", null));
        givenPrior("4.1", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("4.6"));
    }

    // ---------------------------------------------------------------
    // NOT_EVALUABLE legs — a check that cannot run is not a violation
    // ---------------------------------------------------------------

    @Test
    public void noConfigForTest_notEvaluable_andPriorLookupSkipped() {
        when(configDAO.findActiveByTestId(TEST_ID)).thenReturn(null);

        DeltaCheckVerdict verdict = evaluate("100");
        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, verdict);
        assertTrue("reason must say no thresholds are configured, got: " + verdict.getReason(),
                verdict.getReason().contains("no delta-check thresholds configured"));
        verifyZeroInteractions(priorResultDAO);
    }

    @Test
    public void configWithBothThresholdsNull_notEvaluable() {
        givenConfig(config(null, null));

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("100"));
        verifyZeroInteractions(priorResultDAO);
    }

    @Test
    public void noPriorFinalResult_notEvaluable_notFlagged() {
        givenConfig(config("5", null));
        when(priorResultDAO.findPatientIdForAnalysis(ANALYSIS_ID)).thenReturn(PATIENT_ID);
        when(priorResultDAO.findMostRecentPriorFinalResult(PATIENT_ID, TEST_ID, ANALYSIS_ID, FINALIZED_STATUS_ID))
                .thenReturn(null);

        DeltaCheckVerdict verdict = evaluate("100");
        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, verdict);
        assertTrue("reason must say there is no prior final result, got: " + verdict.getReason(),
                verdict.getReason().contains("no prior final result"));
    }

    @Test
    public void noPatientLinkedToAnalysis_notEvaluable() {
        givenConfig(config("5", null));
        when(priorResultDAO.findPatientIdForAnalysis(ANALYSIS_ID)).thenReturn(null);

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("100"));
    }

    @Test
    public void nonNumericResultType_notEvaluable() {
        givenConfig(config("5", null));
        Result result = numericResult("positive", "mmol/L");
        result.setResultType("D");

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, engine.evaluate(analysis(), result));
    }

    @Test
    public void unparseableCurrentValue_notEvaluable() {
        givenConfig(config("5", null));
        givenPrior("100", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("POS"));
        // "NaN"/"Infinity" parse as doubles but not as exact decimals — the
        // engine must not inherit the parseable-garbage bypass (LIS-55 gotcha)
        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("NaN"));
        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("Infinity"));
    }

    @Test
    public void unparseablePriorValue_notEvaluable() {
        givenConfig(config("5", null));
        givenPrior("QNS", "mmol/L");

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("100"));
    }

    @Test
    public void ucumUnitMismatch_notEvaluable() {
        givenConfig(config("5", null));
        givenPrior("100", "10*3/uL");

        DeltaCheckVerdict verdict = evaluate("100", "10*9/L");
        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, verdict);
        assertTrue("reason must name both units, got: " + verdict.getReason(),
                verdict.getReason().contains("10*9/L") && verdict.getReason().contains("10*3/uL"));
    }

    @Test
    public void bothUnitsAbsent_areComparable() {
        // pre-UCUM rows (manual entry, legacy accept path) carry no unit; two
        // unit-less results for the same test are still the same analyte
        givenConfig(config("5", null));
        givenPrior("100", null);

        assertOutcome(DeltaCheckVerdict.Outcome.PASS, evaluate("103", null));
        assertOutcome(DeltaCheckVerdict.Outcome.FLAGGED, evaluate("110", null));
    }

    @Test
    public void analysisWithoutTest_notEvaluable() {
        Analysis analysis = new Analysis();
        analysis.setId(ANALYSIS_ID);

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE,
                engine.evaluate(analysis, numericResult("100", "mmol/L")));
        verifyZeroInteractions(priorResultDAO);
    }

    @Test
    public void inactiveConfigRowsAreNotUsed() {
        // the DAO contract: findActiveByTestId only returns active rows — the
        // engine treats "nothing active" identically to "nothing configured"
        when(configDAO.findActiveByTestId(anyString())).thenReturn(null);

        assertOutcome(DeltaCheckVerdict.Outcome.NOT_EVALUABLE, evaluate("100"));
        verify(configDAO).findActiveByTestId(TEST_ID);
    }
}
