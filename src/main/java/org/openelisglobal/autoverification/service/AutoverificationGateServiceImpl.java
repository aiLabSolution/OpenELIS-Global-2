package org.openelisglobal.autoverification.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.note.service.NoteService;
import org.openelisglobal.note.service.NoteServiceImpl.NoteType;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.service.QCRuleViolationService;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCRuleViolation;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AutoverificationGateService}.
 *
 * <p>
 * Gate legs, all fail-closed except where a rule is explicitly configured
 * absent:
 * <ol>
 * <li><b>QC-run status</b> — an analysis is blocked while any
 * REJECTION-severity {@link QCRuleViolation} for its (instrument = analyzer,
 * test) is not yet RESOLVED. ACKNOWLEDGED still blocks: acknowledging an alert
 * is not clearing the instrument. It is also blocked while any {@link QCResult}
 * for that (instrument, test) is still PENDING evaluation — a run whose
 * Westgard evaluation has not finished is not yet known to be in control, so
 * the gate does not release against it (the evaluation listener is
 * asynchronous). Run scoping is (instrument, test, until-resolved) rather than
 * a time window — an out-of-control instrument releases nothing for that test
 * until a human resolves the violation.</li>
 * <li><b>Reference range</b> — the persisted Result's minNormal/maxNormal
 * (populated at accept from result_limits; absent limits are persisted as
 * ±Infinity per the LIS-191 ruling, so an unconfigured range passes and is
 * recorded as "no range configured"). Equal min/max bounds are the upstream "no
 * range configured" convention and also pass. Non-numeric result types,
 * unparseable values and non-finite values (NaN/Infinity parse cleanly but
 * compare as never-out-of-range) hold — the gate only auto-releases what it can
 * actually evaluate.</li>
 * <li><b>Delta check</b> — delegated to the {@link DeltaCheckService} SPI
 * (LIS-54). FLAGGED holds; NOT_EVALUABLE does not block (a check that cannot
 * run is not a violation) but is recorded as such in the release note.</li>
 * </ol>
 *
 * <p>
 * Pass ⇒ the same <b>status transition</b> the human result-validation path
 * performs: statusId = Finalized, releasedDate stamped, saved via
 * {@link AnalysisService#update} (which writes the audit trail), plus an
 * internal "Auto-verified by system" Note recording the actual per-leg outcomes
 * — the recorded verified-by-system artifact (core has no verified_by column).
 * The human path's release <b>side effects</b> (notifications,
 * sample-completion roll-up, IResultUpdate updaters, FHIR export) are NOT yet
 * replicated — LIS-226 tracks them and gates enabling
 * {@code autoverification.enabled} in any deployment. Hold ⇒ the analysis stays
 * at TechnicalAcceptance and an internal Note records every failed leg.
 *
 * <p>
 * The SampleGrouping analyses are detached copies whose {@code @Version} is
 * stale for pre-existing (ordered) analyses — persistAnalyzerResults bumped the
 * row after they were loaded. The gate therefore groups by analysis id and
 * re-loads each analysis inside its own transaction before deciding; merging
 * the stale copies instead throws StaleObjectStateException and kills the whole
 * gate pass.
 */
@Service
public class AutoverificationGateServiceImpl implements AutoverificationGateService {

    public static final String NOTE_SUBJECT = "Autoverification";

    @Value("${autoverification.enabled:false}")
    private boolean enabled;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private NoteService noteService;

    @Autowired
    private QCRuleViolationService qcRuleViolationService;

    @Autowired
    private QCResultDAO qcResultDAO;

    @Autowired
    private DeltaCheckService deltaCheckService;

    @Autowired
    private IStatusService statusService;

    @PersistenceContext
    private EntityManager entityManager;

    /** Per-analysis evaluation: hold reasons plus honest pass-leg records. */
    private static final class GateEvaluation {
        final List<String> holdReasons = new ArrayList<>();
        final List<String> passRecord = new ArrayList<>();
    }

