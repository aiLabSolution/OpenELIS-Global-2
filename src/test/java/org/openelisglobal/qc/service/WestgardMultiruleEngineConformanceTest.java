package org.openelisglobal.qc.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.qc.dao.QCStatisticsDAO;
import org.openelisglobal.qc.service.evaluator.Rule10_xEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule1_2sEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule1_3sEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule2_2sEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule3_1sEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule4_1sEvaluator;
import org.openelisglobal.qc.service.evaluator.Rule7_tEvaluator;
import org.openelisglobal.qc.service.evaluator.RuleEvaluationResult;
import org.openelisglobal.qc.service.evaluator.RuleR_4sEvaluator;
import org.openelisglobal.qc.service.evaluator.WestgardRuleEvaluator;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCStatistics;
import org.openelisglobal.qc.valueholder.WestgardRuleConfig;

/**
 * Conformance gate for LIS-52 / Stage 5 §S5.1 — "Westgard multirules engine: QC
 * point vectors produce named in/out-of-control verdicts."
 *
 * <p>
 * Unlike {@link WestgardRuleEvaluationServiceTest} (which mocks the evaluators
 * and exercises only the orchestration), this test wires the <b>real</b>
 * evaluator set into {@link WestgardRuleEvaluationServiceImpl} and drives the
 * assembled engine with canonical Westgard QC point vectors, asserting the
 * exact set of <b>named</b> rule verdicts and their in-control (WARNING) vs
 * out-of-control (REJECTION) severity. It is the executable IQ/OQ proof that
 * the pinned core satisfies S5.1, and it is the only test that runs every
 * rule's production logic through the engine — including 1₂ₛ, 3₁ₛ, 7ₜ and 10ₓ,
 * which have no isolated unit test.
 *
 * <p>
 * All vectors use mean = 100, SD = 10 so that z = (value − 100) / 10; the
 * engine computes the z-score from the raw value and statistics (no pre-set
 * z-score). Each scenario is constructed to yield a single, exactly-known set
 * of violated rule codes, which lets the gate assert equality (not mere
 * containment) and so catches both false negatives and spurious extra verdicts.
 *
 * <p>
 * Non-vacuity: {@link #inControlVector_yieldsNoVerdicts_negativeControl()}
 * supplies a 12-point straddling history so all eight rules <em>evaluate</em>
 * yet none fire. A break in any threshold (verified manually by perturbing an
 * evaluator) turns this suite red.
 */
public class WestgardMultiruleEngineConformanceTest {

    // Canonical control-lot statistics shared by every vector.
    private static final String LOT = "LOT-S51";
    private static final String TEST = "100";
    private static final String INSTRUMENT = "200";
    private static final BigDecimal MEAN = new BigDecimal("100");
    private static final BigDecimal SD = new BigDecimal("10");

    private static final String REJECTION = "REJECTION";
    private static final String WARNING = "WARNING";

    // Rule codes are sourced from the evaluators themselves to avoid
    // transcribing the subscript-unicode rule names by hand.
    private static final String R_1_2S = new Rule1_2sEvaluator().getRuleCode();
    private static final String R_1_3S = new Rule1_3sEvaluator().getRuleCode();
    private static final String R_2_2S = new Rule2_2sEvaluator().getRuleCode();
    private static final String R_R_4S = new RuleR_4sEvaluator().getRuleCode();
    private static final String R_3_1S = new Rule3_1sEvaluator().getRuleCode();
    private static final String R_4_1S = new Rule4_1sEvaluator().getRuleCode();
    private static final String R_7_T = new Rule7_tEvaluator().getRuleCode();
    private static final String R_10_X = new Rule10_xEvaluator().getRuleCode();

    private WestgardRuleEvaluationServiceImpl engine;
    private QCStatistics statistics;
    // Declared severity per rule code, as published by each evaluator.
    private Map<String, String> declaredSeverity;

