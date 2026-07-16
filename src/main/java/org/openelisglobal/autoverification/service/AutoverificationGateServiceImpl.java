package org.openelisglobal.autoverification.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.common.services.StatusService.OrderStatus;
import org.openelisglobal.common.services.registration.ValidationUpdateRegister;
import org.openelisglobal.common.services.registration.interfaces.IResultUpdate;
import org.openelisglobal.dataexchange.fhir.exception.FhirLocalPersistingException;
import org.openelisglobal.dataexchange.fhir.service.FhirTransformService;
import org.openelisglobal.dataexchange.orderresult.OrderResponseWorker.Event;
import org.openelisglobal.note.service.NoteService;
import org.openelisglobal.note.service.NoteServiceImpl.NoteType;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.notification.service.TestNotificationService;
import org.openelisglobal.notification.valueholder.NotificationConfigOption.NotificationNature;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.service.QCRuleViolationService;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCRuleViolation;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.reports.service.DocumentTrackService;
import org.openelisglobal.reports.service.DocumentTypeService;
import org.openelisglobal.result.action.util.ResultSet;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.resultvalidation.util.ResultValidationSaveService;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * The human path's release <b>side effects</b> are replicated after the batch
 * decision (LIS-226), in the same order as
 * {@code ResultValidationServiceImpl.persistdata} +
 * {@code AccessionValidationRestController}:
 * <ol>
 * <li>RESULT_VALIDATION notification per released result (per-result try/catch
 * — a notification failure never blocks a release, exactly as upstream);</li>
 * <li>sample-completion roll-up — the parent sample rolls to
 * {@code OrderStatus.Finished} when every analysis on it is terminal (Finalized
 * / Canceled / NonConforming), detached before mutation for the audit
 * diff;</li>
 * <li>registered {@link IResultUpdate} updaters get {@code transactionalUpdate}
 * with the same ResultSet shape the controller builds (DocumentTrack-classified
 * new-vs-corrected, resultEvent stamped). {@code postTransactionalCommitUpdate}
 * is intentionally <b>not</b> invoked — the human path's call is disabled
 * (commented out) upstream, and parity, not improvement, is the contract
 * here;</li>
 * <li>FHIR export ({@code transformPersistResultValidationFhirObjects}) —
 * deferred to <b>afterCommit</b>: the transform is {@code @Async} and re-loads
 * every entity by id in its own read-only transaction, so dispatching it before
 * this transaction commits would race the commit (the human path gets this
 * ordering for free by calling it after persistdata returns). An export failure
 * is logged but does not un-release — same residual as the human path.</li>
 * </ol>
 * An updater or roll-up failure rolls the whole gate transaction back (releases
 * stay atomic with their transactional side effects; the analyses simply stay
 * at TechnicalAcceptance for human validation). The notification dispatch is
 * the one non-transactional exception: the sender is {@code @Async}, so a
 * notification already in flight when a later leg rolls the release back may
 * still deliver — the human path has the identical window (persistdata notifies
 * before its sample and updater legs). Hold ⇒ the analysis stays at
 * TechnicalAcceptance and an internal Note records every failed leg.
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

    @Autowired
    private SampleService sampleService;

    @Autowired
    private SampleHumanService sampleHumanService;

    @Autowired
    private TestNotificationService testNotificationService;

    // @Lazy: fhirTransformServiceImpl sits in a circular reference with
    // fhirReferralServiceImpl and is @Async-proxied after creation. Injecting it
    // eagerly here pulls it into existence mid-cycle (this gate is created early
    // via
    // analyzerResultsAcceptServiceImpl) while its raw instance is already injected
    // elsewhere, and Spring aborts context startup. First use is post-boot
    // (afterCommit export), so a lazy proxy is safe.
    @Autowired
    @Lazy
    private FhirTransformService fhirTransformService;

    @Autowired
    private DocumentTrackService documentTrackService;

    @Autowired
    private ReferenceTablesService referenceTablesService;

    @Autowired
    private DocumentTypeService documentTypeService;

    /**
     * Seam over the static {@link ValidationUpdateRegister} (which reads
     * ConfigurationProperties), so tests can substitute updaters without touching
     * global state.
     */
    private Supplier<List<IResultUpdate>> updatersSupplier = ValidationUpdateRegister::getRegisteredUpdaters;

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

        List<Analysis> finalizedAnalyses = new ArrayList<>();
        ArrayList<Result> finalizedResults = new ArrayList<>();
        Set<String> finalizedResultIds = new LinkedHashSet<>();
        Map<String, String> sampleIdByAnalysisId = new LinkedHashMap<>();

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
            // Navigate to the parent sample while the analysis is still
            // managed (lazy proxies die at detach) — the completion roll-up
            // and the updater ResultSets need it.
            String sampleId = parentSampleId(analysis);
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
                finalizedAnalyses.add(analysis);
                if (sampleId != null) {
                    sampleIdByAnalysisId.put(analysis.getId(), sampleId);
                }
                for (Result result : entry.getValue()) {
                    if (result == null || GenericValidator.isBlankOrNull(result.getId())) {
                        LogEvent.logError(getClass().getSimpleName(), "evaluateAndFinalize", "Result without id on"
                                + " released analysis " + analysis.getId() + " — excluded from notification/export");
                        continue;
                    }
                    if (!finalizedResultIds.add(result.getId())) {
                        // the same persisted row can arrive as several
                        // detached copies — the FHIR transform dedups by
                        // composite key but the notification leg would fire
                        // once per copy
                        continue;
                    }
                    // downstream consumers (notification config lookup, FHIR
                    // device reference) navigate result.getAnalysis() — hand
                    // them the reloaded analysis, not the stale grouping copy
                    result.setAnalysis(analysis);
                    finalizedResults.add(result);
                }
            } else {
                hold(analysis, evaluation.holdReasons, sysUserId);
            }
        }

        if (finalizedAnalyses.isEmpty()) {
            return;
        }
        // Release side effects, in the human validation path's order
        // (persistdata: notifications -> sample roll-up -> updaters; then the
        // controller's FHIR export after commit).
        sendReleaseNotifications(finalizedResults);
        ArrayList<Sample> finishedSamples = rollUpFinishedSamples(new LinkedHashSet<>(sampleIdByAnalysisId.values()),
                sysUserId);
        runRegisteredUpdaters(finalizedResults, sampleIdByAnalysisId);
        scheduleFhirExport(finalizedAnalyses, finalizedResults, finishedSamples);
    }

    /** Parent sample id, or null when the linkage cannot be navigated. */
    private String parentSampleId(Analysis analysis) {
        if (analysis.getSampleItem() == null || analysis.getSampleItem().getSample() == null
                || GenericValidator.isBlankOrNull(analysis.getSampleItem().getSample().getId())) {
            LogEvent.logError(getClass().getSimpleName(), "parentSampleId", "Analysis " + analysis.getId()
                    + " has no navigable parent sample — completion roll-up skipped for it");
            return null;
        }
        return analysis.getSampleItem().getSample().getId();
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

    // ---------------------------------------------------------------
    // Release side effects (LIS-226) — parity with the human validation
    // path (ResultValidationServiceImpl.persistdata +
    // AccessionValidationRestController)
    // ---------------------------------------------------------------

    /**
     * Same call, same guard as persistdata: a notification failure is logged and
     * never blocks the release (the sender is also {@code @Async} upstream).
     */
    private void sendReleaseNotifications(List<Result> finalizedResults) {
        for (Result result : finalizedResults) {
            try {
                testNotificationService
                        .createAndSendNotificationsToConfiguredSources(NotificationNature.RESULT_VALIDATION, result);
            } catch (RuntimeException e) {
                LogEvent.logError(e);
            }
        }
    }

    /**
     * checkIfSamplesFinished, gate-side: a parent sample whose analyses are all
     * terminal (Finalized / Canceled / NonConforming) rolls to
     * {@code OrderStatus.Finished}. The just-finalized analyses are visible to the
     * query because their merge flushed in this same session.
     */
    private ArrayList<Sample> rollUpFinishedSamples(Set<String> sampleIds, String sysUserId) {
        ArrayList<Sample> finishedSamples = new ArrayList<>();
        if (sampleIds.isEmpty()) {
            return finishedSamples;
        }

        // HashSet, not Set.of: getStatusID answers "-1" for every unmapped
        // status, and Set.of throws on duplicates
        Set<String> terminalStatusIds = new LinkedHashSet<>();
        terminalStatusIds.add(statusService.getStatusID(AnalysisStatus.Finalized));
        terminalStatusIds.add(statusService.getStatusID(AnalysisStatus.Canceled));
        terminalStatusIds.add(statusService.getStatusID(AnalysisStatus.NonConforming_depricated));
        terminalStatusIds.remove("-1");
        String finishedStatusId = statusService.getStatusID(OrderStatus.Finished);
        if ("-1".equals(finishedStatusId)) {
            // unmapped status — writing "-1" would violate the FK and roll the
            // whole release back; leave the sample open for the human path
            LogEvent.logError(getClass().getSimpleName(), "rollUpFinishedSamples",
                    "OrderStatus.Finished is not mapped — sample completion roll-up skipped");
            return finishedSamples;
        }

        for (String sampleId : sampleIds) {
            List<Analysis> sampleAnalyses = analysisService.getAnalysesBySampleId(sampleId);
            if (sampleAnalyses == null || sampleAnalyses.isEmpty()) {
                continue;
            }
            if (sampleAnalyses.stream().allMatch(analysis -> terminalStatusIds.contains(analysis.getStatusId()))) {
                Sample sample = sampleService.get(sampleId);
                // same audit idiom as the analysis: detach so the update diff
                // records the Started -> Finished transition
                entityManager.detach(sample);
                sample.setSysUserId(sysUserId);
                sample.setStatusId(finishedStatusId);
                sampleService.update(sample);
                finishedSamples.add(sample);
                LogEvent.logInfo(getClass().getSimpleName(), "rollUpFinishedSamples",
                        "Sample " + sampleId + " rolled to Finished — all analyses terminal after autoverification");
            }
        }
        return finishedSamples;
    }

    /**
     * The controller's updater leg: registered {@link IResultUpdate} updaters get
     * {@code transactionalUpdate} with ResultSets built exactly like
     * {@code addResultSets} (DocumentTrack decides new-vs-corrected, the
     * resultEvent is stamped). {@code postTransactionalCommitUpdate} is
     * intentionally not invoked — its call site in the human path is disabled
     * (commented out) upstream, and the gate replicates the human path, it does not
     * extend it. An updater failure rolls the whole gate transaction back by
     * contract ("If thrown all transactions will be rolled back").
     */
    private void runRegisteredUpdaters(List<Result> finalizedResults, Map<String, String> sampleIdByAnalysisId) {
        List<IResultUpdate> updaters = updatersSupplier.get();
        if (updaters.isEmpty()) {
            return;
        }

        String resultTableId = referenceTablesService.getReferenceTableByName("RESULT").getId();
        String resultReportId = documentTypeService.getDocumentTypeByName("resultExport").getId();

        ResultValidationSaveService saveService = new ResultValidationSaveService();
        Map<String, Sample> sampleCache = new LinkedHashMap<>();
        for (Result result : finalizedResults) {
            String sampleId = sampleIdByAnalysisId.get(result.getAnalysis().getId());
            Sample sample = sampleId == null ? null
                    : sampleCache.computeIfAbsent(sampleId, id -> sampleService.get(id));
            Patient patient = sample == null ? null : sampleHumanService.getPatientForSample(sample);

            boolean finalResultAlreadySent = !documentTrackService
                    .getByTypeRecordAndTable(resultReportId, resultTableId, result.getId()).isEmpty();
            if (finalResultAlreadySent) {
                result.setResultEvent(Event.CORRECTION);
                saveService.getModifiedResults().add(new ResultSet(result, null, null, patient, sample, null, false));
            } else {
                result.setResultEvent(Event.FINAL_RESULT);
                saveService.getNewResults().add(new ResultSet(result, null, null, patient, sample, null, false));
            }
        }

        for (IResultUpdate updater : updaters) {
            updater.transactionalUpdate(saveService);
        }
    }

    /**
     * The controller's FHIR export leg, deferred to afterCommit: the transform is
     * {@code @Async} and re-loads every entity by id in its own read-only
     * transaction, so it must not be dispatched before this transaction's commit is
     * visible. Without an active transaction (plain unit-test calls) there is
     * nothing to wait for and the export runs inline.
     */
    private void scheduleFhirExport(List<Analysis> finalizedAnalyses, ArrayList<Result> finalizedResults,
            ArrayList<Sample> finishedSamples) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    exportFhirObjects(finalizedAnalyses, finalizedResults, finishedSamples);
                }
            });
        } else {
            exportFhirObjects(finalizedAnalyses, finalizedResults, finishedSamples);
        }
    }

    private void exportFhirObjects(List<Analysis> finalizedAnalyses, ArrayList<Result> finalizedResults,
            ArrayList<Sample> finishedSamples) {
        try {
            fhirTransformService.transformPersistResultValidationFhirObjects(new ArrayList<>(), finalizedAnalyses,
                    finalizedResults, new ArrayList<>(), finishedSamples, new ArrayList<>());
        } catch (FhirLocalPersistingException | RuntimeException e) {
            // the analyses are already released — an export failure must be
            // loud in the log (released-but-undelivered is the exact LIS-226
            // failure mode), but it cannot un-release them; same residual
            // behavior as the human path's catch around the same call
            LogEvent.logError(getClass().getSimpleName(), "exportFhirObjects",
                    "FHIR export of " + finalizedAnalyses.size() + " auto-verified analysis(es) FAILED — results are"
                            + " released locally but not exported: " + e.getMessage());
            LogEvent.logError(e);
        }
    }
}
