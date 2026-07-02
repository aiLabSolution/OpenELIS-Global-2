package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.test.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-86 / LIS-92 — verifies the Seamaty SD1 biochem code -> LOINC seed
 * (Liquibase changesets {@code 049-sd1-loinc-seed.xml} and
 * {@code 050-sd1-live-chemistry-seed.xml}) against the migrated schema.
 *
 * <p>
 * The seed's own rows land in <b>canonical</b> tables ({@code test},
 * {@code analyzer}, {@code analyzer_test_map}) which sibling integration tests
 * truncate/reload via DBUnit ({@code executeDataSetWithStateManagement}), so
 * they are <b>not</b> reliably present when an arbitrary test runs (the same
 * reason the LIS-8 seed test asserts only its new {@code vendor_code_mapping}
 * table). The exact row content is proven separately at the data layer against
 * the migrated schema. This test therefore verifies the two things that
 * <i>are</i> reliable here:
 *
 * <ol>
 * <li><b>The seeds executed.</b> All {@code lis86-*} and {@code lis92-*}
 * changesets are recorded {@code EXECUTED} in {@code databasechangelog}
 * (liquibase's own tracking table, which fixtures never reset) — proving 049 and
 * 050 are wired into the changelog and applied cleanly in a from-scratch
 * migration.</li>
 * <li><b>The production resolver works over the real DB.</b> With a fixture
 * analyzer + twenty live SD1 code mappings + twenty LOINC-carrying Test rows
 * shaped like the seeded output,
 * {@link BridgeRegistrationService#attachTestCodeLoinc} (the map OE2 pushes so
 * the bridge can LOINC-code each Observation) yields exactly the live SD1 code
 * -> LOINC pairs, and each LOINC resolves via
 * {@code TestService.getTestsByLoincCode} — exercising the real DAO / HQL /
 * numeric-string-converter / Test.loinc chain the mock unit test cannot.</li>
 * </ol>
 */
public class Sd1LoincSeedIntegrationTest extends BaseWebContextSensitiveTest {

    /** Live SD1 wire code (OBX-3.1) -> target LOINC, in panel order. */
    private static final Map<String, String> SD1_CODE_TO_LOINC = new LinkedHashMap<>();

    static {
        SD1_CODE_TO_LOINC.put("LDL", "22748-8");
        SD1_CODE_TO_LOINC.put("AMY", "1798-8");
        SD1_CODE_TO_LOINC.put("GLU", "2345-7");
        SD1_CODE_TO_LOINC.put("TB", "14631-6");
        SD1_CODE_TO_LOINC.put("TC", "2093-3");
        SD1_CODE_TO_LOINC.put("TG", "2571-8");
        SD1_CODE_TO_LOINC.put("DB", "14629-0");
        SD1_CODE_TO_LOINC.put("CHE", "2098-2");
        SD1_CODE_TO_LOINC.put("ALB", "1751-7");
        SD1_CODE_TO_LOINC.put("ALP", "6768-6");
        SD1_CODE_TO_LOINC.put("TP", "2885-2");
        SD1_CODE_TO_LOINC.put("CK", "2157-6");
        SD1_CODE_TO_LOINC.put("GGT", "2324-2");
        SD1_CODE_TO_LOINC.put("HDL", "2085-9");
        SD1_CODE_TO_LOINC.put("BUN", "3094-0");
        SD1_CODE_TO_LOINC.put("UA", "3084-1");
        SD1_CODE_TO_LOINC.put("TBA", "14628-2");
        SD1_CODE_TO_LOINC.put("AST", "1920-8");
        SD1_CODE_TO_LOINC.put("ALT", "1742-6");
        SD1_CODE_TO_LOINC.put("Crea", "2160-0");
    }

    /** Synthetic fixture ids, high enough never to collide with catalog data. */
    private static final long FIXTURE_ANALYZER_ID = 990_086_000L;
    private static final long FIXTURE_TEST_BASE_ID = 990_086_001L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestService testService;

    @Autowired
    private BridgeRegistrationService bridgeRegistrationService;

    @Test
    public void sd1SeedChangesetsAreApplied() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM clinlims.databasechangelog"
                        + " WHERE id LIKE 'lis86-%' AND exectype = 'EXECUTED'")) {
            assertTrue(rs.next());
            assertEquals("all four lis86 seed changesets (049) must be EXECUTED in the migrated schema", 4,
                    rs.getInt(1));
        }
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM clinlims.databasechangelog"
                        + " WHERE id LIKE 'lis92-%' AND exectype = 'EXECUTED'")) {
            assertTrue(rs.next());
            assertEquals("all four lis92 seed changesets (050) must be EXECUTED in the migrated schema", 4,
                    rs.getInt(1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void attachTestCodeLoincEmitsTheLiveSd1PairsFromRealDb() throws SQLException {
        try {
            seedFixture();

            Map<String, Object> payload = new LinkedHashMap<>();
            bridgeRegistrationService.attachTestCodeLoinc(payload, Long.toString(FIXTURE_ANALYZER_ID));

            Map<String, String> codeToLoinc = (Map<String, String>) payload.get("testCodeLoinc");
            assertNotNull("attachTestCodeLoinc must always attach a testCodeLoinc map", codeToLoinc);
            assertEquals("SD1 code -> LOINC map must carry exactly the live panel", SD1_CODE_TO_LOINC.size(),
                    codeToLoinc.size());
            for (Map.Entry<String, String> e : SD1_CODE_TO_LOINC.entrySet()) {
                assertEquals("SD1 code " + e.getKey() + " must map to its LOINC", e.getValue(),
                        codeToLoinc.get(e.getKey()));
                assertTrue("getTestsByLoincCode(" + e.getValue() + ") must resolve the seeded test",
                        testService.getTestsByLoincCode(e.getValue()).size() >= 1);
            }
        } finally {
            cleanupFixture();
        }
    }

    /**
     * Insert a fixture analyzer + live-panel LOINC-carrying tests + code mappings
     * (auto-committed).
     */
    private void seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // resolve the bare UNACCENT() in the test-table normalized-description trigger
            st.execute("SET search_path TO clinlims, public");
            st.executeUpdate("INSERT INTO clinlims.analyzer"
                    + " (id, name, description, is_active, status, protocol_version, identifier_pattern, last_updated)"
                    + " VALUES (" + FIXTURE_ANALYZER_ID
                    + ", 'SMT-SD1 IT Fixture', 'LIS-92 test fixture', true, 'SETUP', 'HL7_V2_3_1',"
                    + " '^SMT-SD1$', now())");
            int i = 0;
            for (Map.Entry<String, String> e : SD1_CODE_TO_LOINC.entrySet()) {
                long testId = FIXTURE_TEST_BASE_ID + i++;
                st.executeUpdate("INSERT INTO clinlims.test"
                        + " (id, name, description, loinc, is_active, orderable, guid, lastupdated) VALUES (" + testId
                        + ", 'SD1IT-" + e.getKey() + "', 'SD1IT-" + e.getKey() + "', '" + e.getValue()
                        + "', 'Y', true, gen_random_uuid()::varchar, now())");
                st.executeUpdate("INSERT INTO clinlims.analyzer_test_map"
                        + " (analyzer_id, analyzer_test_name, test_id, last_updated) VALUES (" + FIXTURE_ANALYZER_ID
                        + ", '" + e.getKey() + "', " + testId + ", now())");
            }
        }
    }

    /**
     * Remove the fixture rows (child rows first for the FK) regardless of assertion
     * outcome.
     */
    private void cleanupFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM clinlims.analyzer_test_map WHERE analyzer_id = " + FIXTURE_ANALYZER_ID);
            st.executeUpdate("DELETE FROM clinlims.test WHERE id BETWEEN " + FIXTURE_TEST_BASE_ID + " AND "
                    + (FIXTURE_TEST_BASE_ID + SD1_CODE_TO_LOINC.size() - 1));
            st.executeUpdate("DELETE FROM clinlims.analyzer WHERE id = " + FIXTURE_ANALYZER_ID);
        }
    }
}
