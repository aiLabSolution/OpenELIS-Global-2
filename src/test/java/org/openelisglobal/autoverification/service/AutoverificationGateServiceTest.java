package org.openelisglobal.autoverification.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;
import org.openelisglobal.common.services.IResultSaveService;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.common.services.StatusService.OrderStatus;
import org.openelisglobal.common.services.registration.interfaces.IResultUpdate;
import org.openelisglobal.common.util.ConfigurationProperties.Property;
import org.openelisglobal.common.util.DefaultConfigurationProperties;
import org.openelisglobal.dataexchange.fhir.service.FhirTransformService;
import org.openelisglobal.dataexchange.orderresult.OrderResponseWorker.Event;
import org.openelisglobal.internationalization.MessageUtil;
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
import org.openelisglobal.referencetables.valueholder.ReferenceTables;
import org.openelisglobal.reports.service.DocumentTrackService;
import org.openelisglobal.reports.service.DocumentTypeService;
import org.openelisglobal.reports.valueholder.DocumentTrack;
import org.openelisglobal.reports.valueholder.DocumentType;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.sampleitem.valueholder.SampleItem;
import org.openelisglobal.spring.util.SpringContext;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit coverage for the autoverification gate's verdict composition (LIS-55 /
 * S5.4): which combinations of reference-range, QC-run and delta-check verdicts
 * release an analysis versus hold it, plus the fail-safe behaviors (disabled
 * flag, unknown analyzer, non-numeric values, non-TA statuses).
 *
 * <p>
 * Persistence-level behavior — that findActiveRejections really excludes
 * WARNING severity and RESOLVED violations, and that held/finalized statuses
 * and notes actually hit the database through the real accept path — is the
 * component test's job ({@code AutoverificationGateComponentTest}).
 */
public class AutoverificationGateServiceTest {

    private static final String TA_STATUS_ID = "17";
    private static final String FINALIZED_STATUS_ID = "25";
    private static final String CANCELED_STATUS_ID = "26";
    private static final String NONCONFORMING_STATUS_ID = "27";
    private static final String ORDER_FINISHED_STATUS_ID = "35";
    private static final String ANALYZER_ID = "2001";
    private static final String TEST_ID = "4001";
    private static final String RESULT_TABLE_ID = "9";
    private static final String RESULT_REPORT_ID = "19";

    private AutoverificationGateServiceImpl gate;
    private AnalysisService analysisService;
    private NoteService noteService;
    private QCRuleViolationService qcRuleViolationService;
    private QCResultDAO qcResultDAO;
    private DeltaCheckService deltaCheckService;
    private IStatusService statusService;
    private jakarta.persistence.EntityManager entityManager;
    private SampleService sampleService;
    private SampleHumanService sampleHumanService;
    private TestNotificationService testNotificationService;
    private FhirTransformService fhirTransformService;
    private DocumentTrackService documentTrackService;
    private ReferenceTablesService referenceTablesService;
    private DocumentTypeService documentTypeService;

    private AutowireCapableBeanFactory previousFactory;
    private Object previousMessageUtilInstance;

