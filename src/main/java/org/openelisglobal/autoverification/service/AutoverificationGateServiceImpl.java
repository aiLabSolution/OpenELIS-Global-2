package org.openelisglobal.autoverification.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.note.service.NoteService;
import org.openelisglobal.note.service.NoteServiceImpl.NoteType;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.qc.service.QCRuleViolationService;
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
 * is not clearing the instrument. Run scoping is (instrument, test,
 * until-resolved) rather than a time window — an out-of-control instrument
 * releases nothing for that test until a human resolves the violation.</li>
 * <li><b>Reference range</b> — the persisted Result's minNormal/maxNormal
 * (populated at accept from result_limits; absent limits are persisted as
 * ±Infinity per the LIS-191 ruling, so an unconfigured range passes). Equal
 * min/max bounds are the upstream "no range configured" convention and also
 * pass. Non-numeric result types and unparseable values hold — the gate only
 * auto-releases what it can actually evaluate.</li>
 * <li><b>Delta check</b> — delegated to the {@link DeltaCheckService} SPI
 * (LIS-54). FLAGGED holds; NOT_EVALUABLE does not block (a check that cannot
 * run is not a violation).</li>
 * </ol>
 *
 * <p>
 * Pass ⇒ the same transition the human result-validation path performs:
 * statusId = Finalized, releasedDate stamped, saved via
 * {@link AnalysisService#update} (which writes the audit trail), plus an
 * internal "Auto-verified by system" Note — the recorded verified-by-system
 * artifact (core has no verified_by column). Hold ⇒ the analysis stays at
 * TechnicalAcceptance and an internal Note records every failed leg.
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
    private DeltaCheckService deltaCheckService;

    @Autowired
    private IStatusService statusService;

    @Override
    @Transactional
    public void evaluateAndFinalize(List<SampleGrouping> sampleGroupings, String sysUserId) {
        if (!enabled) {
            return;
        }

        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);

        for (Map.Entry<Analysis, List<Result>> entry : pairByAnalysis(sampleGroupings).entrySet()) {
            Analysis analysis = entry.getKey();
            if (!technicalAcceptanceId.equals(analysis.getStatusId())) {
                continue;
            }

            List<String> holdReasons = evaluate(analysis, entry.getValue());
            if (holdReasons.isEmpty()) {
                autoFinalize(analysis, sysUserId);
            } else {
                hold(analysis, holdReasons, sysUserId);
            }
        }
    }

    /**
     * Analyses and results are paired positionally in a SampleGrouping (see
     * AnalyzerResultsServiceImpl.insertResults); the same analysis may appear
     * at several indices, so collect all of its results before deciding.
     */
    private Map<Analysis, List<Result>> pairByAnalysis(List<SampleGrouping> sampleGroupings) {
        Map<Analysis, List<Result>> byAnalysis = new LinkedHashMap<>();
        for (SampleGrouping grouping : sampleGroupings) {
            if (grouping.analysisList == null || grouping.resultList == null) {
                continue;
            }
            for (int i = 0; i < grouping.analysisList.size() && i < grouping.resultList.size(); i++) {
                byAnalysis.computeIfAbsent(grouping.analysisList.get(i), k -> new ArrayList<>())
                        .add(grouping.resultList.get(i));
            }
        }
        return byAnalysis;
    }

    private List<String> evaluate(Analysis analysis, List<Result> results) {
        List<String> reasons = new ArrayList<>();

        String analyzerId = analysis.getAnalyzerId();
        String testId = analysis.getTest() == null ? null : analysis.getTest().getId();
        if (analyzerId == null || testId == null) {
            reasons.add("QC status cannot be established (analyzer or test unknown)");
        } else {
            for (QCRuleViolation violation : qcRuleViolationService.findActiveRejections(analyzerId, testId)) {
                reasons.add("QC out of control: " + violation.getRuleCode() + " (REJECTION, "
                        + violation.getResolutionStatus() + ") on instrument " + analyzerId + " for test " + testId);
            }
        }

        for (Result result : results) {
            evaluateRange(result, reasons);

            DeltaCheckVerdict delta = deltaCheckService.evaluate(analysis, result);
            if (delta.getOutcome() == DeltaCheckVerdict.Outcome.FLAGGED) {
                reasons.add("delta check flagged: " + delta.getReason());
            }
        }

        return reasons;
    }

    private void evaluateRange(Result result, List<String> reasons) {
        if (!"N".equals(result.getResultType())) {
            reasons.add("result type '" + result.getResultType() + "' cannot be auto-verified (numeric only)");
            return;
        }

        double value;
        try {
            value = Double.parseDouble(result.getValue());
        } catch (NumberFormatException | NullPointerException e) {
            reasons.add("numeric result value '" + result.getValue() + "' cannot be evaluated");
            return;
        }

        Double min = result.getMinNormal();
        Double max = result.getMaxNormal();
        if (min == null || max == null || min.equals(max)) {
            // Upstream convention: equal (or absent) bounds mean no range is
            // configured — absent limits are persisted as ±Infinity at accept.
            return;
        }
        if (value < min) {
            reasons.add("out of reference range: " + result.getValue() + " below " + min);
        } else if (value > max) {
            reasons.add("out of reference range: " + result.getValue() + " above " + max);
        }
    }

    private void autoFinalize(Analysis analysis, String sysUserId) {
        analysis.setSysUserId(sysUserId);
        analysis.setStatusId(statusService.getStatusID(AnalysisStatus.Finalized));
        analysis.setReleasedDate(new Timestamp(System.currentTimeMillis()));
        analysisService.update(analysis);

        insertNote(analysis,
                "Auto-verified by system (autoverification gate): reference range PASS; QC in-control; delta check"
                        + " PASS",
                sysUserId);

        LogEvent.logInfo(getClass().getSimpleName(), "autoFinalize",
                "Auto-verified analysis " + analysis.getId() + " (test "
                        + (analysis.getTest() == null ? "?" : analysis.getTest().getId()) + ", analyzer "
                        + analysis.getAnalyzerId() + ")");
    }

    private void hold(Analysis analysis, List<String> reasons, String sysUserId) {
        insertNote(analysis, "Autoverification hold — held for human review: " + String.join("; ", reasons),
                sysUserId);

        LogEvent.logInfo(getClass().getSimpleName(), "hold",
                "Held analysis " + analysis.getId() + " for human review: " + String.join("; ", reasons));
    }

    private void insertNote(Analysis analysis, String text, String sysUserId) {
        Note note = noteService.createSavableNote(analysis, NoteType.INTERNAL, text, NOTE_SUBJECT, sysUserId);
        noteService.insert(note);
    }
}