    @Override
    @Transactional
    public void evaluateAndFinalize(List<SampleGrouping> sampleGroupings, String sysUserId) {
        if (!enabled) {
            return;
        }

        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);

        for (Map.Entry<String, List<Result>> entry : pairByAnalysisId(sampleGroupings).entrySet()) {
            Analysis analysis;
            try {
                analysis = analysisService.get(entry.getKey());
            } catch (RuntimeException e) {
                // per-analysis granularity: one unloadable analysis must not
                // abort the rest of the batch (fail-safe — it simply stays in
                // the human validation queue)
                LogEvent.logError(getClass().getSimpleName(), "evaluateAndFinalize", "Cannot load analysis "
                        + entry.getKey() + " — leaving it for human validation: " + e.getMessage());
                continue;
            }
            // Detach from the Hibernate session BEFORE mutating so
            // AuditableBaseObjectServiceImpl.update() loads a clean "old" copy
            // for the audit-trail diff — a managed instance would be compared
            // against itself and the Finalized transition would never reach
            // the history table (same idiom as SampleEditServiceImpl).
            entityManager.detach(analysis);
            if (!technicalAcceptanceId.equals(analysis.getStatusId())) {
                continue;
            }

            GateEvaluation evaluation = evaluate(analysis, entry.getValue());
            if (evaluation.holdReasons.isEmpty()) {
                autoFinalize(analysis, evaluation, sysUserId);
            } else {
                hold(analysis, evaluation.holdReasons, sysUserId);
            }
        }
    }

    /**
     * Analyses and results are paired positionally in a SampleGrouping (see
     * AnalyzerResultsServiceImpl.insertResults); the same persisted analysis may
     * appear at several indices AND as distinct detached instances, so group by
     * database id — never by object identity — before deciding.
     */
    private Map<String, List<Result>> pairByAnalysisId(List<SampleGrouping> sampleGroupings) {
        Map<String, List<Result>> byAnalysisId = new LinkedHashMap<>();
        for (SampleGrouping grouping : sampleGroupings) {
            if (grouping.analysisList == null || grouping.resultList == null) {
                continue;
            }
            for (int i = 0; i < grouping.analysisList.size() && i < grouping.resultList.size(); i++) {
                Analysis analysis = grouping.analysisList.get(i);
                if (analysis == null || GenericValidator.isBlankOrNull(analysis.getId())) {
                    LogEvent.logError(getClass().getSimpleName(), "pairByAnalysisId",
                            "Skipping unpersisted analysis at grouping index " + i
                                    + " — cannot be autoverified or held");
                    continue;
                }
                byAnalysisId.computeIfAbsent(analysis.getId(), k -> new ArrayList<>()).add(grouping.resultList.get(i));
            }
        }
        return byAnalysisId;
    }

    private GateEvaluation evaluate(Analysis analysis, List<Result> results) {
        GateEvaluation evaluation = new GateEvaluation();

        evaluateQC(analysis, evaluation);

        for (Result result : results) {
            evaluateRange(result, evaluation);
            evaluateDelta(analysis, result, evaluation);
        }

        return evaluation;
    }

    private void evaluateQC(Analysis analysis, GateEvaluation evaluation) {
        String analyzerId = analysis.getAnalyzerId();
        String testId = analysis.getTest() == null ? null : analysis.getTest().getId();
        if (analyzerId == null || testId == null) {
            evaluation.holdReasons.add("QC status cannot be established (analyzer or test unknown)");
            return;
        }

        boolean blocked = false;
        for (QCRuleViolation violation : qcRuleViolationService.findActiveRejections(analyzerId, testId)) {
            evaluation.holdReasons.add("QC out of control: " + violation.getRuleCode() + " (REJECTION, "
                    + violation.getResolutionStatus() + ") on instrument " + analyzerId + " for test " + testId);
            blocked = true;
        }

        List<QCResult> pending = qcResultDAO.findPendingByInstrumentAndTest(analyzerId, testId);
        if (!pending.isEmpty()) {
            evaluation.holdReasons.add("QC evaluation pending: " + pending.size()
                    + " unevaluated QC result(s) on instrument " + analyzerId + " for test " + testId);
            blocked = true;
        }

        if (!blocked) {
            evaluation.passRecord.add("QC: no active REJECTION violation and no pending QC evaluation for instrument "
                    + analyzerId + ", test " + testId);
        }
    }

    private void evaluateRange(Result result, GateEvaluation evaluation) {
        if (!"N".equals(result.getResultType())) {
            evaluation.holdReasons
                    .add("result type '" + result.getResultType() + "' cannot be auto-verified (numeric only)");
            return;
        }

        double value;
        try {
            value = Double.parseDouble(result.getValue());
        } catch (NumberFormatException | NullPointerException e) {
            evaluation.holdReasons.add("numeric result value '" + result.getValue() + "' cannot be evaluated");
            return;
        }
        if (!Double.isFinite(value)) {
            // "NaN"/"Infinity" parse cleanly but compare as never-out-of-range
            // — a fail-closed gate must not have a parseable-garbage bypass
            evaluation.holdReasons
                    .add("non-finite numeric result value '" + result.getValue() + "' cannot be auto-verified");
            return;
        }

        Double min = result.getMinNormal();
        Double max = result.getMaxNormal();
        boolean lowerBounded = min != null && !min.isInfinite();
        boolean upperBounded = max != null && !max.isInfinite();
        if ((!lowerBounded && !upperBounded) || (min != null && min.equals(max))) {
            // Upstream convention: equal (or absent) bounds mean no range is
            // configured — absent limits are persisted as ±Infinity at accept.
            evaluation.passRecord.add("range: no reference range configured for value " + result.getValue());
            return;
        }
        if (lowerBounded && value < min) {
            evaluation.holdReasons.add("out of reference range: " + result.getValue() + " below " + min);
        } else if (upperBounded && value > max) {
            evaluation.holdReasons.add("out of reference range: " + result.getValue() + " above " + max);
        } else {
            evaluation.passRecord.add("range: " + result.getValue() + " within [" + min + ", " + max + "]");
        }
    }

    private void evaluateDelta(Analysis analysis, Result result, GateEvaluation evaluation) {
        DeltaCheckVerdict delta = deltaCheckService.evaluate(analysis, result);
        switch (delta.getOutcome()) {
        case FLAGGED:
            evaluation.holdReasons.add("delta check flagged: " + delta.getReason());
            break;
        case PASS:
            evaluation.passRecord.add("delta: within threshold for value " + result.getValue());
            break;
        case NOT_EVALUABLE:
        default:
            evaluation.passRecord.add("delta: not evaluable (" + delta.getReason() + ")");
            break;
        }
    }

    private void autoFinalize(Analysis analysis, GateEvaluation evaluation, String sysUserId) {
        analysis.setSysUserId(sysUserId);
        analysis.setStatusId(statusService.getStatusID(AnalysisStatus.Finalized));
        analysis.setReleasedDate(new Timestamp(System.currentTimeMillis()));
        analysisService.update(analysis);

        insertNote(analysis,
                "Auto-verified by system (autoverification gate): " + String.join("; ", evaluation.passRecord),
                sysUserId);

        LogEvent.logInfo(getClass().getSimpleName(), "autoFinalize",
                "Auto-verified analysis " + analysis.getId() + " (test "
                        + (analysis.getTest() == null ? "?" : analysis.getTest().getId()) + ", analyzer "
                        + analysis.getAnalyzerId() + ")");
    }

    private void hold(Analysis analysis, List<String> reasons, String sysUserId) {
        insertNote(analysis, "Autoverification hold — held for human review: " + String.join("; ", reasons), sysUserId);

        LogEvent.logInfo(getClass().getSimpleName(), "hold",
                "Held analysis " + analysis.getId() + " for human review: " + String.join("; ", reasons));
    }

    private void insertNote(Analysis analysis, String text, String sysUserId) {
        Note note = noteService.createSavableNote(analysis, NoteType.INTERNAL, text, NOTE_SUBJECT, sysUserId);
        noteService.insert(note);
    }
}
