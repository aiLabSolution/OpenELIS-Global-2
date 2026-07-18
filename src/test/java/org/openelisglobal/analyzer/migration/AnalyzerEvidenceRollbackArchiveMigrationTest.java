package org.openelisglobal.analyzer.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.PostgreSQLContainer;

public class AnalyzerEvidenceRollbackArchiveMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:14.4");

    private static final String LONG_RANGE = "RANGE-BEGIN:" + "r".repeat(96) + ":RANGE-EXACT-END";
    private static final String LONG_FLAG = "FLAG-BEGIN:" + "f".repeat(48) + ":FLAG-EXACT-END";

    @BeforeClass
    public static void startPostgres() {
        POSTGRES.start();
    }

    @AfterClass
    public static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    public void rollbackArchivesExactEvidenceBeforeLegacyColumnsNarrow() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            connection.setAutoCommit(true);
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("liquibase/lis97-rollback-archive-test.xml",
                    new ClassLoaderResourceAccessor(), database);
            Contexts contexts = new Contexts();

            liquibase.update(contexts);
            insertLongEvidence(connection);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }

            // Roll back 061 first and deliberately pause before 060. The
            // compensator must preserve exact evidence and prevent any new
            // over-limit writes during this staged downgrade window.
            liquibase.rollback(2, "");
            assertEquals(LONG_RANGE.substring(0, 80),
                    scalar(connection, "SELECT reference_range FROM clinlims.analyzer_results WHERE id = 101"));
            assertEquals(LONG_FLAG.substring(0, 40),
                    scalar(connection, "SELECT abnormal_flag FROM clinlims.result WHERE id = 201"));

            assertEquals(3, count(connection, "SELECT COUNT(*) FROM clinlims.analyzer_evidence_rollback_archive"));
            assertArchived(connection, "analyzer_results", 101, null, null, "staging-value");
            assertArchived(connection, "result", 201, 201, null, "clinical-value");
            assertArchived(connection, "result_version", 1, 201, 1, "clinical-value");
            assertArchiveMutationRejected(connection, "UPDATE clinlims.analyzer_evidence_rollback_archive "
                    + "SET reference_range = 'tampered' WHERE source_table = 'result'");
            assertArchiveMutationRejected(connection,
                    "DELETE FROM clinlims.analyzer_evidence_rollback_archive WHERE source_table = 'result'");
            assertOverLimitWriteRejected(connection, "analyzer_results", 101,
                    "ck_analyzer_results_lis97_legacy_evidence_width");
            assertOverLimitWriteRejected(connection, "result", 201, "ck_result_lis97_legacy_evidence_width");

            // Continue through the focused fixture trigger and 060 changesets
            // 005, 004, and 003. Changesets 001/002 remain so their legacy
            // columns can prove deterministic narrowing.
            liquibase.rollback(4, "");
            assertEquals(80, columnMaximumLength(connection, "analyzer_results", "reference_range"));
            assertEquals(40, columnMaximumLength(connection, "analyzer_results", "abnormal_flag"));
            assertEquals(80, columnMaximumLength(connection, "result", "reference_range"));
            assertEquals(40, columnMaximumLength(connection, "result", "abnormal_flag"));
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM clinlims.analyzer_evidence_rollback_archive"));

            liquibase.update(contexts);
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM clinlims.analyzer_evidence_rollback_archive"));
            assertEquals("text", scalar(connection, "SELECT data_type FROM information_schema.columns "
                    + "WHERE table_schema = 'clinlims' AND table_name = 'result' AND column_name = 'reference_range'"));
            assertEquals("text",
                    scalar(connection,
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_schema = 'clinlims' AND table_name = 'analyzer_results' "
                                    + "AND column_name = 'reference_range'"));
            updateEvidence(connection, "analyzer_results", 101, LONG_RANGE, LONG_FLAG);
            updateEvidence(connection, "result", 201, LONG_RANGE, LONG_FLAG);
            assertEquals(LONG_RANGE,
                    scalar(connection, "SELECT reference_range FROM clinlims.analyzer_results WHERE id = 101"));
            assertEquals(LONG_FLAG,
                    scalar(connection, "SELECT abnormal_flag FROM clinlims.analyzer_results WHERE id = 101"));
            assertEquals(LONG_RANGE, scalar(connection, "SELECT reference_range FROM clinlims.result WHERE id = 201"));
            assertEquals(LONG_FLAG, scalar(connection, "SELECT abnormal_flag FROM clinlims.result WHERE id = 201"));
            database.close();
        }
    }

    private void insertLongEvidence(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO clinlims.analyzer_results (id, result, reference_range, abnormal_flag) VALUES (101, ?, ?, ?)")) {
            statement.setString(1, "staging-value");
            statement.setString(2, LONG_RANGE);
            statement.setString(3, LONG_FLAG);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO clinlims.result (id, value, reference_range, abnormal_flag) VALUES (201, ?, ?, ?)")) {
            statement.setString(1, "clinical-value");
            statement.setString(2, LONG_RANGE);
            statement.setString(3, LONG_FLAG);
            statement.executeUpdate();
        }
        assertEquals(1, count(connection, "SELECT COUNT(*) FROM clinlims.result_version WHERE result_id = 201"));
    }

    private void assertArchived(Connection connection, String sourceTable, int expectedSourceRecordId,
            Integer expectedResultId, Integer expectedVersionNumber, String expectedValue) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT source_record_id, result_id, version_number, current_value, "
                        + "reference_range, abnormal_flag, evidence_digest "
                        + "FROM clinlims.analyzer_evidence_rollback_archive WHERE source_table = ?")) {
            statement.setString(1, sourceTable);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue("missing archive row for " + sourceTable, resultSet.next());
                assertEquals(expectedSourceRecordId, resultSet.getInt("source_record_id"));
                assertNullableInteger(expectedResultId, resultSet, "result_id");
                assertNullableInteger(expectedVersionNumber, resultSet, "version_number");
                assertEquals(expectedValue, resultSet.getString("current_value"));
                assertEquals(LONG_RANGE, resultSet.getString("reference_range"));
                assertEquals(LONG_FLAG, resultSet.getString("abnormal_flag"));
                assertEquals(32, resultSet.getString("evidence_digest").length());
            }
        }
    }

    private void assertNullableInteger(Integer expected, ResultSet resultSet, String column) throws SQLException {
        if (expected == null) {
            assertNull(resultSet.getObject(column));
        } else {
            assertEquals(expected.intValue(), resultSet.getInt(column));
        }
    }

    private void assertArchiveMutationRejected(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            fail("append-only archive mutation should be rejected");
        } catch (SQLException exception) {
            String message = exception.getMessage();
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            assertTrue(message.contains("append-only"));
        }
    }

    private void assertOverLimitWriteRejected(Connection connection, String table, int id, String constraintName)
            throws SQLException {
        try {
            updateEvidence(connection, table, id, LONG_RANGE, LONG_FLAG);
            fail("legacy-width guard should reject new over-limit evidence for " + table);
        } catch (SQLException exception) {
            String message = exception.getMessage();
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            assertTrue(message.contains(constraintName));
        }
    }

    private void updateEvidence(Connection connection, String table, int id, String referenceRange, String abnormalFlag)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE clinlims." + table + " SET reference_range = ?, abnormal_flag = ? WHERE id = ?")) {
            statement.setString(1, referenceRange);
            statement.setString(2, abnormalFlag);
            statement.setInt(3, id);
            statement.executeUpdate();
        }
    }

    private int columnMaximumLength(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'clinlims' AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private int count(Connection connection, String sql) throws SQLException {
        return Integer.parseInt(scalar(connection, sql));
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
