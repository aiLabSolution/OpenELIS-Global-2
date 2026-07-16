package org.openelisglobal.analyzerresults.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.analyzerresults.dao.AnalyzerResultsDAO;
import org.openelisglobal.analyzerresults.valueholder.AnalyzerResults;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the upsert / dedupe contract in
 * {@link AnalyzerResultsServiceImpl#insertAnalyzerResults}.
 * <p>
 * This is the single persistence method shared by every analyzer import path —
 * legacy plugins (GenericASTM, GenericHL7, GenericFile, per-device plugins) all
 * route through {@code AnalyzerLineInserter.persistImport()} →
 * {@code insertAnalyzerResults}, and the new FHIR bridge path
 * ({@code AnalyzerFhirImportController.importFhirBundle}) calls it directly.
 * The three-case contract this method implements (keyed on
 * {@code (analyzerId, accessionNumber, testName)}) is the authoritative upsert
 * semantics for the whole system, so it gets exercised here rather than being
 * duplicated per import path.
 * </p>
 * <p>
 * Three cases (fail-visible contract, LIS-121):
 * <ol>
 * <li><b>No previous row</b> — fresh insert.</li>
 * <li><b>True re-import</b> — same key AND same completeDate AND same result
 * value: skip silently (idempotent re-POST of the same message).</li>
 * <li><b>Anything else on an existing key</b> — insert the new row as a linked
 * correction ({@code readOnly=true}, {@code duplicateAnalyzerResultId} backlink
 * on both rows) so the tech sees it. A re-run with an equal value on a
 * different date, or a corrected value re-sent with the same completion date,
 * must never be silently dropped — the accession key carries no patient
 * dimension, so a silent skip can lose a different sample's (or patient's)
 * result outright.</li>
 * </ol>
 * This layout matches the plan mellow-honking-cascade Phase 1.6 upsert
 * invariants ({@code sampleAccession}, {@code testCode}, {@code analyzerId}) as
 * the dedupe key.
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyzerResultsServiceImplTest {

    @Mock
    private AnalyzerResultsDAO baseObjectDAO;

    @InjectMocks
    private AnalyzerResultsServiceImpl service;

    private static final String USER_ID = "42";
    private static final String ANALYZER_ID = "208";
    private static final String ACCESSION = "DEV0126000000001";
    private static final String TEST_NAME = "VIH-1";

    private AnalyzerResults newIncoming(String result, Timestamp completeDate) {
        AnalyzerResults ar = new AnalyzerResults();
        ar.setAnalyzerId(ANALYZER_ID);
        ar.setAccessionNumber(ACCESSION);
        ar.setTestName(TEST_NAME);
        ar.setResult(result);
        ar.setCompleteDate(completeDate);
        return ar;
    }

    private AnalyzerResults existingRow(String id, String result, Timestamp completeDate) {
        AnalyzerResults ar = newIncoming(result, completeDate);
        ar.setId(id);
        ar.setLastupdated(new Timestamp(System.currentTimeMillis()));
        return ar;
    }

    @Before
    public void setUp() {
        // insert(T) on the base service delegates to baseObjectDAO.insert(T);
        // return a stable fake id for any invocation.
        when(baseObjectDAO.insert(any(AnalyzerResults.class))).thenReturn("new-id-777");
        when(baseObjectDAO.update(any(AnalyzerResults.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // These tests cover the upsert/dedupe contract; the inherited audit-emit
        // path needs a Spring-wired AuditTrailService + DAO.get(id) stubbing that
        // doesn't apply here. Disable it so save/update stays focused.
        ReflectionTestUtils.setField(service, "auditTrailLog", false);
    }

    // ------------------------------------------------------------------
    // Case 1 — no previous row: fresh insert
    // ------------------------------------------------------------------

    @Test
    public void noPreviousRow_insertsFreshResult() {
        AnalyzerResults incoming = newIncoming("1520.5", new Timestamp(1_700_000_000_000L));
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(null);

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, times(1)).insert(incoming);
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
        assertEquals("sysUserId must be stamped onto the inserted row", USER_ID, incoming.getSysUserId());
        assertFalse("fresh insert should not be flagged read-only", incoming.isReadOnly());
        assertNull("fresh insert must not back-link", incoming.getDuplicateAnalyzerResultId());
    }

    // ------------------------------------------------------------------
    // Case 2 — true re-import (same date AND same value): skip silently
    // ------------------------------------------------------------------

    @Test
    public void reImport_sameCompleteDateAndSameValue_skipsInsertAndUpdate() {
        Timestamp when = new Timestamp(1_700_000_000_000L);
        AnalyzerResults existing = existingRow("42", "1520.5", when);
        AnalyzerResults incoming = newIncoming("1520.5", when); // SAME value, SAME date
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, never()).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
    }

    @Test
    public void correctedValueOnSameCompleteDate_stagesLinkedCorrection() {
        // LIS-121: a corrected value re-sent with the same completion timestamp
        // used to be silently dropped (same-date alone counted as a re-import).
        Timestamp when = new Timestamp(1_700_000_000_000L);
        AnalyzerResults existing = existingRow("42", "1520.5", when);
        AnalyzerResults incoming = newIncoming("9999.9", when); // different value, SAME date
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, times(1)).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, times(1)).update(any(AnalyzerResults.class));
        assertTrue("must surface for review, not vanish", incoming.isReadOnly());
        assertEquals("42", incoming.getDuplicateAnalyzerResultId());
    }

    @Test
    public void sameValueOnDifferentCompleteDate_stagesLinkedCorrection() {
        // LIS-121: the silent-loss scenario — a NEW run whose value happens to
        // equal the previous run's (common for a stable analyte) used to be
        // dropped outright and never reach the tech.
        AnalyzerResults existing = existingRow("42", "1520.5", new Timestamp(1_700_000_000_000L));
        AnalyzerResults incoming = newIncoming("1520.5", new Timestamp(1_800_000_000_000L)); // same value, different
                                                                                             // date
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, times(1)).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, times(1)).update(any(AnalyzerResults.class));
        assertTrue("must surface for review, not vanish", incoming.isReadOnly());
        assertEquals("42", incoming.getDuplicateAnalyzerResultId());
    }

    // ------------------------------------------------------------------
    // LIS-127 — cross-patient safety under a shared sentinel accession.
    // Same defect as LIS-121 (filed twice from the 2026-07-03 review); this
    // anchors the specific walk-up scenario LIS-127 names: two DIFFERENT
    // patients whose results collapse onto the constant "HL7-UNKNOWN"
    // accession (HL7AnalyzerLineInserter derives it when patient+order IDs
    // are blank). Patient B's identical value must never be discarded as an
    // "idempotent re-import" of patient A's — it must stage, linked, so a
    // tech sees both. The two walk-up runs carry distinct completeDate
    // stamps (each import is timestamped separately); asserting on injected
    // timestamps keeps this deterministic. A same-millisecond collision is
    // the residual, structurally-unfixable window (the key has no patient
    // dimension) — tracked separately, not exercised here.
    // ------------------------------------------------------------------
    @Test
    public void twoPatientsSharingSentinelAccession_sameValue_bothStage_neitherDropped() {
        String sentinel = "HL7-UNKNOWN";
        AnalyzerResults patientA = existingRow("patient-A-row", "4.0", new Timestamp(1_700_000_000_000L));
        patientA.setAccessionNumber(sentinel);
        AnalyzerResults patientB = newIncoming("4.0", new Timestamp(1_700_000_300_000L)); // same value, later run
        patientB.setAccessionNumber(sentinel);
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(patientB)).thenReturn(List.of(patientA));

        service.insertAnalyzerResults(List.of(patientB), USER_ID);

        // Patient B's row is staged, not silently dropped…
        verify(baseObjectDAO, times(1)).insert(patientB);
        // …linked to patient A's row so a tech sees both and can disambiguate…
        assertEquals("patient B must back-link to A's staged row, not overwrite it", "patient-A-row",
                patientB.getDuplicateAnalyzerResultId());
        assertTrue("linked correction is flagged for review", patientB.isReadOnly());
        // …and A is updated to point forward at B, so both rows stay reviewable.
        verify(baseObjectDAO, times(1)).update(patientA);
        assertEquals("new-id-777", patientA.getDuplicateAnalyzerResultId());
    }

    // ------------------------------------------------------------------
    // LIS-239 — patient-hint dimension on the idempotency check. Two
    // non-blank hints that differ positively identify DIFFERENT patients:
    // an "identical" arrival (same key, date, value) under a shared sentinel
    // accession must stage as a linked correction, never skip. A null/blank
    // hint on either side carries no signal (pre-migration rows, sources
    // without patient identity) and preserves idempotency.
    // ------------------------------------------------------------------

    @Test
    public void sameDateSameValue_conflictingPatientHints_stagesLinkedCorrection() {
        Timestamp when = new Timestamp(1_700_000_000_000L);
        AnalyzerResults existing = existingRow("patient-A-row", "4.0", when);
        existing.setPatientHint("PAT-A");
        AnalyzerResults incoming = newIncoming("4.0", when); // same value, SAME date
        incoming.setPatientHint("PAT-B");
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, times(1)).insert(incoming);
        verify(baseObjectDAO, times(1)).update(existing);
        assertTrue("a different patient's identical-looking result must surface, not vanish", incoming.isReadOnly());
        assertEquals("patient-A-row", incoming.getDuplicateAnalyzerResultId());
    }

    @Test
    public void sameDateSameValue_equalPatientHints_skipsAsTrueReImport() {
        Timestamp when = new Timestamp(1_700_000_000_000L);
        AnalyzerResults existing = existingRow("42", "4.0", when);
        existing.setPatientHint("PAT-A");
        AnalyzerResults incoming = newIncoming("4.0", when);
        incoming.setPatientHint("PAT-A");
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, never()).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
    }

    @Test
    public void sameDateSameValue_hintOnOneSideOnly_skipsAsTrueReImport() {
        // A pre-migration staged row (null hint) re-POSTed after the bridge
        // started forwarding identities must stay idempotent — one-sided hints
        // carry no mismatch signal.
        Timestamp when = new Timestamp(1_700_000_000_000L);
        AnalyzerResults existing = existingRow("42", "4.0", when); // no hint
        AnalyzerResults incoming = newIncoming("4.0", when);
        incoming.setPatientHint("PAT-A");
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, never()).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
    }

    @Test
    public void patientHintsConflict_blankNeverConflicts() {
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict(null, "PAT-A"));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict("PAT-A", null));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict("", "PAT-A"));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict("  ", "PAT-A"));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict(null, null));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict("PAT-A", "PAT-A"));
        assertTrue(AnalyzerResultsServiceImpl.patientHintsConflict("PAT-A", "PAT-B"));
    }

    @Test
    public void patientHintsConflict_trimsBeforeCompare() {
        // LIS-244: whitespace variance between an original and a re-export is
        // not a patient mismatch. Rows staged before the boundary trim landed
        // can still carry padding, so the predicate trims defensively too.
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict("PAT-A ", "PAT-A"));
        assertFalse(AnalyzerResultsServiceImpl.patientHintsConflict(" PAT-A", "PAT-A "));
        assertTrue(AnalyzerResultsServiceImpl.patientHintsConflict("PAT-A ", " PAT-B"));
    }

    @Test
    public void reImport_bothNullDates_sameValue_skipsAsTrueReImport() {
        // Timestamp-less analyzers: null completeDate on both sides + equal value
        // is the closest thing to an idempotency key the wire offers — skip.
        AnalyzerResults existing = existingRow("42", "1520.5", null);
        AnalyzerResults incoming = newIncoming("1520.5", null);
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, never()).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
    }

    // ------------------------------------------------------------------
    // Case 3 — corrected re-export: insert linked correction row
    // ------------------------------------------------------------------

    @Test
    public void correctedReExport_insertsNewLinkedRow_andBacklinksPrevious() {
        AnalyzerResults existing = existingRow("old-id-42", "1520.5", new Timestamp(1_700_000_000_000L));
        AnalyzerResults incoming = newIncoming("1750.0", new Timestamp(1_800_000_000_000L));
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        // New row is inserted…
        ArgumentCaptor<AnalyzerResults> insertCaptor = ArgumentCaptor.forClass(AnalyzerResults.class);
        verify(baseObjectDAO, times(1)).insert(insertCaptor.capture());
        AnalyzerResults inserted = insertCaptor.getValue();
        assertEquals("new row links back to the old row's id", "old-id-42", inserted.getDuplicateAnalyzerResultId());
        assertTrue("new corrected row is flagged read-only for staging review", inserted.isReadOnly());
        assertEquals(USER_ID, inserted.getSysUserId());

        // …and the previous row is updated to point forward at the new row
        ArgumentCaptor<AnalyzerResults> updateCaptor = ArgumentCaptor.forClass(AnalyzerResults.class);
        verify(baseObjectDAO, times(1)).update(updateCaptor.capture());
        AnalyzerResults updated = updateCaptor.getValue();
        assertEquals("previous row is back-linked to the new row's id", "new-id-777",
                updated.getDuplicateAnalyzerResultId());
        assertEquals(USER_ID, updated.getSysUserId());
    }

    // ------------------------------------------------------------------
    // Null-safety edge case (documents current behavior)
    // ------------------------------------------------------------------

    @Test
    public void reImport_bothCompleteDateAndResultNull_isTreatedAsCorrection() {
        // Pathological case: a previously-staged row with null completeDate AND
        // null result value against an incoming row with real values. Nothing
        // matches, so the incoming row stages as a linked correction — visible,
        // never dropped.
        AnalyzerResults existing = existingRow("old-id-99", null, null);
        AnalyzerResults incoming = newIncoming("new-value", new Timestamp(1_800_000_000_000L));
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(incoming)).thenReturn(List.of(existing));

        service.insertAnalyzerResults(List.of(incoming), USER_ID);

        verify(baseObjectDAO, times(1)).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, times(1)).update(any(AnalyzerResults.class));
    }

    // ------------------------------------------------------------------
    // Multiple results in one batch
    // ------------------------------------------------------------------

    @Test
    public void mixedBatch_eachResultEvaluatedIndependently() {
        AnalyzerResults freshA = newIncoming("100", new Timestamp(1L));
        AnalyzerResults freshB = newIncoming("200", new Timestamp(2L));
        AnalyzerResults dupeC = newIncoming("300", new Timestamp(3L)); // same value, different date
        AnalyzerResults exactD = newIncoming("400", new Timestamp(4L)); // true re-import

        AnalyzerResults existingForC = existingRow("existing-c", "300", new Timestamp(999L));
        AnalyzerResults existingForD = existingRow("existing-d", "400", new Timestamp(4L));

        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(freshA)).thenReturn(null);
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(freshB)).thenReturn(null);
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(dupeC)).thenReturn(List.of(existingForC));
        when(baseObjectDAO.getDuplicateResultByAccessionAndTest(exactD)).thenReturn(List.of(existingForD));

        List<AnalyzerResults> batch = new ArrayList<>();
        Collections.addAll(batch, freshA, freshB, dupeC, exactD);
        service.insertAnalyzerResults(batch, USER_ID);

        // Two fresh inserts + one visible correction for C; the exact re-import D
        // is the only row skipped.
        verify(baseObjectDAO, times(3)).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, times(1)).update(any(AnalyzerResults.class));
        assertTrue(dupeC.isReadOnly());
        assertNull(exactD.getId());
    }

    @Test
    public void emptyBatch_isNoOp() {
        service.insertAnalyzerResults(Collections.emptyList(), USER_ID);
        verify(baseObjectDAO, never()).insert(any(AnalyzerResults.class));
        verify(baseObjectDAO, never()).update(any(AnalyzerResults.class));
    }

    /**
     * A4 — Import Issues panel backend: the service must pass-through to the DAO so
     * the REST endpoint reaches the {@code import_issue_reason IS NOT NULL} query
     * without an extra layer of filtering that could silently drop rows.
     */
    @Test
    public void findWithImportIssues_delegatesToDaoWithSameLimit() {
        AnalyzerResults orphan = new AnalyzerResults();
        orphan.setAccessionNumber("ACC-42");
        orphan.setTestName("CT");
        orphan.setImportIssueReason("unmapped_code:CT");
        when(baseObjectDAO.findWithImportIssues(25)).thenReturn(List.of(orphan));

        List<AnalyzerResults> rows = service.findWithImportIssues(25);
        assertEquals(1, rows.size());
        assertEquals("unmapped_code:CT", rows.get(0).getImportIssueReason());
        verify(baseObjectDAO, times(1)).findWithImportIssues(25);
    }
}