    @Before
    public void setUp() throws Exception {
        // Analysis.setReleasedDate resolves a display format through
        // DateUtil -> ConfigurationProperties/MessageUtil, which are backed by
        // the static SpringContext; stand in for them the way
        // BarcodeLabelMakerTest does.
        previousFactory = (AutowireCapableBeanFactory) ReflectionTestUtils.getField(SpringContext.class, "factory");
        previousMessageUtilInstance = ReflectionTestUtils.getField(MessageUtil.class, "instance");

        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);
        DefaultConfigurationProperties configurationProperties = mock(DefaultConfigurationProperties.class);
        MessageSource messageSource = mock(MessageSource.class);
        ReflectionTestUtils.setField(SpringContext.class, "factory", beanFactory);
        when(beanFactory.getBean(DefaultConfigurationProperties.class)).thenReturn(configurationProperties);
        when(configurationProperties.getPropertyValue(any(Property.class))).thenReturn("en");
        when(messageSource.getMessage(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key != null && key.startsWith("date.format")) {
                return "MM/dd/yyyy";
            }
            return inv.getArgument(2);
        });
        MessageUtil.setMessageSource(messageSource);

        gate = new AutoverificationGateServiceImpl();
        analysisService = mock(AnalysisService.class);
        noteService = mock(NoteService.class);
        qcRuleViolationService = mock(QCRuleViolationService.class);
        qcResultDAO = mock(QCResultDAO.class);
        deltaCheckService = mock(DeltaCheckService.class);
        statusService = mock(IStatusService.class);
        entityManager = mock(jakarta.persistence.EntityManager.class);
        sampleService = mock(SampleService.class);
        sampleHumanService = mock(SampleHumanService.class);
        testNotificationService = mock(TestNotificationService.class);
        fhirTransformService = mock(FhirTransformService.class);
        documentTrackService = mock(DocumentTrackService.class);
        referenceTablesService = mock(ReferenceTablesService.class);
        documentTypeService = mock(DocumentTypeService.class);

        inject("analysisService", analysisService);
        inject("noteService", noteService);
        inject("qcRuleViolationService", qcRuleViolationService);
        inject("qcResultDAO", qcResultDAO);
        inject("deltaCheckService", deltaCheckService);
        inject("statusService", statusService);
        inject("entityManager", entityManager);
        inject("sampleService", sampleService);
        inject("sampleHumanService", sampleHumanService);
        inject("testNotificationService", testNotificationService);
        inject("fhirTransformService", fhirTransformService);
        inject("documentTrackService", documentTrackService);
        inject("referenceTablesService", referenceTablesService);
        inject("documentTypeService", documentTypeService);
        // registered updaters resolve through the static
        // ValidationUpdateRegister/ConfigurationProperties pair — stub the seam
        // empty so unit tests opt in per test
        inject("updatersSupplier", (Supplier<List<IResultUpdate>>) Collections::emptyList);
        inject("enabled", true);

        when(statusService.getStatusID(AnalysisStatus.TechnicalAcceptance)).thenReturn(TA_STATUS_ID);
        when(statusService.getStatusID(AnalysisStatus.Finalized)).thenReturn(FINALIZED_STATUS_ID);
        when(statusService.getStatusID(AnalysisStatus.Canceled)).thenReturn(CANCELED_STATUS_ID);
        when(statusService.getStatusID(AnalysisStatus.NonConforming_depricated)).thenReturn(NONCONFORMING_STATUS_ID);
        when(statusService.getStatusID(OrderStatus.Finished)).thenReturn(ORDER_FINISHED_STATUS_ID);
        when(qcRuleViolationService.findActiveRejections(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(qcResultDAO.findPendingByInstrumentAndTest(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(deltaCheckService.evaluate(any(), any())).thenReturn(DeltaCheckVerdict.notEvaluable("not installed"));
        when(noteService.createSavableNote(any(), any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            Note note = new Note();
            note.setText(inv.getArgument(2));
            return note;
        });
    }

    @After
    public void tearDown() {
        ReflectionTestUtils.setField(SpringContext.class, "factory", previousFactory);
        ReflectionTestUtils.setField(MessageUtil.class, "instance", previousMessageUtilInstance);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = AutoverificationGateServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(gate, value);
    }

    private Analysis analysis(String id) {
        Analysis analysis = new Analysis();
        analysis.setId(id);
        analysis.setStatusId(TA_STATUS_ID);
        analysis.setAnalyzerId(ANALYZER_ID);
        org.openelisglobal.test.valueholder.Test test = new org.openelisglobal.test.valueholder.Test();
        test.setId(TEST_ID);
        analysis.setTest(test);
        // parent sample (id "9"+analysisId), reachable while the analysis is
        // still managed — the sample-completion roll-up navigates it
        Sample sample = new Sample();
        sample.setId(sampleIdFor(id));
        SampleItem sampleItem = new SampleItem();
        sampleItem.setSample(sample);
        analysis.setSampleItem(sampleItem);
        when(sampleService.get(sample.getId())).thenReturn(sample);
        // the gate re-loads each analysis by id inside its transaction (the
        // grouping copies are detached with a stale @Version)
        when(analysisService.get(id)).thenReturn(analysis);
        return analysis;
    }

    private String sampleIdFor(String analysisId) {
        return "9" + analysisId;
    }

    private Result persistedResult(String id, String value, Double min, Double max) {
        Result result = numericResult(value, min, max);
        result.setId(id);
        return result;
    }

    private Result numericResult(String value, Double min, Double max) {
        Result result = new Result();
        result.setResultType("N");
        result.setValue(value);
        result.setMinNormal(min);
        result.setMaxNormal(max);
        return result;
    }

    private List<SampleGrouping> grouping(Analysis analysis, Result... results) {
        SampleGrouping grouping = new SampleGrouping();
        grouping.analysisList = new ArrayList<>();
        grouping.resultList = new ArrayList<>();
        for (Result result : results) {
            grouping.analysisList.add(analysis);
            grouping.resultList.add(result);
        }
        return List.of(grouping);
    }

    private QCRuleViolation rejection(String ruleCode, String resolutionStatus) {
        QCRuleViolation violation = new QCRuleViolation();
        violation.setRuleCode(ruleCode);
        violation.setSeverity("REJECTION");
        violation.setResolutionStatus(resolutionStatus);
        violation.setInstrumentId(ANALYZER_ID);
        violation.setTestId(TEST_ID);
        return violation;
    }

    private String heldReason() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(noteService).createSavableNote(any(), eq(NoteType.INTERNAL), text.capture(),
                eq(AutoverificationGateServiceImpl.NOTE_SUBJECT), anyString());
        return text.getValue();
    }

    // ---------------------------------------------------------------
    // Release path
    // ---------------------------------------------------------------

    @Test
    public void cleanNumericResult_autoFinalizesWithSystemNote() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        assertNotNull("releasedDate must be stamped on auto-finalize", analysis.getReleasedDate());
        verify(analysisService).update(analysis);
        String note = heldReason();
        assertTrue("system attribution must be recorded, got: " + note, note.contains("Auto-verified by system"));
    }

    @Test
    public void unconfiguredRange_infiniteBounds_passes() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(
                grouping(analysis, numericResult("999", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)), "7");
        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
    }

    @Test
    public void unconfiguredRange_equalBounds_passes() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("999", 0.0, 0.0)), "7");
        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
    }

    @Test
    public void deltaNotEvaluable_doesNotBlock() {
        // default deltaCheckService stub answers NOT_EVALUABLE
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");
        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
    }

    // ---------------------------------------------------------------
    // Hold paths
    // ---------------------------------------------------------------

    @Test
    public void activeQcRejection_holdsWithRuleCodeInReason() {
        when(qcRuleViolationService.findActiveRejections(ANALYZER_ID, TEST_ID))
                .thenReturn(List.of(rejection("1_3s", "UNRESOLVED")));

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals("held analysis must stay at TechnicalAcceptance", TA_STATUS_ID, analysis.getStatusId());
        assertNull(analysis.getReleasedDate());
        verify(analysisService, never()).update(any());
        String note = heldReason();
        assertTrue("hold reason must name the violated rule, got: " + note, note.contains("1_3s"));
        assertTrue(note.contains("QC out of control"));
    }

    @Test
    public void acknowledgedQcRejection_stillHolds() {
        // findActiveRejections includes ACKNOWLEDGED rows by contract; the gate
        // must hold on them (acknowledging an alert is not clearing the
        // instrument).
        when(qcRuleViolationService.findActiveRejections(ANALYZER_ID, TEST_ID))
                .thenReturn(List.of(rejection("2_2s", "ACKNOWLEDGED")));

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("ACKNOWLEDGED"));
    }

    @Test
    public void outOfRangeHigh_holdsWithRangeReason() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("75", 40.0, 60.0)), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        String note = heldReason();
        assertTrue("hold reason must record the range failure, got: " + note,
                note.contains("out of reference range") && note.contains("above"));
    }

    @Test
    public void outOfRangeLow_holdsWithRangeReason() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("12", 40.0, 60.0)), "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("below"));
    }

    @Test
    public void deltaFlagged_holdsWithDeltaReason() {
        when(deltaCheckService.evaluate(any(), any()))
                .thenReturn(DeltaCheckVerdict.flagged("change 4.1 -> 7.1 exceeds 20% threshold"));

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        String note = heldReason();
        assertTrue("hold reason must carry the delta engine's reason, got: " + note,
                note.contains("delta check flagged") && note.contains("exceeds 20% threshold"));
    }

    @Test
    public void nonNumericResultType_holdsFailClosed() {
        Analysis analysis = analysis("100");
        Result dictionary = new Result();
        dictionary.setResultType("D");
        dictionary.setValue("120");

        gate.evaluateAndFinalize(grouping(analysis, dictionary), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("cannot be auto-verified"));
    }

    @Test
    public void unparseableNumericValue_holdsFailClosed() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("QNS", 40.0, 60.0)), "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("cannot be evaluated"));
    }

    @Test
    public void nanValue_holdsFailClosed() {
        // Double.parseDouble("NaN") succeeds and NaN comparisons are all false
        // — without an explicit finiteness guard this auto-released (review
        // P1). Must hold.
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("NaN", 40.0, 60.0)), "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        verify(analysisService, never()).update(any());
        assertTrue(heldReason().contains("non-finite"));
    }

    @Test
    public void infinityValue_holdsFailClosed_evenWithUnconfiguredRange() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(
                grouping(analysis, numericResult("Infinity", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)), "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("non-finite"));
    }

    @Test
    public void pendingQcEvaluation_holdsFailClosed() {
        // a QC run whose async Westgard evaluation has not finished is not yet
        // known to be in control — the gate must not release against it
        when(qcResultDAO.findPendingByInstrumentAndTest(ANALYZER_ID, TEST_ID)).thenReturn(List.of(new QCResult()));

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("QC evaluation pending"));
    }

    @Test
    public void autoVerifyNote_recordsActualLegOutcomes() {
        // the release note must record what actually ran — not claim "delta
        // check PASS" while the delta engine is not installed (review P2)
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        String note = heldReason();
        assertTrue("QC leg record expected, got: " + note, note.contains("QC: no active REJECTION violation"));
        assertTrue("range leg record expected, got: " + note, note.contains("range: 50 within"));
        assertTrue("delta leg must be recorded as not evaluable, got: " + note, note.contains("delta: not evaluable"));
    }

    @Test
    public void sameAnalysisId_distinctDetachedInstances_decidedOnce() {
        // the accept path materializes the same DB analysis as distinct
        // detached instances — grouping must key on id, not object identity,
        // or a passing duplicate copy could finalize a row whose other result
        // failed a leg (review P2)
        Analysis first = analysis("100");
        Analysis second = new Analysis();
        second.setId("100");
        second.setStatusId(TA_STATUS_ID);
        second.setAnalyzerId(ANALYZER_ID);
        org.openelisglobal.test.valueholder.Test test = new org.openelisglobal.test.valueholder.Test();
        test.setId(TEST_ID);
        second.setTest(test);

        SampleGrouping grouping = new SampleGrouping();
        grouping.analysisList = new ArrayList<>(List.of(first, second));
        grouping.resultList = new ArrayList<>(
                List.of(numericResult("50", 40.0, 60.0), numericResult("75", 40.0, 60.0)));

        gate.evaluateAndFinalize(List.of(grouping), "7");

        assertEquals("one bad result must hold the whole analysis", TA_STATUS_ID, first.getStatusId());
        verify(analysisService, never()).update(any());
        verify(noteService).createSavableNote(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    public void analysisIsDetachedBeforeMutation_soAuditDiffSeesTheTransition() {
        // AuditableBaseObjectServiceImpl.update() diffs the entity against a
        // freshly loaded "old" copy; a still-managed instance is compared
        // against itself (Hibernate L1 cache) and the Finalized transition
        // never reaches the history table. The harness mocks
        // AuditTrailService, so this is the only guard: detach must happen
        // while the entity still holds its PRE-mutation state, before update.
        Analysis analysis = analysis("100");
        List<String> statusAtDetach = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            statusAtDetach.add(((Analysis) inv.getArgument(0)).getStatusId());
            return null;
        }).when(entityManager).detach(any(Analysis.class));

        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        assertEquals("detach must be called exactly once for the analysis", 1, statusAtDetach.size());
        assertEquals("detach must happen while the entity still holds its pre-mutation status", TA_STATUS_ID,
                statusAtDetach.get(0));
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(entityManager, analysisService);
        inOrder.verify(entityManager).detach(analysis);
        inOrder.verify(analysisService).update(analysis);
    }

    @Test
    public void oneUnloadableAnalysis_doesNotAbortTheRestOfTheBatch() {
        Analysis good = analysis("100");
        Analysis missing = analysis("200");
        when(analysisService.get("200")).thenThrow(new RuntimeException("ObjectNotFound"));

        SampleGrouping grouping = new SampleGrouping();
        grouping.analysisList = new ArrayList<>(List.of(missing, good));
        grouping.resultList = new ArrayList<>(
                List.of(numericResult("50", 40.0, 60.0), numericResult("50", 40.0, 60.0)));

        gate.evaluateAndFinalize(List.of(grouping), "7");

        assertEquals("the loadable analysis must still be decided", FINALIZED_STATUS_ID, good.getStatusId());
        verify(analysisService).update(good);
    }

    @Test
    public void unpersistedAnalysis_isSkippedNotFinalized() {
        Analysis analysis = analysis("100");
        analysis.setId(null);
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        verify(analysisService, never()).update(any());
        verify(noteService, never()).createSavableNote(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    public void missingAnalyzerId_holdsFailClosed() {
        Analysis analysis = analysis("100");
        analysis.setAnalyzerId(null);
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        assertTrue(heldReason().contains("QC status cannot be established"));
    }

    @Test
    public void oneBadResultHoldsTheWholeAnalysis() {
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0), numericResult("75", 40.0, 60.0)),
                "7");
        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        verify(analysisService, never()).update(any());
    }

    // ---------------------------------------------------------------
    // Fail-safe / scope guards
    // ---------------------------------------------------------------

    @Test
    public void disabledFlag_isCompleteNoOp() throws Exception {
        inject("enabled", false);
        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");

        assertEquals(TA_STATUS_ID, analysis.getStatusId());
        verify(analysisService, never()).update(any());
        verify(noteService, never()).createSavableNote(any(), any(), anyString(), anyString(), anyString());
        verify(qcRuleViolationService, never()).findActiveRejections(anyString(), anyString());
        verify(deltaCheckService, never()).evaluate(any(), any());
    }

    @Test
    public void nonTechnicalAcceptanceAnalysis_isLeftUntouched() {
        Analysis rejected = analysis("100");
        rejected.setStatusId("99"); // e.g. TechnicalRejected
        gate.evaluateAndFinalize(grouping(rejected, numericResult("50", 40.0, 60.0)), "7");

        assertEquals("99", rejected.getStatusId());
        verify(analysisService, never()).update(any());
        verify(noteService, never()).createSavableNote(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    public void finalizeStampsReleasedDateCloseToNow() {
        Analysis analysis = analysis("100");
        long before = System.currentTimeMillis();
        gate.evaluateAndFinalize(grouping(analysis, numericResult("50", 40.0, 60.0)), "7");
        long after = System.currentTimeMillis();

        Timestamp released = analysis.getReleasedDate();
        assertNotNull(released);
        assertTrue("releasedDate must be stamped at decision time",
                released.getTime() >= before && released.getTime() <= after);
    }

    // ---------------------------------------------------------------
    // Release side effects (LIS-226) — parity with the human validation
    // path (ResultValidationServiceImpl.persistdata +
    // AccessionValidationRestController)
    // ---------------------------------------------------------------

    @Test
    public void autoFinalize_sendsResultValidationNotificationForThePersistedResult() {
        Analysis analysis = analysis("100");
        Result result = persistedResult("601", "50", 40.0, 60.0);

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        verify(testNotificationService)
                .createAndSendNotificationsToConfiguredSources(NotificationNature.RESULT_VALIDATION, result);
        org.junit.Assert.assertSame("the notified result must carry the reloaded analysis, not the stale grouping copy",
                analysis, result.getAnalysis());
    }

    @Test
    public void duplicateDetachedCopiesOfOnePersistedResult_notifyOnlyOnce() {
        // the same persisted result row arriving as two detached copies (same
        // analysis at two grouping indices) must not double-notify — the FHIR
        // transform dedups by composite key, the notification leg must too
        Analysis analysis = analysis("100");
        Result firstCopy = persistedResult("601", "50", 40.0, 60.0);
        Result secondCopy = persistedResult("601", "50", 40.0, 60.0);

        gate.evaluateAndFinalize(grouping(analysis, firstCopy, secondCopy), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        verify(testNotificationService).createAndSendNotificationsToConfiguredSources(
                eq(NotificationNature.RESULT_VALIDATION), any(Result.class));
    }

    @Test
    public void autoFinalize_exportsFhirValidationObjectsForAnalysisAndResult() throws Exception {
        Analysis analysis = analysis("100");
        Result result = persistedResult("601", "50", 40.0, 60.0);

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Result>> deletable = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Analysis>> analyses = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArrayList<Result>> results = ArgumentCaptor.forClass(ArrayList.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArrayList<Sample>> samples = ArgumentCaptor.forClass(ArrayList.class);
        verify(fhirTransformService).transformPersistResultValidationFhirObjects(deletable.capture(),
                analyses.capture(), results.capture(), any(), samples.capture(), any());

        assertTrue("gate never cancels observations", deletable.getValue().isEmpty());
        assertEquals(List.of(analysis), analyses.getValue());
        assertEquals("analysis must be exported as Finalized (DiagnosticReport leg)", FINALIZED_STATUS_ID,
                analyses.getValue().get(0).getStatusId());
        assertEquals(List.of(result), results.getValue());
        assertTrue("no sample finished in this scenario", samples.getValue().isEmpty());
    }

    @Test
    public void fhirExportIsDeferredUntilAfterTransactionCommit() throws Exception {
        // transformPersistResultValidationFhirObjects is @Async and re-loads
        // every entity by id in its own read-only transaction — dispatched
        // before the gate's transaction commits it would race the commit and
        // see the pre-release state. The human path orders this by calling the
        // transform after persistdata returns; the gate must defer to
        // afterCommit.
        TransactionSynchronizationManager.initSynchronization();
        try {
            Analysis analysis = analysis("100");
            Result result = persistedResult("601", "50", 40.0, 60.0);
            gate.evaluateAndFinalize(grouping(analysis, result), "7");

            verify(fhirTransformService, never()).transformPersistResultValidationFhirObjects(any(), any(), any(),
                    any(), any(), any());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(fhirTransformService).transformPersistResultValidationFhirObjects(any(), any(), any(), any(), any(),
                    any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void lastOpenAnalysisFinalized_rollsParentSampleToFinished_andExportsIt() throws Exception {
        Analysis analysis = analysis("100");
        Result result = persistedResult("601", "50", 40.0, 60.0);
        // by roll-up time the (only) sibling list holds the just-finalized row
        when(analysisService.getAnalysesBySampleId(sampleIdFor("100"))).thenReturn(List.of(analysis));

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        Sample sample = sampleService.get(sampleIdFor("100"));
        assertEquals("parent sample must roll to OrderStatus.Finished", ORDER_FINISHED_STATUS_ID, sample.getStatusId());
        verify(sampleService).update(sample);
        // same audit idiom as the analysis: detach before mutation so the
        // audit diff sees the Started -> Finished transition
        verify(entityManager).detach(sample);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArrayList<Sample>> samples = ArgumentCaptor.forClass(ArrayList.class);
        verify(fhirTransformService).transformPersistResultValidationFhirObjects(any(), any(), any(), any(),
                samples.capture(), any());
        assertEquals("finished sample must ride the FHIR export (Task/ServiceRequest leg)", List.of(sample),
                samples.getValue());
    }

    @Test
    public void openSiblingAnalysis_leavesParentSampleUnfinished() {
        Analysis analysis = analysis("100");
        Analysis openSibling = new Analysis();
        openSibling.setId("101");
        openSibling.setStatusId(TA_STATUS_ID);
        when(analysisService.getAnalysesBySampleId(sampleIdFor("100"))).thenReturn(List.of(analysis, openSibling));

        gate.evaluateAndFinalize(grouping(analysis, persistedResult("601", "50", 40.0, 60.0)), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        verify(sampleService, never()).update(any());
    }

    @Test
    public void canceledSibling_countsAsTerminal_soSampleStillRolls() {
        Analysis analysis = analysis("100");
        Analysis canceledSibling = new Analysis();
        canceledSibling.setId("101");
        canceledSibling.setStatusId(CANCELED_STATUS_ID);
        when(analysisService.getAnalysesBySampleId(sampleIdFor("100"))).thenReturn(List.of(analysis, canceledSibling));

        gate.evaluateAndFinalize(grouping(analysis, persistedResult("601", "50", 40.0, 60.0)), "7");

        verify(sampleService).update(sampleService.get(sampleIdFor("100")));
    }

    @Test
    public void heldAnalysis_firesNoReleaseSideEffects() throws Exception {
        when(qcRuleViolationService.findActiveRejections(ANALYZER_ID, TEST_ID))
                .thenReturn(List.of(rejection("1_3s", "UNRESOLVED")));

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, persistedResult("601", "50", 40.0, 60.0)), "7");

        verify(testNotificationService, never()).createAndSendNotificationsToConfiguredSources(any(), any());
        verify(fhirTransformService, never()).transformPersistResultValidationFhirObjects(any(), any(), any(), any(),
                any(), any());
        verify(sampleService, never()).update(any());
    }

    @Test
    public void notificationFailure_isLoggedButDoesNotBlockReleaseOrExport() throws Exception {
        // mirror of the human path's per-result try/catch in persistdata
        org.mockito.Mockito.doThrow(new RuntimeException("notification transport down")).when(testNotificationService)
                .createAndSendNotificationsToConfiguredSources(any(), any());

        Analysis analysis = analysis("100");
        gate.evaluateAndFinalize(grouping(analysis, persistedResult("601", "50", 40.0, 60.0)), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        verify(analysisService).update(analysis);
        verify(fhirTransformService).transformPersistResultValidationFhirObjects(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    public void unpersistedResult_isExcludedFromNotificationAndExport_butAnalysisStillReleases() throws Exception {
        Analysis analysis = analysis("100");
        Result result = numericResult("50", 40.0, 60.0); // no id — cannot be re-loaded downstream

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        assertEquals(FINALIZED_STATUS_ID, analysis.getStatusId());
        verify(testNotificationService, never()).createAndSendNotificationsToConfiguredSources(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ArrayList<Result>> results = ArgumentCaptor.forClass(ArrayList.class);
        verify(fhirTransformService).transformPersistResultValidationFhirObjects(any(), any(), results.capture(), any(),
                any(), any());
        assertTrue("id-less results cannot ride the export", results.getValue().isEmpty());
    }

    @Test
    public void registeredUpdaters_receiveTransactionalUpdate_butNeverTheDisabledPostCommitHook() throws Exception {
        IResultUpdate updater = mock(IResultUpdate.class);
        inject("updatersSupplier", (Supplier<List<IResultUpdate>>) () -> List.of(updater));
        stubDocumentTrackLookups();

        Analysis analysis = analysis("100");
        Result result = persistedResult("601", "50", 40.0, 60.0);
        Patient patient = new Patient();
        when(sampleHumanService.getPatientForSample(sampleService.get(sampleIdFor("100")))).thenReturn(patient);

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        ArgumentCaptor<IResultSaveService> saveService = ArgumentCaptor.forClass(IResultSaveService.class);
        verify(updater).transactionalUpdate(saveService.capture());
        assertEquals("never-reported result must be classified as new", 1,
                saveService.getValue().getNewResults().size());
        assertTrue(saveService.getValue().getModifiedResults().isEmpty());
        org.junit.Assert.assertSame(result, saveService.getValue().getNewResults().get(0).result);
        org.junit.Assert.assertSame(patient, saveService.getValue().getNewResults().get(0).patient);
        org.junit.Assert.assertSame(sampleService.get(sampleIdFor("100")),
                saveService.getValue().getNewResults().get(0).sample);
        assertEquals(Event.FINAL_RESULT, result.getResultEvent());
        // the human path's postTransactionalCommitUpdate call is disabled
        // (commented out upstream) — parity means never invoking it here either
        verify(updater, never()).postTransactionalCommitUpdate(any());
    }

    @Test
    public void alreadyReportedResult_isClassifiedAsCorrectionForUpdaters() throws Exception {
        IResultUpdate updater = mock(IResultUpdate.class);
        inject("updatersSupplier", (Supplier<List<IResultUpdate>>) () -> List.of(updater));
        stubDocumentTrackLookups();
        when(documentTrackService.getByTypeRecordAndTable(RESULT_REPORT_ID, RESULT_TABLE_ID, "601"))
                .thenReturn(List.of(new DocumentTrack()));

        Analysis analysis = analysis("100");
        Result result = persistedResult("601", "50", 40.0, 60.0);

        gate.evaluateAndFinalize(grouping(analysis, result), "7");

        ArgumentCaptor<IResultSaveService> saveService = ArgumentCaptor.forClass(IResultSaveService.class);
        verify(updater).transactionalUpdate(saveService.capture());
        assertTrue(saveService.getValue().getNewResults().isEmpty());
        assertEquals(1, saveService.getValue().getModifiedResults().size());
        assertEquals(Event.CORRECTION, result.getResultEvent());
    }

    private void stubDocumentTrackLookups() {
        ReferenceTables resultTable = new ReferenceTables();
        resultTable.setId(RESULT_TABLE_ID);
        when(referenceTablesService.getReferenceTableByName("RESULT")).thenReturn(resultTable);
        DocumentType reportType = new DocumentType();
        reportType.setId(RESULT_REPORT_ID);
        when(documentTypeService.getDocumentTypeByName("resultExport")).thenReturn(reportType);
    }

    @Test
    public void fhirTransformServiceInjection_mustStayLazy_orProductionContextCannotBoot() throws Exception {
        // Tripwire, not a boot test: fhirTransformServiceImpl is in a circular
        // reference with fhirReferralServiceImpl and gets @Async-proxied after
        // creation. An eager injection here pulls it into existence mid-cycle and
        // Spring aborts startup ("injected in its raw version ... eventually
        // wrapped") — every test context mocks FhirTransformService, so only the
        // deploy-kit clean-box smoke gate boots the real graph. Keep @Lazy.
        Field field = AutoverificationGateServiceImpl.class.getDeclaredField("fhirTransformService");
        assertTrue("fhirTransformService must be injected @Lazy — removing it breaks production context boot",
                field.isAnnotationPresent(org.springframework.context.annotation.Lazy.class));
    }
}
