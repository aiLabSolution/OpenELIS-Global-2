package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.test.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-86 / S2.10 — proves the Seamaty SD1 biochem code -> LOINC seed (Liquibase
 * changeset {@code 049-sd1-loinc-seed.xml}) makes end-to-end SD1 LOINC
 * ingestion resolve in production, against the migrated schema.
 *
 * <p>
 * Three claims, all the seed must satisfy for a replayed SD1 ORU^R01 to carry
 * the correct LOINC end-to-end:
 *
 * <ol>
 * <li><b>OE2 binds inbound SD1 results by LOINC.</b> Each of the six target
 * LOINCs resolves to a {@code clinlims.test} row
 * ({@code TestService.getTestsByLoincCode}) — the lookup
 * {@code AnalyzerFhirImportController.resolveLoincTest} uses to stage an
 * inbound Observation.</li>
 * <li><b>The SD1 analyzer + its six code mappings are seeded.</b> A
 * {@code Seamaty SD1} analyzer exists with one {@code analyzer_test_map} row
 * per SD1 code (GLU/BUN/CREA/AST/ALT/TP).</li>
 * <li><b>The bridge code -> LOINC push resolves.</b>
 * {@link BridgeRegistrationService#attachTestCodeLoinc} (the map OE2 pushes so
 * the bridge can LOINC-code each Observation it emits) yields exactly the six
 * SD1 code -> LOINC pairs.</li>
 * </ol>
 *
 * <p>
 * Mirrors
 * {@link org.openelisglobal.normalization.VendorCodeNormalizationIntegrationTest}:
 * the seed rows are present because the harness applies the full Liquibase
 * changelog (incl. 049); the analyzer/test/analyzer_test_map tables are not in
 * the fixture-reset set, so the seed persists across the shared container.
 */
public class Sd1LoincSeedIntegrationTest extends BaseWebContextSensitiveTest {

    /** SD1 wire code (OBX-3.1) -> target LOINC, in panel order. */
    private static final Map<String, String> SD1_CODE_TO_LOINC = new LinkedHashMap<>();

    static {
        SD1_CODE_TO_LOINC.put("GLU", "2345-7");
        SD1_CODE_TO_LOINC.put("BUN", "3094-0");
        SD1_CODE_TO_LOINC.put("CREA", "2160-0");
        SD1_CODE_TO_LOINC.put("AST", "1920-8");
        SD1_CODE_TO_LOINC.put("ALT", "1742-6");
        SD1_CODE_TO_LOINC.put("TP", "2885-2");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestService testService;

    @Autowired
    private BridgeRegistrationService bridgeRegistrationService;

    @Test
    public void everyTargetLoincResolvesToASeededTest() {
        for (Map.Entry<String, String> e : SD1_CODE_TO_LOINC.entrySet()) {
            String loinc = e.getValue();
            List<org.openelisglobal.test.valueholder.Test> tests = testService.getTestsByLoincCode(loinc);
            assertNotNull("getTestsByLoincCode(" + loinc + ") returned null", tests);
            assertTrue("no clinlims.test row carries LOINC " + loinc + " (" + e.getKey()
                    + ") — seed 049 did not populate it", tests.size() >= 1);
        }
    }

    @Test
    public void seamatySd1AnalyzerHasOneMappingPerCode() throws SQLException {
        String sd1Id = seamatySd1AnalyzerId();
        assertNotNull("Seamaty SD1 analyzer was not seeded", sd1Id);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT count(*) FROM clinlims.analyzer_test_map WHERE analyzer_id = ?");) {
            ps.setLong(1, Long.parseLong(sd1Id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Seamaty SD1 must have exactly six code mappings", 6, rs.getInt(1));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void attachTestCodeLoincEmitsTheSixSd1Pairs() throws SQLException {
        String sd1Id = seamatySd1AnalyzerId();
        assertNotNull("Seamaty SD1 analyzer was not seeded", sd1Id);

        Map<String, Object> payload = new LinkedHashMap<>();
        bridgeRegistrationService.attachTestCodeLoinc(payload, sd1Id);

        Map<String, String> codeToLoinc = (Map<String, String>) payload.get("testCodeLoinc");
        assertNotNull("attachTestCodeLoinc must always attach a testCodeLoinc map", codeToLoinc);
        assertEquals("SD1 code -> LOINC map must carry exactly six pairs", 6, codeToLoinc.size());
        for (Map.Entry<String, String> e : SD1_CODE_TO_LOINC.entrySet()) {
            assertEquals("SD1 code " + e.getKey() + " must map to its LOINC", e.getValue(),
                    codeToLoinc.get(e.getKey()));
        }
    }

    /**
     * @return the seeded Seamaty SD1 analyzer id (as a String), or null if absent.
     */
    private String seamatySd1AnalyzerId() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT id FROM clinlims.analyzer WHERE name = 'Seamaty SD1'");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("id") : null;
        }
    }
}
