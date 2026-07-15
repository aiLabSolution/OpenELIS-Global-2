package org.openelisglobal.autoverification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.autoverification.dao.DeltaCheckConfigDAO;
import org.openelisglobal.autoverification.dao.DeltaCheckPriorResultDAO;
import org.openelisglobal.autoverification.valueholder.DeltaCheckConfig;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delta-check engine (LIS-54 / S5.3): compares an incoming normalized
 * result value against the patient's most recent prior final {@link Result} for
 * the same test, using the per-test absolute/relative thresholds in
 * {@link DeltaCheckConfig}, and flags an implausible change for human review.
 *
 * <p>
 * {@code @Primary} so the autoverification gate (LIS-55) autowires this engine
 * over the inert {@link NotInstalledDeltaCheckService} default.
 *
 * <p>
 * Semantics:
 * <ul>
 * <li><b>Same analyte = same test.</b> Analyzer analytes are seeded as Tests
 * (analyzer_test_map), so a test id identifies the analyte across instruments
 * and entry paths.</li>
 * <li><b>Prior = the most recent Finalized analysis' result</b> for the same
 * patient and test with a non-null released date, excluding the incoming
 * analysis itself. No prior, no patient linkage, or no thresholds configured ⇒
 * {@code NOT_EVALUABLE} — a check that cannot run is not a violation (LIS-55
 * contract).</li>
 * <li><b>Exact decimal boundaries.</b> Values and thresholds compare as
 * {@link BigDecimal}: a change exactly AT a threshold passes, only a strictly
 * greater change flags, and the relative leg cross-multiplies
 * ({@code |Δ|·100 > threshold·|prior|}) so no division or binary float rounding
 * can smear the boundary. A zero prior makes any nonzero change an unbounded
 * relative change, which flags. {@code NaN}/{@code Infinity} do not parse as
 * decimals, so the double-parsing garbage bypass (LIS-55 range-leg gotcha)
 * cannot arise here.</li>
 * <li><b>Unit guard.</b> When both results carry a normalized UCUM unit they
 * must match exactly; comparing across units would manufacture or mask jumps.
 * Two unit-less results (legacy/manual rows) for the same test are considered
 * comparable.</li>
 * </ul>
 */
@Primary
@Service
public class DeltaCheckServiceImpl implements DeltaCheckService {

    @Autowired
    private DeltaCheckConfigDAO deltaCheckConfigDAO;

    @Autowired
    private DeltaCheckPriorResultDAO priorResultDAO;

    @Autowired
    private IStatusService statusService;

    @Override
    @Transactional(readOnly = true)
    public DeltaCheckVerdict evaluate(Analysis analysis, Result result) {
        if (analysis == null || result == null) {
            return DeltaCheckVerdict.notEvaluable("no analysis/result to evaluate");
        }
        String analysisId = analysis.getId();
        String testId = analysis.getTest() == null ? null : analysis.getTest().getId();
        if (GenericValidator.isBlankOrNull(analysisId) || GenericValidator.isBlankOrNull(testId)) {
            return DeltaCheckVerdict.notEvaluable("analysis carries no persisted id or test");
        }

        DeltaCheckConfig config = deltaCheckConfigDAO.findActiveByTestId(testId);
        if (config == null || (config.getAbsoluteChange() == null && config.getRelativeChangePercent() == null)) {
            return DeltaCheckVerdict.notEvaluable("no delta-check thresholds configured for test " + testId);
        }

        if (!"N".equals(result.getResultType())) {
            return DeltaCheckVerdict
                    .notEvaluable("result type '" + result.getResultType() + "' is not numeric (N only)");
        }
        BigDecimal current = parseDecimal(result.getValue());
        if (current == null) {
            return DeltaCheckVerdict.notEvaluable("result value '" + result.getValue() + "' is not numeric");
        }

        String patientId = priorResultDAO.findPatientIdForAnalysis(analysisId);
        if (patientId == null) {
            return DeltaCheckVerdict.notEvaluable("analysis " + analysisId + " is not linked to a patient");
        }
        Result prior = priorResultDAO.findMostRecentPriorFinalResult(patientId, testId, analysisId,
                statusService.getStatusID(AnalysisStatus.Finalized));
        if (prior == null) {
            return DeltaCheckVerdict.notEvaluable("no prior final result for this patient and test " + testId);
        }
        BigDecimal priorValue = parseDecimal(prior.getValue());
        if (priorValue == null) {
            return DeltaCheckVerdict.notEvaluable("prior final result value '" + prior.getValue() + "' is not numeric");
        }

        String currentUnit = normalizeUnit(result.getUcumValue());
        String priorUnit = normalizeUnit(prior.getUcumValue());
        if (!Objects.equals(currentUnit, priorUnit)) {
            return DeltaCheckVerdict
                    .notEvaluable("unit mismatch: incoming '" + currentUnit + "' vs prior '" + priorUnit + "'");
        }

        BigDecimal delta = current.subtract(priorValue).abs();
        List<String> violations = new ArrayList<>();

        BigDecimal absoluteThreshold = config.getAbsoluteChange();
        if (absoluteThreshold != null && delta.compareTo(absoluteThreshold) > 0) {
            violations.add("exceeds absolute threshold " + absoluteThreshold.toPlainString());
        }

        BigDecimal relativeThreshold = config.getRelativeChangePercent();
        if (relativeThreshold != null && delta.multiply(BigDecimal.valueOf(100))
                .compareTo(relativeThreshold.multiply(priorValue.abs())) > 0) {
            violations.add(describeRelativeViolation(delta, priorValue, relativeThreshold));
        }

        if (violations.isEmpty()) {
            return DeltaCheckVerdict.pass();
        }
        return DeltaCheckVerdict.flagged(
                "value " + current.toPlainString() + " vs prior final " + priorValue.toPlainString() + " (change "
                        + delta.toPlainString() + ") " + String.join(" and ", violations) + " for test " + testId);
    }

    private String describeRelativeViolation(BigDecimal delta, BigDecimal priorValue, BigDecimal relativeThreshold) {
        String threshold = "relative threshold " + relativeThreshold.toPlainString() + "%";
        if (priorValue.signum() == 0) {
            return "is an unbounded relative change from a zero prior, " + threshold;
        }
        BigDecimal percent = delta.multiply(BigDecimal.valueOf(100)).divide(priorValue.abs(), 2, RoundingMode.HALF_UP);
        return "= " + percent.toPlainString() + "%, exceeds " + threshold;
    }

    /**
     * Exact decimal parse; null for anything that is not a plain decimal number
     * (including NaN/Infinity, which BigDecimal rejects by design).
     */
    private BigDecimal parseDecimal(String value) {
        if (GenericValidator.isBlankOrNull(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeUnit(String ucumUnit) {
        return GenericValidator.isBlankOrNull(ucumUnit) ? null : ucumUnit.trim();
    }
}
