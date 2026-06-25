package org.openelisglobal.audittrail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.audittrail.daoimpl.AuditTrailServiceImpl;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.dictionary.service.DictionaryService;
import org.openelisglobal.dictionary.valueholder.Dictionary;
import org.openelisglobal.dictionarycategory.service.DictionaryCategoryService;
import org.openelisglobal.history.service.HistoryService;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.referencetables.valueholder.ReferenceTables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LIS-6 / S0.4 — append-only audit log with DB-layer immutability.
 *
 * <p>
 * End-to-end thread for the slice: creating then updating a record emits
 * append-only AuditEvents (rows in {@code clinlims.history}) with correct
 * who/what/when/before-after, and a direct {@code UPDATE} or {@code DELETE}
 * against an existing audit row is rejected at the database layer by the
 * append-only trigger (Liquibase changeset
 * {@code 046-history-append-only.xml}).
 *
 * <p>
 * The trigger fires on UPDATE/DELETE only — not TRUNCATE — so the
 * integration-test harness reset and the training-installation
 * {@code DatabaseClean} reset endpoints keep working. This test reuses the
 * existing audit-emission path (the same wiring as
 * {@link SystemAuditTrailIntegrationTest}) so the audit row under test is a
 * genuine, service-emitted record rather than a synthetic insert.
 */
public class HistoryAppendOnlyIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private DictionaryService dictionaryService;

    @Autowired
    private DictionaryCategoryService dictionaryCategoryService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ReferenceTablesService referenceTablesService;

    @Autowired
    private DataSource dataSource;

    private String dictionaryRefTableId;

    @Before
    public void setUp() throws Exception {
        // Swap the mocked AuditTrailService for a real one so create/update emit
        // genuine history rows (mirrors SystemAuditTrailIntegrationTest).
        AuditTrailServiceImpl realAuditTrailService = new AuditTrailServiceImpl();
        ReflectionTestUtils.setField(realAuditTrailService, "referenceTablesService", referenceTablesService);
        ReflectionTestUtils.setField(realAuditTrailService, "historyService", historyService);
        Object target = AopTestUtils.getUltimateTargetObject(dictionaryService);
        ReflectionTestUtils.setField(target, "auditTrailService", realAuditTrailService);

        executeDataSetWithStateManagement("testdata/dictionary.xml");
        ReferenceTables rt = referenceTablesService.getReferenceTableByName("DICTIONARY");
        assertNotNull("DICTIONARY must be in reference_tables", rt);
        dictionaryRefTableId = rt.getId();
    }

    /**
     * AC1 + AC2 as one thread: create then update yields append-only AuditEvents,
     * and a direct UPDATE and a direct DELETE on an existing audit row are each
     * rejected at the DB layer, leaving the row intact.
     */
    @Test
    public void createThenUpdate_emitsAppendOnlyAuditEvents_andDirectMutationIsRejected() throws Exception {
        // --- AC1: create emits exactly one 'I' audit event (who/what/when) ---
        Dictionary dict = new Dictionary();
        dict.setSortOrder(10);
        dict.setDictionaryCategory(dictionaryCategoryService.getDictionaryCategoryByName("CA3"));
        dict.setDictEntry("Append Only Entry");
        dict.setIsActive("Y");
        dict.setLocalAbbreviation("AOE");
        dict.setSysUserId("1");

        String id = dictionaryService.insert(dict);
        assertNotNull("insert should return an id", id);

        List<History> afterInsert = historyService.getHistoryByRefIdAndRefTableId(id, dictionaryRefTableId);
        assertEquals("exactly one history row after insert", 1, afterInsert.size());
        History insertRow = afterInsert.get(0);
        assertEquals("I", insertRow.getActivity());
        assertEquals("1", insertRow.getSysUserId());
        assertEquals(dictionaryRefTableId, insertRow.getReferenceTable());

        // --- AC1: update emits a 'U' audit event carrying the before-image ---
        String original = dict.getDictEntry();
        dict.setDictEntry("Append Only Entry (edited)");
        dict.setSysUserId("1");
        dictionaryService.update(dict);

        List<History> afterUpdate = historyService.getHistoryByRefIdAndRefTableId(id, dictionaryRefTableId);
        History updateRow = afterUpdate.stream().filter(h -> "U".equals(h.getActivity())).findFirst().orElse(null);
        assertNotNull("an update history row should exist", updateRow);
        assertNotNull("update row should carry a before/after diff", updateRow.getChanges());
        assertTrue("diff should contain the original value", new String(updateRow.getChanges()).contains(original));

        // --- AC2: a direct UPDATE against an existing audit row is rejected at the DB
        // layer ---
        final String auditRowId = insertRow.getId();
        SQLException onUpdate = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE clinlims.history SET activity = 'X' WHERE id = " + auditRowId);
            }
        });
        assertTrue("UPDATE rejection should come from the append-only guard, was: " + onUpdate.getMessage(),
                String.valueOf(onUpdate.getMessage()).toLowerCase().contains("append-only"));

        // --- AC2: a direct DELETE against an existing audit row is rejected at the DB
        // layer ---
        SQLException onDelete = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM clinlims.history WHERE id = " + auditRowId);
            }
        });
        assertTrue("DELETE rejection should come from the append-only guard, was: " + onDelete.getMessage(),
                String.valueOf(onDelete.getMessage()).toLowerCase().contains("append-only"));

        // --- the audit row survived both rejected mutations, unchanged ---
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT activity FROM clinlims.history WHERE id = " + auditRowId)) {
            assertTrue("audit row must still exist after rejected mutations", rs.next());
            assertEquals("audit row activity must be unchanged", "I", rs.getString(1));
        }
    }
}
