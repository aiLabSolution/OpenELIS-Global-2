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
 * LIS-111 / LIS-183 / LIS-187 / LIS-192 — verifies the EDAN H90-series (H99S +
 * H60S) CBC+DIFF code -> LOINC seed (Liquibase changesets
 * {@code 052-edan-cbc-diff-loinc-seed.xml}, {@code 053-edan-h60s-seed.xml},
 * {@code 055-edan-pdw-loinc-seed.xml} and
 * {@code 056-edan-h60s-full-panel-seed.xml}) against the migrated schema — the
 * EDAN analogue of {@link Sd1LoincSeedIntegrationTest}.
 *
 * <p>
 * The seeds' own rows land in <b>canonical</b> tables ({@code test},
 * {@code analyzer}, {@code analyzer_test_map}) which sibling integration tests
 * truncate/reload via DBUnit, so they are <b>not</b> reliably present when an
 * arbitrary test runs. This test verifies the two things that <i>are</i>
 * reliable:
 *
 * <ol>
 * <li><b>The seeds executed.</b> All {@code lis183-*} (052), {@code lis187-*}
 * (053), {@code lis192-*} (055) and {@code lis111-*} (056) changesets are
 * recorded {@code EXECUTED} in {@code databasechangelog} (liquibase's own
 * tracking table, which fixtures never reset) — proving 052/053/055/056 are
 * wired into the changelog and apply cleanly in a from-scratch migration.</li>
 * <li><b>The production resolver works over the real DB for the full panel.</b>
 * With a fixture EDAN analyzer + the full H90-series panel of LOINC-carrying
 * Test rows + code mappings shaped like the seeded output,
 * {@link BridgeRegistrationService#attachTestCodeLoinc} (the map OE2 pushes so
 * the bridge can LOINC-code each Observation) yields exactly the EDAN code ->
 * LOINC pairs, and each LOINC resolves via
 * {@code TestService.getTestsByLoincCode} — exercising the real DAO / HQL /
 * Test.loinc chain the H99S bench proved on hardware (104/104, 2026-07-08) and
 * that LIS-111 requires the H60S to share.</li>
 * </ol>
 */
public class EdanLoincSeedIntegrationTest extends BaseWebContextSensitiveTest {

    /**
     * Full EDAN H90-series wire code (OBX-4) -> target LOINC, exactly the panel
     * that {@code 056-edan-h60s-full-panel-seed.xml} maps onto the EDAN H60S (= the
     * H99S panel of 052 cs3 + PDW of 055). Both RDW-CV/RDW_CV and RDW-SD/RDW_SD
     * separator spellings are present, matching the seed.
     */
    private static final Map<String, String> EDAN_CODE_TO_LOINC = new LinkedHashMap<>();

    static {
        EDAN_CODE_TO_LOINC.put("WBC", "6690-2");
        EDAN_CODE_TO_LOINC.put("RBC", "789-8");
        EDAN_CODE_TO_LOINC.put("HGB", "718-7");
        EDAN_CODE_TO_LOINC.put("HCT", "4544-3");
        EDAN_CODE_TO_LOINC.put("MCV", "787-2");
        EDAN_CODE_TO_LOINC.put("MCH", "785-6");
        EDAN_CODE_TO_LOINC.put("MCHC", "786-4");
        EDAN_CODE_TO_LOINC.put("PLT", "777-3");
        EDAN_CODE_TO_LOINC.put("RDW-CV", "788-0");
        EDAN_CODE_TO_LOINC.put("RDW_CV", "788-0");
        EDAN_CODE_TO_LOINC.put("MPV", "32623-1");
        EDAN_CODE_TO_LOINC.put("NEU#", "751-8");
        EDAN_CODE_TO_LOINC.put("NEU%", "770-8");
        EDAN_CODE_TO_LOINC.put("LYM#", "731-0");
        EDAN_CODE_TO_LOINC.put("LYM%", "736-9");
        EDAN_CODE_TO_LOINC.put("MON#", "742-7");
        EDAN_CODE_TO_LOINC.put("MON%", "5905-5");
        EDAN_CODE_TO_LOINC.put("EOS#", "711-2");
        EDAN_CODE_TO_LOINC.put("EOS%", "713-8");
        EDAN_CODE_TO_LOINC.put("BAS#", "704-7");
        EDAN_CODE_TO_LOINC.put("BAS%", "706-2");
        EDAN_CODE_TO_LOINC.put("NRBC#", "771-6");
        EDAN_CODE_TO_LOINC.put("NRBC%", "58413-6");
        EDAN_CODE_TO_LOINC.put("RDW-SD", "21000-5");
        EDAN_CODE_TO_LOINC.put("RDW_SD", "21000-5");
        EDAN_CODE_TO_LOINC.put("PCT", "51637-7");
        EDAN_CODE_TO_LOINC.put("IMG#", "53115-2");
        EDAN_CODE_TO_LOINC.put("IMG%", "38518-7");
        EDAN_CODE_TO_LOINC.put("RET#", "60474-4");
        EDAN_CODE_TO_LOINC.put("RET%", "17849-1");
        EDAN_CODE_TO_LOINC.put("PDW", "32207-3");
    }

