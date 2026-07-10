package org.openelisglobal.analyzerresults.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
    private AnalyzerResultItem correctionItem() {
        return buildItem("1011", "7.1", true, "1010", "4001", "CORR100", "Potassium", 1, false, false, false);
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
}
