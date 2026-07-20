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
 * {@link #rollbackLeavesReusedCatalogRowsAlone()} pins that boundary.
 */
public class MaglumiX3SeedRollbackTest {

    private static final String CHANGELOG = "liquibase/lis272-x3-seed-test.xml";
    private static final String ANALYZER = "SNIBE MAGLUMI X3";

    /**
     * Every changeset down to (and including) the fixture's analyzer_qc_rule table:
     * 004-015's seed, that table, and the six changesets of 063. Stops short of the
     * fixture's own baseline-catalog and setup changesets.
     */
    private static final int SEED_CHANGESET_COUNT = 8;

    @Test
    public void rollbackLeavesReusedCatalogRowsAlone() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14.4")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                connection.setAutoCommit(true);
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);

                liquibase.update(new Contexts());
                assertEquals(1,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
                assertEquals(2, count(connection, "SELECT count(*) FROM clinlims.analyzer_qc_rule"));

                liquibase.rollback(SEED_CHANGESET_COUNT, "");

                assertEquals("the seeded analyzer must be gone", 0,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
                assertEquals("its map rows go with it via the ON DELETE CASCADE fk", 0,
                        count(connection, "SELECT count(*) FROM clinlims.analyzer_test_map"));
                assertEquals("Tests this seed created are scoped by the X3-* local_code it assigns", 0,
                        count(connection, "SELECT count(*) FROM clinlims.test WHERE local_code LIKE 'X3-%-Serum'"));

                // The boundary that matters: the fixture's pre-existing TSH row carries
                // 3016-3, so cs2 adopted rather than created it. A rollback scoped by
                // LOINC alone would delete it — someone else's catalog entry.
                assertEquals("a reused catalog Test must survive the rollback", 1,
                        count(connection, "SELECT count(*) FROM clinlims.test WHERE local_code = 'CATALOG-TSH-Serum'"));

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
