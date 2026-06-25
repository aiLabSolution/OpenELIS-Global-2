package org.openelisglobal.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-7 / S0.5 — Result table shape with raw + normalized columns and
 * append-only result versions.
 *
 * <p>
 * Two claims of the slice, proven end-to-end at the database layer (Liquibase
 * changeset {@code 047-result-shape.xml}):
 *
 * <ol>
 * <li><b>Raw + normalized side by side.</b> A {@code clinlims.result} row
 * carries {@code raw_code}, {@code raw_unit}, {@code loinc}, {@code ucum_value}
 * and {@code status} alongside the existing {@code value} — the raw analyzer
 * code/unit and the normalized LOINC/UCUM observation coexist on one row.</li>
 * <li><b>Append-only result versions.</b> Every value/normalization change to a
 * result appends a new {@code clinlims.result_version} snapshot (an
 * {@code AFTER INSERT OR UPDATE} trigger on {@code result}) rather than
 * overwriting the prior version, and a direct {@code UPDATE} or {@code DELETE}
 * against an existing version row is rejected at the DB layer by the
 * append-only trigger — so the version spine can never be silently rewritten
 * (no last-writer-wins), the foundation Stage-4 reconciliation builds on.</li>
 * </ol>
 *
 * <p>
 * Mirrors {@code HistoryAppendOnlyIntegrationTest} (LIS-6 / S0.4): the DB-layer
 * guarantee is asserted by driving raw JDBC against the migrated schema and
 * checking that the guard rejects mutation with an {@code append-only} message.
 * All assertions are scoped to a single synthetic {@code result.id} so the test
 * is independent of any other rows in the shared container.
 */
public class ResultVersionAppendOnlyIntegrationTest extends BaseWebContextSensitiveTest {

    /** A synthetic result id high enough to never collide with fixture data. */
    private static final long RESULT_ID = 999_000_701L;

    @Autowired
    private DataSource dataSource;

    @Test
    public void resultCarriesRawAndNormalizedColumns_andUpdatesAppendImmutableVersions() throws Exception {
        // --- arrange: a result captured with raw analyzer fields AND normalized
        // LOINC/UCUM, all on the same row ---
        insertResult(RESULT_ID, "5.2", "GLU", "mg/dL", "2345-7", "mmol/L", "RAW");

        // CLAIM 1: the five normalization columns coexist with value on one result row.
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT value, raw_code, raw_unit, loinc, ucum_value, status"
                        + " FROM clinlims.result WHERE id = " + RESULT_ID)) {
            assertTrue("result row must exist", rs.next());
            assertEquals("5.2", rs.getString("value"));
            assertEquals("GLU", rs.getString("raw_code"));
            assertEquals("mg/dL", rs.getString("raw_unit"));
            assertEquals("2345-7", rs.getString("loinc"));
            assertEquals("mmol/L", rs.getString("ucum_value"));
            assertEquals("RAW", rs.getString("status"));
        }

        // the insert auto-appended version 1 via the AFTER INSERT trigger.
        assertEquals("insert appends exactly one version", 1, versionCount(RESULT_ID));
        long firstVersionId = versionIdOf(RESULT_ID, 1);

        // --- act: correct the result (value + normalization change) ---
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE clinlims.result SET value = '7.8', ucum_value = '0.43',"
                    + " status = 'RECONCILED' WHERE id = " + RESULT_ID);
        }

        // CLAIM 2a: the update APPENDED a new version rather than overwriting.
        assertEquals("update appends a second version", 2, versionCount(RESULT_ID));
        assertVersion(RESULT_ID, 1, "5.2", "mmol/L", "RAW"); // version 1 is byte-for-byte unchanged
        assertVersion(RESULT_ID, 2, "7.8", "0.43", "RECONCILED"); // version 2 is the new snapshot

        // an UPDATE that touches no value/normalization column must NOT spawn a
        // version.
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE clinlims.result SET sort_order = 9 WHERE id = " + RESULT_ID);
        }
        assertEquals("a non-result-bearing update appends no version", 2, versionCount(RESULT_ID));

        // CLAIM 2b: a direct UPDATE on an existing version row is rejected at the DB
        // layer.
        SQLException onUpdate = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE clinlims.result_version SET value = 'X' WHERE id = " + firstVersionId);
            }
        });
        assertTrue("UPDATE rejection should come from the append-only guard, was: " + onUpdate.getMessage(),
                String.valueOf(onUpdate.getMessage()).toLowerCase().contains("append-only"));

        // CLAIM 2b: a direct DELETE on an existing version row is rejected at the DB
        // layer.
        SQLException onDelete = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM clinlims.result_version WHERE id = " + firstVersionId);
            }
        });
        assertTrue("DELETE rejection should come from the append-only guard, was: " + onDelete.getMessage(),
                String.valueOf(onDelete.getMessage()).toLowerCase().contains("append-only"));

        // the version row survived both rejected mutations, unchanged.
        assertVersion(RESULT_ID, 1, "5.2", "mmol/L", "RAW");
    }

    private void insertResult(long id, String value, String rawCode, String rawUnit, String loinc, String ucumValue,
            String status) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO clinlims.result"
                        + " (id, value, raw_code, raw_unit, loinc, ucum_value, status) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, value);
            ps.setString(3, rawCode);
            ps.setString(4, rawUnit);
            ps.setString(5, loinc);
            ps.setString(6, ucumValue);
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    private int versionCount(long resultId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt
                        .executeQuery("SELECT count(*) FROM clinlims.result_version WHERE result_id = " + resultId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long versionIdOf(long resultId, int versionNumber) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT id FROM clinlims.result_version WHERE result_id = " + resultId
                        + " AND version_number = " + versionNumber)) {
            assertTrue("version " + versionNumber + " must exist", rs.next());
            return rs.getLong(1);
        }
    }

    private void assertVersion(long resultId, int versionNumber, String expectedValue, String expectedUcum,
            String expectedStatus) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT value, ucum_value, status FROM clinlims.result_version"
                        + " WHERE result_id = " + resultId + " AND version_number = " + versionNumber)) {
            assertTrue("version " + versionNumber + " must exist", rs.next());
            assertEquals("version " + versionNumber + " value", expectedValue, rs.getString("value"));
            assertEquals("version " + versionNumber + " ucum_value", expectedUcum, rs.getString("ucum_value"));
            assertEquals("version " + versionNumber + " status", expectedStatus, rs.getString("status"));
        }
    }
}
