package org.openelisglobal.analyzerresults.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzerresults.action.beanitems.AnalyzerResultItem;
import org.openelisglobal.analyzerresults.valueholder.AnalyzerResults;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-158: patient-safety coverage for the linked-correction accept flow.
 *
 * <p>
 * When an analyzer re-sends a corrected result, {@code insertAnalyzerResults}
 * stages the new arrival as a readOnly linked correction
 * ({@code readOnly=true}, {@code duplicateAnalyzerResultId} back-linking the
 * original) rather than silently overwriting or dropping either row (LIS-121).
 * Before this fix, accept propagated the group's accept/reject/delete flag onto
 * the readOnly correction, marked it deletable, but excluded it from
 * persistence ({@code buildSampleGroupings}'s {@code !isReadOnly()} guard) — so
 * the correction vanished from staging with no record and the original
 * (possibly-wrong) value is what got reported.
 * </p>
 *
 * <p>
 * These tests anchor the fix directly against
 * {@link AnalyzerResultsAcceptServiceImpl} (not just the public
 * {@code acceptAndPersist} entry point) so the seams —
 * {@code hydrateStagingFlags} and {@code resolveLinkedCorrections} — are each
 * independently verifiable. Every test here fails on the pre-fix code (neither
 * seam existed, and an unresolved correction was silently dropped instead of
 * blocking accept).
 * </p>
 */
public class AnalyzerResultsAcceptServiceImplTest extends BaseWebContextSensitiveTest {

    @Autowired
    private AnalyzerResultsAcceptServiceImpl acceptService;

    @Autowired
    private AnalyzerResultsService analyzerResultsService;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/analyzer-results-correction.xml");
    }

    private AnalyzerResultItem buildItem(String id, String result, boolean readOnly, String duplicateId, String testId,
            String accessionNumber, String testName, int sampleGroupingNumber, boolean accepted, boolean rejected,
            boolean deleted) {
        AnalyzerResultItem item = new AnalyzerResultItem();
        item.setId(id);
        item.setResult(result);
        item.setReadOnly(readOnly);
        item.setDuplicateAnalyzerResultId(duplicateId);
        item.setTestId(testId);
        item.setAccessionNumber(accessionNumber);
        item.setTestName(testName);
        item.setSampleGroupingNumber(sampleGroupingNumber);
        item.setIsAccepted(accepted);
        item.setIsRejected(rejected);
        item.setIsDeleted(deleted);
        item.setIsControl(false);
        item.setTestResultType("N");
        item.setCompleteDate("2025-07-03 09:00:00");
        return item;
    }

    // The editable original: K+ 4.1, linked forward to the 7.1 correction.
    private AnalyzerResultItem originalItem(boolean accepted, boolean rejected, boolean deleted) {
        return buildItem("1010", "4.1", false, "1011", "4001", "CORR100", "Potassium", 1, accepted, rejected, deleted);
    }

    // The analyzer-sent correction: K+ 7.1, linked back to the 4.1 original.
    // Same calendar day as the original (2025-07-01), later time — a genuine
    // analyzer corrected re-export.
    private AnalyzerResultItem correctionItem() {
        return buildItem("1011", "7.1", true, "1010", "4001", "CORR100", "Potassium", 1, false, false, false);
    }

    // LIS-128 reused-accession scenario: day-1 CRP=5 stages writable, linked
    // forward to a day-2 CRP=40 "correction" under the SAME accession number.
    // The cross-day gap is the reused-accession signature, not a genuine
    // analyzer correction. Posted completeDate uses the display format the GET
    // flow feeds back (MM/dd/yyyy) — the persist path parses it with
    // DateUtil.getDateFormat(), so an unparseable string would abort accept for
    // the wrong reason and mask the defect under test.
    private AnalyzerResultItem reusedAccessionOriginalItem(boolean accepted, boolean rejected, boolean deleted) {
        AnalyzerResultItem item = buildItem("1013", "5", false, "1014", "4001", "REUSE300", "CRP", 1, accepted,
                rejected, deleted);
        item.setCompleteDate("07/01/2025");
        return item;
    }

    private AnalyzerResultItem reusedAccessionCorrectionItem() {
        AnalyzerResultItem item = buildItem("1014", "40", true, "1013", "4001", "REUSE300", "CRP", 1, false, false,
                false);
        item.setCompleteDate("07/02/2025");
        return item;
    }

    @Test
    public void acceptGroupWithUnresolvedCorrection_throwsAndLeavesStagingIntact() {
        AnalyzerResultItem original = originalItem(true, false, false);
        AnalyzerResultItem correction = correctionItem();
        // no correctionAction set on the correction — the tech never decided

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(original, correction));

        UnresolvedCorrectionException ex = assertThrows(UnresolvedCorrectionException.class,
                () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
        assertTrue("message must name the blocked accession", ex.getMessage().contains("CORR100"));

        assertNotNull("original must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1010"));
        assertNotNull("correction must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1011"));
    }

    @Test
    public void useCorrection_substitutesValueAndNotesOnPartner() {
        AnalyzerResultItem original = originalItem(true, false, false);
        AnalyzerResultItem correction = correctionItem();
        correction.setCorrectionAction("USE");

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        acceptService.resolveLinkedCorrections(items);

        assertEquals("USE must substitute the corrected value onto the editable partner", "7.1", original.getResult());
        assertTrue("partner note must record both the discarded and applied values",
                original.getNote() != null && original.getNote().contains("4.1") && original.getNote().contains("7.1"));
        assertTrue("the correction row itself stays readOnly — it never becomes a second reportable row",
                correction.isReadOnly());
        assertEquals("selected correction raw code must follow its value", "K-CORRECTED", original.getRawCode());
        assertEquals("selected correction raw unit must follow its value", "mEq/L", original.getRawUnit());
        assertEquals("selected correction LOINC must follow its value", "2823-3", original.getLoinc());
        assertNull("selected correction has no UCUM value", original.getUcumValue());
        assertEquals("selected correction status must follow its value", "PARTIAL",
                original.getNormalizationStatus());
    }

    @Test
    public void dismissCorrection_keepsOriginalAndNotes() {
        AnalyzerResultItem original = originalItem(true, false, false);
        AnalyzerResultItem correction = correctionItem();
        correction.setCorrectionAction("DISMISS");

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        acceptService.resolveLinkedCorrections(items);

        assertEquals("DISMISS must keep the original value unchanged", "4.1", original.getResult());
        assertTrue("partner note must record the dismissed corrected value",
                original.getNote() != null && original.getNote().contains("7.1"));
    }

    @Test
    public void rejectGroupWithCorrection_notBlocked() {
        AnalyzerResultItem original = originalItem(false, true, false);
        AnalyzerResultItem correction = correctionItem();
        // no correctionAction — rejection must never be blocked by an unresolved
        // correction, only accept is fail-closed

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        acceptService.resolveLinkedCorrections(items);

        assertTrue("rejected group must auto-note the discarded correction on the partner",
                original.getNote() != null && original.getNote().contains("7.1"));
    }

    @Test
    public void deleteGroupWithCorrection_notBlocked() {
        AnalyzerResultItem original = originalItem(false, false, true);
        AnalyzerResultItem correction = correctionItem();

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        // must not throw — deletions are audit-trailed, nothing is reported either way
        acceptService.resolveLinkedCorrections(items);
    }

    @Test
    public void useOnUnmappedReadOnlyRow_isRejected() {
        // A correction-shaped row that never resolved to a mapped test (testId
        // null) cannot be USEd — there is nothing to accept it as.
        AnalyzerResultItem unmappedCorrection = buildItem("9001", "99", true, "9000", null, "MISS200", "Unmapped", 42,
                true, false, false);
        unmappedCorrection.setCorrectionAction("USE");

        List<AnalyzerResultItem> items = List.of(unmappedCorrection);

        assertThrows(UnresolvedCorrectionException.class, () -> acceptService.resolveLinkedCorrections(items));
    }

    @Test
    public void missingMappingRow_withoutDupId_doesNotBlock() {
        // id=1012 in the fixture is a readOnly row with NO testId and NO
        // duplicate_id — a missing-mapping row, not a linked correction — grouped
        // alongside a normal mapped/accepted row.
        AnalyzerResultItem mapped = originalItem(true, false, false);
        AnalyzerResultItem missingMapping = buildItem("1012", "99", true, null, null, "MISS200", "Unmapped", 1, true,
                false, false);

        List<AnalyzerResultItem> items = List.of(mapped, missingMapping);
        acceptService.hydrateStagingFlags(items);
        // must not throw — a missing-mapping row is never a linked correction
        acceptService.resolveLinkedCorrections(items);
    }

    @Test
    public void clientPostedReadOnlyFalse_overriddenByHydration() {
        // Simulate a tampered/stale POST: the client claims the correction row is
        // no longer readOnly. AnalyzerResultsPaging.updateCache replaces cache
        // items wholesale from the client payload, so this must be corrected from
        // the DB before accept, not trusted.
        AnalyzerResultItem original = originalItem(true, false, false);
        AnalyzerResultItem correction = correctionItem();
        correction.setReadOnly(false);
        // no correctionAction set

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(original, correction));

        assertThrows("hydration must restore readOnly from the DB, still blocking accept",
                UnresolvedCorrectionException.class, () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
    }

    @Test
    public void clientPostedNormalizationProvenance_isOverwrittenByHydration() {
        AnalyzerResultItem correction = correctionItem();
        correction.setRawCode("FORGED");
        correction.setRawUnit("FORGED");
        correction.setLoinc("FORGED");
        correction.setUcumValue("FORGED");
        correction.setNormalizationStatus("FORGED");

        acceptService.hydrateStagingFlags(List.of(correction));

        assertEquals("K-CORRECTED", correction.getRawCode());
        assertEquals("mEq/L", correction.getRawUnit());
        assertEquals("2823-3", correction.getLoinc());
        assertNull(correction.getUcumValue());
        assertEquals("PARTIAL", correction.getNormalizationStatus());
    }

    @Test
    public void useCorrection_marksBothStagingRowsForDeletionAndKeepsPartnerReportable() {
        AnalyzerResultItem original = originalItem(true, false, false);
        AnalyzerResultItem correction = correctionItem();
        correction.setCorrectionAction("USE");

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        acceptService.resolveLinkedCorrections(items);

        // the substituted partner stays editable, so buildSampleGroupings persists it
        // (its !isReadOnly() guard keeps readOnly rows out) — carrying the 7.1 value
        assertEquals("7.1", original.getResult());
        assertTrue("partner must remain reportable (non-readOnly) so it persists", !original.isReadOnly());
        assertTrue("the correction row stays readOnly — never a second reportable row", correction.isReadOnly());

        // both staging rows must be removed: no orphaned correction, no stale original
        List<AnalyzerResults> removable = acceptService.getRemovableAnalyzerResults(items, new ArrayList<>());
        Set<String> removedIds = removable.stream().map(AnalyzerResults::getId).collect(Collectors.toSet());
        assertTrue("original staging row 1010 must be deleted", removedIds.contains("1010"));
        assertTrue("correction staging row 1011 must be deleted", removedIds.contains("1011"));
    }

    @Test
    public void useCorrection_sharedTestName_substitutesOntoCorrectRow() {
        // H99S shared-name config: two analyzer test names map to one OE testId (4001)
        // in one accession. A correction for the second name must land on the second
        // original, not the first testId-4001 row — pairing is by testName, not testId.
        AnalyzerResultItem originalA = buildItem("2010", "1.0", false, null, "4001", "CORR200", "K-A", 1, true, false,
                false);
        AnalyzerResultItem originalB = buildItem("2011", "2.0", false, "2012", "4001", "CORR200", "K-B", 1, true, false,
                false);
        AnalyzerResultItem correctionB = buildItem("2012", "9.9", true, "2011", "4001", "CORR200", "K-B", 1, false,
                false, false);
        correctionB.setCorrectionAction("USE");

        List<AnalyzerResultItem> items = List.of(originalA, originalB, correctionB);
        // resolve directly on the beans: readOnly is set explicitly, no DB row needed
        acceptService.resolveLinkedCorrections(items);

        assertEquals("correction must land on the same-named original", "9.9", originalB.getResult());
        assertEquals("the other testId-4001 row must be untouched", "1.0", originalA.getResult());
    }

    // ---------------------------------------------------------------
    // LIS-128: cross-day linked-correction guard (reused-accession gate)
    // ---------------------------------------------------------------
    //
    // A linked correction pair whose two staging rows' completeDate fall on
    // different calendar days is the reused-accession signature (a genuine
    // analyzer corrected re-export lands same-day). USE must refuse
    // fail-closed; DISMISS — the safe path that never substitutes a value —
    // remains available.

    @Test
    public void useCrossDayCorrection_reusedAccession_blocksFailClosedAndLeavesStagingIntact() {
        AnalyzerResultItem original = reusedAccessionOriginalItem(true, false, false);
        AnalyzerResultItem correction = reusedAccessionCorrectionItem();
        correction.setCorrectionAction("USE");
        // REUSE300 has no registered sample/order, so it independently trips the
        // LIS-126 unmatched-sample gate (gateUnmatchedSampleGroups), which runs
        // AFTER resolveLinkedCorrections in acceptAndPersist. Confirm past that
        // orthogonal gate so this test actually exercises — and, pre-fix, would
        // silently fall through — the cross-day guard under test.
        original.setUnmatchedAction("ACCEPT_UNKNOWN");

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(original, correction));

        UnresolvedCorrectionException ex = assertThrows(UnresolvedCorrectionException.class,
                () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
        assertTrue("message must name the blocked accession", ex.getMessage().contains("REUSE300"));

        assertNotNull("original must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1013"));
        assertNotNull("correction must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1014"));
    }

    @Test
    public void dismissCrossDayCorrection_commitsOriginalAndNotes() {
        AnalyzerResultItem original = reusedAccessionOriginalItem(true, false, false);
        AnalyzerResultItem correction = reusedAccessionCorrectionItem();
        correction.setCorrectionAction("DISMISS");

        List<AnalyzerResultItem> items = List.of(original, correction);
        acceptService.hydrateStagingFlags(items);
        // must not throw — DISMISS stays available even for a cross-day pair
        acceptService.resolveLinkedCorrections(items);

        assertEquals("DISMISS must keep the original value unchanged", "5", original.getResult());
        assertTrue("partner note must record the dismissed corrected value",
                original.getNote() != null && original.getNote().contains("40"));

        // both staging rows are still marked for removal — DISMISS commits the
        // original and discards the correction, same as the same-day case
        List<AnalyzerResults> removable = acceptService.getRemovableAnalyzerResults(items, new ArrayList<>());
        Set<String> removedIds = removable.stream().map(AnalyzerResults::getId).collect(Collectors.toSet());
        assertTrue("original staging row 1013 must be deleted", removedIds.contains("1013"));
        assertTrue("correction staging row 1014 must be deleted", removedIds.contains("1014"));
    }

    @Test
    public void clientPostedCompleteDate_cannotDefeatCrossDayBlock() {
        // Simulate a tampered/stale POST: the client posts the SAME day for
        // both rows, on both date fields. The REAL vector is
        // stagingCompleteDate itself — the REST accept path binds posted JSON
        // via Jackson (@RequestBody), which ignores the controller's
        // setAllowedFields, so a client CAN post it. The defense is that
        // hydrateStagingFlags overwrites it unconditionally from the DB before
        // the cross-day check reads it.
        AnalyzerResultItem original = reusedAccessionOriginalItem(true, false, false);
        AnalyzerResultItem correction = reusedAccessionCorrectionItem();
        correction.setCorrectionAction("USE");
        original.setCompleteDate("07/05/2025");
        correction.setCompleteDate("07/05/2025");
        original.setStagingCompleteDate(Timestamp.valueOf("2025-07-05 10:00:00"));
        correction.setStagingCompleteDate(Timestamp.valueOf("2025-07-05 10:00:00"));
        // see useCrossDayCorrection_...: REUSE300 also needs to clear the
        // orthogonal LIS-126 unmatched-sample gate to reach the code under test.
        original.setUnmatchedAction("ACCEPT_UNKNOWN");

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(original, correction));

        UnresolvedCorrectionException ex = assertThrows(
                "hydration must restore the true DB completeDates, still blocking a cross-day USE",
                UnresolvedCorrectionException.class, () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
        assertTrue("message must name the blocked accession", ex.getMessage().contains("REUSE300"));
    }

    @Test
    public void useCrossDayOrphanCorrection_unmappedPartner_blocksFailClosed() {
        // REUSE400: the writable original (1015) is UNMAPPED (no testId), so
        // hydration forces it readOnly and findPartner — which only considers
        // non-readOnly candidates — cannot see it. The cross-day USE on 1016
        // then lands in the orphan branch, which pre-fix committed the value
        // with only an auto-note. The guard must reach through the
        // duplicateAnalyzerResultId backlink to the staging row instead.
        AnalyzerResultItem original = buildItem("1015", "12", true, "1016", null, "REUSE400", "HGB", 1, true, false,
                false);
        original.setCompleteDate("07/01/2025");
        // 1015 is itself correction-shaped after hydration (readOnly + backlink)
        // and would trip the unresolved gate without a decision; DISMISS it so
        // the orphan USE path — not the unresolved gate — is what is under test.
        original.setCorrectionAction("DISMISS");
        // clears the downstream LIS-126 unmatched gate, like the REUSE300 tests
        original.setUnmatchedAction("ACCEPT_UNKNOWN");

        AnalyzerResultItem correction = buildItem("1016", "8", true, "1015", "4001", "REUSE400", "HGB", 1, false, false,
                false);
        correction.setCompleteDate("07/02/2025");
        correction.setCorrectionAction("USE");

        List<AnalyzerResultItem> items = new ArrayList<>(List.of(original, correction));

        UnresolvedCorrectionException ex = assertThrows(UnresolvedCorrectionException.class,
                () -> acceptService.acceptAndPersist(items, TEST_SYS_USER_ID));
        assertTrue("message must name the blocked accession", ex.getMessage().contains("REUSE400"));

        assertNotNull("unmapped original must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1015"));
        assertNotNull("correction must remain staged, not silently deleted",
                analyzerResultsService.readAnalyzerResults("1016"));
    }
}
