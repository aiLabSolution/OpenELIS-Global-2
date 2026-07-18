package org.openelisglobal.analyzer.migration;

import static org.junit.Assert.assertEquals;
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
            assertArchived(connection, "analyzer_results", "staging-value");
            assertArchived(connection, "result", "clinical-value");
            assertArchived(connection, "result_version", "clinical-value");
            assertArchiveMutationRejected(connection, "UPDATE clinlims.analyzer_evidence_rollback_archive "
                    + "SET reference_range = 'tampered' WHERE source_table = 'result'");
            assertArchiveMutationRejected(connection,
                    "DELETE FROM clinlims.analyzer_evidence_rollback_archive WHERE source_table = 'result'");
            assertOverLimitWriteRejected(connection);

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
            updateResultRange(connection, LONG_RANGE);
            assertEquals(LONG_RANGE, scalar(connection, "SELECT reference_range FROM clinlims.result WHERE id = 201"));
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

    private void assertArchived(Connection connection, String sourceTable, String expectedValue) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT current_value, reference_range, abnormal_flag, evidence_digest "
                        + "FROM clinlims.analyzer_evidence_rollback_archive WHERE source_table = ?")) {
            statement.setString(1, sourceTable);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue("missing archive row for " + sourceTable, resultSet.next());
                assertEquals(expectedValue, resultSet.getString("current_value"));
                assertEquals(LONG_RANGE, resultSet.getString("reference_range"));
                assertEquals(LONG_FLAG, resultSet.getString("abnormal_flag"));
                assertEquals(32, resultSet.getString("evidence_digest").length());
            }
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

    private void assertOverLimitWriteRejected(Connection connection) throws SQLException {
        try {
            updateResultRange(connection, LONG_RANGE);
            fail("legacy-width guard should reject new over-limit evidence during a staged rollback");
        } catch (SQLException exception) {
            String message = exception.getMessage();
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            assertTrue(message.contains("ck_result_lis97_legacy_evidence_width"));
        }
    }

    private void updateResultRange(Connection connection, String referenceRange) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("UPDATE clinlims.result SET reference_range = ? WHERE id = 201")) {
            statement.setString(1, referenceRange);
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