    @Before
    public void wireRealEngine() {
        // The full production evaluator set, in canonical order.
        List<WestgardRuleEvaluator> evaluators = Arrays.asList(new Rule1_2sEvaluator(), new Rule1_3sEvaluator(),
                new Rule2_2sEvaluator(), new RuleR_4sEvaluator(), new Rule3_1sEvaluator(), new Rule4_1sEvaluator(),
                new Rule7_tEvaluator(), new Rule10_xEvaluator());

        declaredSeverity = new LinkedHashMap<>();
        List<WestgardRuleConfig> enabledConfigs = new ArrayList<>();
        for (WestgardRuleEvaluator evaluator : evaluators) {
            declaredSeverity.put(evaluator.getRuleCode(), evaluator.getSeverity());
            enabledConfigs.add(enabledConfig(evaluator.getRuleCode()));
        }

        statistics = new QCStatistics();
        statistics.setControlLotId(LOT);
        statistics.setMean(MEAN);
        statistics.setStandardDeviation(SD);

        // Stub the two persistence collaborators the object-overload touches:
        // all rules enabled for this test/instrument, and the lot statistics.
        WestgardRuleConfigService ruleConfigService = mock(WestgardRuleConfigService.class);
        when(ruleConfigService.findEnabledByTestAndInstrument(TEST, INSTRUMENT)).thenReturn(enabledConfigs);
        QCStatisticsDAO statisticsDAO = mock(QCStatisticsDAO.class);
        when(statisticsDAO.findLatestByControlLot(anyString())).thenReturn(statistics);

        engine = new WestgardRuleEvaluationServiceImpl();
        inject(engine, "evaluators", evaluators);
        inject(engine, "ruleConfigService", ruleConfigService);
        inject(engine, "statisticsDAO", statisticsDAO);
    }

    // ----- The S5.1 conformance scenarios: vector -> named verdict set -----

    @Test
    public void inControlVector_yieldsNoVerdicts_negativeControl() {
        // 12 points straddling the mean within ±0.3 SD: every rule (including
        // 10ₓ, which needs nine prior results) evaluates, none should fire.
        QCResult current = qc("cur", "101");
        List<QCResult> history = history("103", "98", "101", "99", "102", "97", "100", "99", "101", "98", "102", "99");

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals("a stable in-control run must produce no named verdicts", Collections.emptySet(),
                violatedCodes(verdicts));
        assertFalse("an in-control run is not out-of-control", isOutOfControl(verdicts));
        assertEvaluated(verdicts);
    }

