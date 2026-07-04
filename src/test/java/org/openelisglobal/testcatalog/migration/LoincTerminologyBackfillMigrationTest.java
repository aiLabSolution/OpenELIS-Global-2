package org.openelisglobal.testcatalog.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.sql.Timestamp;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression test for LIS-156: fork-seeded tests created after upstream's 043
 * LOINC backfill must still get a LOINC/SAME_AS terminology mapping.
 */
public class LoincTerminologyBackfillMigrationTest extends BaseWebContextSensitiveTest {

    private static final String BACKFILL_SQL = "INSERT INTO clinlims.test_terminology_mapping"
            + " (id, test_id, source, code, relationship, is_active, lastupdated, last_updated)"
            + " SELECT gen_random_uuid()::varchar, t.id, 'LOINC', trim(t.loinc), 'SAME_AS', 'Y', now(), now()"
            + " FROM clinlims.test t" + " WHERE t.loinc IS NOT NULL" + " AND trim(t.loinc) <> ''" + " AND NOT EXISTS ("
            + " SELECT 1 FROM clinlims.test_terminology_mapping m" + " WHERE m.test_id = t.id"
            + " AND m.source = 'LOINC'" + " AND m.code = trim(t.loinc)" + " )";

    private static final long NEEDS_BACKFILL = 94201L;
    private static final long ALREADY_MAPPED = 94202L;
    private static final long NO_LOINC = 94203L;

    @Autowired
    private javax.sql.DataSource dataSource;

    private JdbcTemplate jdbc;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        jdbc = new JdbcTemplate(dataSource);
        cleanSeededData();

        insertTest(NEEDS_BACKFILL, "BackfillNeedsLoinc", "22748-8");
        insertTest(ALREADY_MAPPED, "BackfillAlreadyMapped", "2345-7");
        insertTest(NO_LOINC, "BackfillNoLoinc", null);
        insertMapping(ALREADY_MAPPED, "2345-7");
    }

    @After
    public void tearDown() {
        cleanSeededData();
    }

    @Test
    public void backfill_insertsMissingLoincSameAsMappings_idempotently() {
        assertEquals("the changelog must include and execute the LIS-156 backfill", Long.valueOf(1L),
                jdbc.queryForObject(
                        "SELECT count(*) FROM clinlims.databasechangelog"
                                + " WHERE id = 'LIS-156-backfill-test-loinc-terminology' AND exectype = 'EXECUTED'",
                        Long.class));

        runBackfill();

        assertEquals(Long.valueOf(1L), mappingCount(NEEDS_BACKFILL));
        assertEquals("SAME_AS", relationship(NEEDS_BACKFILL));
        assertNotNull(version(NEEDS_BACKFILL));
        assertEquals(Long.valueOf(1L), mappingCount(ALREADY_MAPPED));
        assertEquals(Long.valueOf(0L), mappingCount(NO_LOINC));

        runBackfill();
        assertEquals("second run must not duplicate the inserted row", Long.valueOf(1L), mappingCount(NEEDS_BACKFILL));
        assertEquals("second run must not duplicate an existing mapping", Long.valueOf(1L),
                mappingCount(ALREADY_MAPPED));
    }

    private void runBackfill() {
        jdbc.execute(BACKFILL_SQL);
    }

    private Long mappingCount(long testId) {
        return jdbc.queryForObject("SELECT count(*) FROM clinlims.test_terminology_mapping WHERE test_id = ?",
                Long.class, testId);
    }

    private String relationship(long testId) {
        return jdbc.queryForObject("SELECT relationship FROM clinlims.test_terminology_mapping WHERE test_id = ?",
                String.class, testId);
    }

    private Timestamp version(long testId) {
        return jdbc.queryForObject("SELECT last_updated FROM clinlims.test_terminology_mapping WHERE test_id = ?",
                Timestamp.class, testId);
    }

    private void insertTest(long id, String name, String loinc) {
        jdbc.update(
                "INSERT INTO clinlims.test (id, name, description, loinc, is_active, guid, lastupdated)"
                        + " VALUES (?, ?, ?, ?, 'Y', ?, NOW())",
                id, name, name + " (loinc backfill test)", loinc, UUID.randomUUID().toString());
    }

    private void insertMapping(long testId, String loinc) {
        jdbc.update(
                "INSERT INTO clinlims.test_terminology_mapping"
                        + " (id, test_id, source, code, relationship, is_active, lastupdated, last_updated)"
                        + " VALUES (?, ?, 'LOINC', ?, 'SAME_AS', 'Y', NOW(), NOW())",
                UUID.randomUUID().toString(), testId, loinc);
    }

    private void cleanSeededData() {
        jdbc.execute("DELETE FROM clinlims.test_terminology_mapping WHERE test_id IN (94201, 94202, 94203)");
        jdbc.execute("DELETE FROM clinlims.test WHERE id IN (94201, 94202, 94203)");
    }
}
