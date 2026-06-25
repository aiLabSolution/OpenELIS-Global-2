package org.openelisglobal.history;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.history.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;

public class HistoryServiceTest extends BaseWebContextSensitiveTest {

    @Autowired
    private HistoryService historyService;

    @PersistenceContext
    private EntityManager entityManager;

    @Before
    public void init() throws Exception {
        executeDataSetWithStateManagement("testdata/history.xml");
    }

    @Test
    public void insert_validHistory_shouldInsertRecord() {
        History newHistory = new History();
        newHistory.setSysUserId("1");
        newHistory.setReferenceId("11111");
        newHistory.setReferenceTable("5");
        newHistory.setTimestamp(Timestamp.from(Instant.now()));
        newHistory.setActivity("I");
        newHistory.setChanges("Test insert".getBytes());

        String insertedId = historyService.insert(newHistory);

        Assert.assertNotNull(insertedId);

        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("11111", "5");
        Assert.assertEquals(1, historyList.size());
    }

    /**
     * LIS-6 / S0.4: clinlims.history is append-only. The DB-layer trigger
     * (Liquibase changeset 046-history-append-only.xml) rejects any UPDATE of an
     * existing audit row, so HistoryService.update() on a persisted history row
     * fails. HistoryService.update/delete have no production callers (audit
     * emission is INSERT-only); these previously asserted that history was mutable,
     * which the append-only invariant now forbids. The full DB-layer proof lives in
     * {@code org.openelisglobal.audittrail.HistoryAppendOnlyIntegrationTest}.
     */
    @Test
    public void update_existingHistoryRow_isRejectedByAppendOnlyTrigger() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("67890", "1");
        Assert.assertFalse(historyList.isEmpty());

        History history = historyList.get(0);
        history.setChanges("tamper".getBytes()); // genuinely dirty -> forces a SQL UPDATE on flush

        Exception ex = Assert.assertThrows(Exception.class, () -> historyService.update(history));
        Assert.assertTrue("update should be rejected by the append-only guard, was: " + causeChainMessage(ex),
                causeChainMessage(ex).toLowerCase().contains("append-only"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void deleteHistory_detachedEntity_shouldThrowException() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("67890", "1");
        History detachedHistory = historyList.get(0);

        entityManager.clear();
        historyService.delete(detachedHistory);
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_validInputs_shouldReturnRecords() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("67890", "1");

        Assert.assertFalse(historyList.isEmpty());
        Assert.assertEquals(2, historyList.size());
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_differentTableIds_shouldReturnCorrectRecords() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("67890", "2");

        Assert.assertFalse(historyList.isEmpty());
        Assert.assertEquals(1, historyList.size());
    }

    @Test
    public void getHistory_differentRefTables_shouldReturnCorrectCounts() {
        List<History> table1 = historyService.getHistoryByRefIdAndRefTableId("67890", "1");
        Assert.assertEquals(2, table1.size());

        List<History> table2 = historyService.getHistoryByRefIdAndRefTableId("67890", "2");
        Assert.assertEquals(1, table2.size());
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_shouldReturnSortedResults() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("67890", "1");
        Assert.assertFalse(historyList.isEmpty());

        for (int i = 1; i < historyList.size(); i++) {
            Timestamp previousTimestamp = historyList.get(i - 1).getTimestamp();
            Timestamp currentTimestamp = historyList.get(i).getTimestamp();
            Assert.assertTrue(previousTimestamp.compareTo(currentTimestamp) >= 0);
        }
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_existingData_shouldReturnRecords() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("99999", "99999");

        Assert.assertFalse(historyList.isEmpty());
        Assert.assertEquals(1, historyList.size());
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_noMatchingData_shouldReturnEmptyList() {
        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId("88888", "88888");

        Assert.assertTrue(historyList.isEmpty());
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_withHistoryObject_shouldReturnRecords() {
        History searchHistory = new History();
        searchHistory.setReferenceId("67890");
        searchHistory.setReferenceTable("1");

        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId(searchHistory);

        Assert.assertFalse(historyList.isEmpty());
        Assert.assertEquals(2, historyList.size());
    }

    @Test
    public void getHistoryByRefIdAndRefTableId_withHistoryObject_differentTable_shouldReturnCorrectRecords() {
        History searchHistory = new History();
        searchHistory.setReferenceId("67890");
        searchHistory.setReferenceTable("2");

        List<History> historyList = historyService.getHistoryByRefIdAndRefTableId(searchHistory);

        Assert.assertFalse(historyList.isEmpty());
        Assert.assertEquals(1, historyList.size());
    }

    @Test(expected = NumberFormatException.class)
    public void getHistoryByRefIdAndRefTableId_noRecordsFound() {
        historyService.getHistoryByRefIdAndRefTableId("nonexistent", "nonexistent");
    }

    @Test(expected = NumberFormatException.class)
    public void getHistoryByRefIdAndRefTableId_withHistoryObject_nonNumericIds_shouldThrowException() {
        History searchHistory = new History();
        searchHistory.setReferenceId("notanumber");
        searchHistory.setReferenceTable("1");

        historyService.getHistoryByRefIdAndRefTableId(searchHistory);
    }

    /** Flatten a throwable's cause chain into one string for message assertions. */
    private static String causeChainMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}