    @Test
    public void singleExtremePoint_tripsBoth1_2sWarningAnd1_3sRejection() {
        // One point at +3.5 SD: the multirule essence — a single QC value
        // simultaneously raises the 1₂ₛ warning and the 1₃ₛ rejection.
        QCResult current = qc("cur", "135");
        List<QCResult> history = history("100");

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals(setOf(R_1_2S, R_1_3S), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertTrue("a 1₃ₛ rejection puts the run out-of-control", isOutOfControl(verdicts));
    }

    @Test
    public void twoConsecutiveResultsBeyond2SD_trip2_2sRejection() {
        QCResult current = qc("cur", "124"); // z = +2.4
        List<QCResult> history = history("123"); // z = +2.3, same side

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        // The current point is itself beyond 2 SD (1₂ₛ warning) and completes
        // the 2₂ₛ systematic-shift rejection.
        assertEquals(setOf(R_1_2S, R_2_2S), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertTrue(isOutOfControl(verdicts));
    }

    @Test
    public void oppositeExtremesAcross4SD_tripR_4sRejection() {
        // Range = |+1.4 − (−2.6)| = 4.0 SD, but neither point alone reaches
        // 2 SD, so R₄ₛ fires in isolation.
        QCResult current = qc("cur", "114"); // z = +1.4
        List<QCResult> history = history("74"); // z = −2.6

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals(setOf(R_R_4S), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertTrue(isOutOfControl(verdicts));
    }

    @Test
    public void fourConsecutiveResultsBeyond1SD_trip3_1sAnd4_1s() {
        // Four consecutive points above +1 SD: the trailing three raise the
        // 3₁ₛ warning and all four raise the 4₁ₛ rejection.
        QCResult current = qc("cur", "115"); // z = +1.5
        List<QCResult> history = history("113", "114", "116"); // +1.3, +1.4, +1.6

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals(setOf(R_3_1S, R_4_1S), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertTrue("the 4₁ₛ rejection puts the run out-of-control", isOutOfControl(verdicts));
    }

    @Test
    public void sevenRisingPointsWithin1SD_trip7_tWarningOnly() {
        // A strictly increasing run that never leaves ±1 SD: only the 7ₜ
        // drift warning fires, so the run is flagged but NOT rejected.
        QCResult current = qc("cur", "106");
        List<QCResult> history = history("94", "96", "98", "100", "102", "104");

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals(setOf(R_7_T), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertFalse("a warning-only run stays in-control (autorelease not blocked)", isOutOfControl(verdicts));
    }

    @Test
    public void tenPointsOnTheSameSideOfMean_trip10_xRejection() {
        // Ten consecutive points above the mean (all < +1 SD, non-monotonic):
        // only the 10ₓ shift rejection fires.
        QCResult current = qc("cur", "104");
        List<QCResult> history = history("105", "103", "107", "104", "106", "102", "108", "103", "105");

        List<RuleEvaluationResult> verdicts = evaluate(current, history);

        assertEquals(setOf(R_10_X), violatedCodes(verdicts));
        assertSeveritiesMatchDeclared(verdicts);
        assertTrue(isOutOfControl(verdicts));
    }

    @Test
    public void everyNamedRuleIsExercisedAcrossTheConformanceVectors() {
        // Completeness: the union of verdicts across the scenarios covers the
        // entire published rule set, so no named rule is left unproven.
        Set<String> covered = new HashSet<>();
        covered.addAll(violatedCodes(evaluate(qc("cur", "135"), history("100"))));
        covered.addAll(violatedCodes(evaluate(qc("cur", "124"), history("123"))));
        covered.addAll(violatedCodes(evaluate(qc("cur", "114"), history("74"))));
        covered.addAll(violatedCodes(evaluate(qc("cur", "115"), history("113", "114", "116"))));
        covered.addAll(violatedCodes(evaluate(qc("cur", "106"), history("94", "96", "98", "100", "102", "104"))));
        covered.addAll(violatedCodes(
                evaluate(qc("cur", "104"), history("105", "103", "107", "104", "106", "102", "108", "103", "105"))));

        assertEquals("every published Westgard rule must fire on at least one conformance vector",
                declaredSeverity.keySet(), covered);
    }

    // --------------------------- helpers ---------------------------

    private List<RuleEvaluationResult> evaluate(QCResult current, List<QCResult> history) {
        return engine.evaluateAllRules(current, history, TEST, INSTRUMENT);
    }

    private Set<String> violatedCodes(List<RuleEvaluationResult> verdicts) {
        return verdicts.stream().filter(RuleEvaluationResult::isViolated).map(RuleEvaluationResult::getRuleCode)
                .collect(Collectors.toSet());
    }

    private boolean isOutOfControl(List<RuleEvaluationResult> verdicts) {
        return verdicts.stream().anyMatch(v -> v.isViolated() && REJECTION.equals(v.getSeverity()));
    }

    /** Each fired verdict must carry the severity its evaluator publishes. */
    private void assertSeveritiesMatchDeclared(List<RuleEvaluationResult> verdicts) {
        for (RuleEvaluationResult verdict : verdicts) {
            if (verdict.isViolated()) {
                assertEquals("severity for " + verdict.getRuleCode(), declaredSeverity.get(verdict.getRuleCode()),
                        verdict.getSeverity());
                assertTrue("severity must be a known class",
                        WARNING.equals(verdict.getSeverity()) || REJECTION.equals(verdict.getSeverity()));
            }
        }
    }

    /** The negative control must actually run every rule, not skip them. */
    private void assertEvaluated(List<RuleEvaluationResult> verdicts) {
        Set<String> evaluatedCodes = verdicts.stream().filter(RuleEvaluationResult::isEvaluated)
                .map(RuleEvaluationResult::getRuleCode).collect(Collectors.toSet());
        assertEquals("the in-control vector must exercise all eight rules", declaredSeverity.keySet(), evaluatedCodes);
    }

    private QCResult qc(String id, String value) {
        QCResult result = new QCResult();
        result.setId(id);
        result.setControlLotId(LOT);
        result.setTestId(TEST);
        result.setInstrumentId(INSTRUMENT);
        result.setResultValue(new BigDecimal(value));
        return result;
    }

    /** Build a chronological (oldest-first) history from raw values. */
    private List<QCResult> history(String... values) {
        List<QCResult> results = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            results.add(qc("h" + i, values[i]));
        }
        return results;
    }

    private WestgardRuleConfig enabledConfig(String ruleCode) {
        WestgardRuleConfig config = new WestgardRuleConfig();
        config.setRuleCode(ruleCode);
        config.setEnabled(true);
        config.setTestId(TEST);
        config.setInstrumentId(INSTRUMENT);
        return config;
    }

    private static Set<String> setOf(String... codes) {
        return new HashSet<>(Arrays.asList(codes));
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inject field: " + fieldName, e);
        }
    }
}