    /** Synthetic fixture ids, high enough never to collide with catalog data. */
    private static final long FIXTURE_ANALYZER_ID = 990_111_000L;
    private static final long FIXTURE_TEST_BASE_ID = 990_111_001L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestService testService;

    @Autowired
    private BridgeRegistrationService bridgeRegistrationService;

    @Test
    public void edanSeedChangesetsAreApplied() throws SQLException {
        assertExecutedCount("lis183-%", 3, "052-edan-cbc-diff-loinc-seed.xml (H99S analyzer + tests + 30-code map)");
        assertExecutedCount("lis187-%", 2, "053-edan-h60s-seed.xml (H60S analyzer + CBC-6 map)");
        assertExecutedCount("lis192-%", 2, "055-edan-pdw-loinc-seed.xml (PDW test + H99S map)");
        assertExecutedCount("lis111-%", 1, "056-edan-h60s-full-panel-seed.xml (H60S full-panel map)");
    }

    private void assertExecutedCount(String idLike, int expected, String what) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM clinlims.databasechangelog" + " WHERE id LIKE '"
                        + idLike + "' AND exectype = 'EXECUTED'")) {
            assertTrue(rs.next());
            assertEquals(what + " must be EXECUTED in the migrated schema", expected, rs.getInt(1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void attachTestCodeLoincEmitsTheFullEdanPanelFromRealDb() throws SQLException {
        try {
            seedFixture();

            Map<String, Object> payload = new LinkedHashMap<>();
            bridgeRegistrationService.attachTestCodeLoinc(payload, Long.toString(FIXTURE_ANALYZER_ID));

            Map<String, String> codeToLoinc = (Map<String, String>) payload.get("testCodeLoinc");
            assertNotNull("attachTestCodeLoinc must always attach a testCodeLoinc map", codeToLoinc);
            assertEquals("EDAN code -> LOINC map must carry exactly the full H90-series panel",
                    EDAN_CODE_TO_LOINC.size(), codeToLoinc.size());
            for (Map.Entry<String, String> e : EDAN_CODE_TO_LOINC.entrySet()) {
                assertEquals("EDAN code " + e.getKey() + " must map to its LOINC", e.getValue(),
                        codeToLoinc.get(e.getKey()));
                assertTrue("getTestsByLoincCode(" + e.getValue() + ") must resolve the seeded test",
                        testService.getTestsByLoincCode(e.getValue()).size() >= 1);
            }
        } finally {
            cleanupFixture();
        }
    }

    /**
     * Insert a fixture EDAN analyzer + full-panel LOINC-carrying tests + code
     * mappings (auto-committed). Distinct LOINCs get one Test row each; the two
     * codes that share a LOINC (RDW-CV/RDW_CV, RDW-SD/RDW_SD) reuse that Test.
     */
    private void seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // resolve the bare UNACCENT() in the test-table normalized-description trigger
            st.execute("SET search_path TO clinlims, public");
            st.executeUpdate("INSERT INTO clinlims.analyzer"
                    + " (id, name, description, is_active, status, protocol_version, identifier_pattern, last_updated)"
                    + " VALUES (" + FIXTURE_ANALYZER_ID
                    + ", 'EDAN H60S IT Fixture', 'LIS-111 test fixture', true, 'SETUP', 'HL7_V2_3_1',"
                    + " '^EDAN H60S$', now())");
            // One Test per distinct LOINC (shared-LOINC codes reuse it); one map row per
            // code.
            Map<String, Long> loincToTestId = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<String, String> e : EDAN_CODE_TO_LOINC.entrySet()) {
                String loinc = e.getValue();
                Long testId = loincToTestId.get(loinc);
                if (testId == null) {
                    testId = FIXTURE_TEST_BASE_ID + i++;
                    loincToTestId.put(loinc, testId);
                    st.executeUpdate("INSERT INTO clinlims.test"
                            + " (id, name, description, loinc, is_active, orderable, guid, lastupdated) VALUES ("
                            + testId + ", 'EDANIT-" + loinc + "', 'EDANIT-" + loinc + "', '" + loinc
                            + "', 'Y', true, gen_random_uuid()::varchar, now())");
                }
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
        // One Test row per DISTINCT LOINC was inserted (shared-LOINC codes reuse it),
        // so the id range spans FIXTURE_TEST_BASE_ID .. +(distinctLoincs-1) — not the
        // 31-code panel size, which would over-reach past the inserted rows.
        long distinctLoincs = EDAN_CODE_TO_LOINC.values().stream().distinct().count();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM clinlims.analyzer_test_map WHERE analyzer_id = " + FIXTURE_ANALYZER_ID);
            st.executeUpdate("DELETE FROM clinlims.test WHERE id BETWEEN " + FIXTURE_TEST_BASE_ID + " AND "
                    + (FIXTURE_TEST_BASE_ID + distinctLoincs - 1));
            st.executeUpdate("DELETE FROM clinlims.analyzer WHERE id = " + FIXTURE_ANALYZER_ID);
        }
    }
}
