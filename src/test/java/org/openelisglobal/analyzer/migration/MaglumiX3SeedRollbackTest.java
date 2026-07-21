package org.openelisglobal.analyzer.migration;

import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises the rollback blocks of the LIS-272 X3 seed (063 +
 * analyzer/004-015).
 *
 * <p>
 * A rollback that throws is not the only failure worth guarding. The one that
 * matters more here is a rollback that succeeds and deletes too much: these
 * changesets reuse catalog rows they did not create (a Test that already
 * carries one of the thyroid LOINCs is adopted, not re-inserted), so an
 * over-broad DELETE would remove a lab's own configuration on a downgrade.
 * {@link #rollbackDeletesOnlySeedOwnedRows()} and
 * {@link #rollbackPreservesSiteOwnedAnalyzerAndMap()} pin that boundary.
 */
public class MaglumiX3SeedRollbackTest {

    private static final String CHANGELOG = "liquibase/lis272-x3-seed-test.xml";
    private static final String ANALYZER = "SNIBE MAGLUMI X3";
    private static final int BASELINE_CHANGESET_COUNT = 2;
    private static final String BASELINE_TAG = "lis272-rollback-baseline";

    @Test
    public void rollbackDeletesOnlySeedOwnedRows() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14.4")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                connection.setAutoCommit(true);
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);

                liquibase.update(BASELINE_CHANGESET_COUNT, "");
                liquibase.tag(BASELINE_TAG);
                liquibase.update(new Contexts());
                assertEquals(1,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
                assertEquals(2, count(connection, "SELECT count(*) FROM clinlims.analyzer_qc_rule"));

                liquibase.rollback(BASELINE_TAG, "");

                assertEquals("the seeded analyzer must be gone", 0,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
                assertEquals("its map rows go with it via the ON DELETE CASCADE fk", 0,
                        count(connection,
                                "SELECT count(*) FROM clinlims.analyzer_test_map m"
                                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '"
                                        + ANALYZER + "'"));
                assertEquals("another analyzer's mapping is not collateral damage", 1,
                        count(connection,
                                "SELECT count(*) FROM clinlims.analyzer_test_map m"
                                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id"
                                        + " WHERE a.name = 'OTHER ANALYZER'"));
                assertEquals("Tests this seed created are scoped by their deterministic seed GUIDs", 0,
                        count(connection, "SELECT count(*) FROM clinlims.test"
                                + " WHERE guid LIKE '27200000-0000-4000-8000-00000000000%'"));

                // The boundary that matters: this pre-existing TSH row carries both the
                // adopted LOINC and the exact local_code the seed would choose. Neither
                // establishes ownership; only the row's different GUID does.
                assertEquals("a reused catalog Test with a seed-like local code must survive the rollback", 1,
                        count(connection,
                                "SELECT count(*) FROM clinlims.test WHERE id = 900"
                                        + " AND local_code = 'X3-TSHII-Serum'"
                                        + " AND guid = '11100000-0000-4000-8000-000000000900'"));
                assertEquals("a reused UOM widened by the seed must survive the rollback", 1,
                        count(connection, "SELECT count(*) FROM clinlims.unit_of_measure WHERE id = 900"
                                + " AND name = 'pmol/L' AND ucum_code = 'pmol/L'"));

                database.close();
            }
        }
    }

    @Test
    public void rollbackPreservesSiteOwnedAnalyzerAndMap() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14.4")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                connection.setAutoCommit(true);
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);

                liquibase.update(BASELINE_CHANGESET_COUNT, "");
                // Liquibase may leave its JDBC connection in transactional mode. The
                // simulated site rows must be committed before tag() starts its own unit
                // of work, just as they would already be durable on a real installation.
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO clinlims.analyzer"
                            + " (id, name, description, is_active, status, fhir_uuid, last_updated)" + " VALUES (700, '"
                            + ANALYZER + "', 'Site-owned X3', true, 'SETUP',"
                            + " '11100000-0000-4000-8000-000000000700', now())");
                    statement.executeUpdate("INSERT INTO clinlims.analyzer_test_map"
                            + " (analyzer_id, analyzer_test_name, test_id, last_updated)"
                            + " VALUES (700, 'TSH II', 900, now())");
                }
                liquibase.tag(BASELINE_TAG);

                liquibase.update(new Contexts());
                assertEquals("the migration must adopt rather than replace the site analyzer", 1,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE id = 700"
                                + " AND fhir_uuid = '11100000-0000-4000-8000-000000000700'"));
                assertEquals("missing maps are still filled on an adopted analyzer", 3,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer_test_map WHERE analyzer_id = 700"));

                liquibase.rollback(BASELINE_TAG, "");

                assertEquals("a MARK_RAN analyzer insert must not own the site row", 1,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE id = 700"
                                + " AND fhir_uuid = '11100000-0000-4000-8000-000000000700'"));
                assertEquals("a pre-existing site map must not be collateral damage", 1,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer_test_map"
                                + " WHERE analyzer_id = 700 AND analyzer_test_name = 'TSH II' AND test_id = 900"));
                assertEquals("map rows inserted by this migration must roll back", 0,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer_test_map"
                                + " WHERE analyzer_id = 700 AND analyzer_test_name IN ('FT3', 'FT4 II')"));

                database.close();
            }
        }
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
