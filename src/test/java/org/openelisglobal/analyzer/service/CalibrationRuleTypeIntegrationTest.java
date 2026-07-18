package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-173 / LIS-269 — verifies the CALIBRATION_* rule-type extension
 * ({@code analyzer/004-014-add-calibration-qc-rule-types.xml}) against the
 * migrated schema, at the layer the mock unit tests cannot reach.
 *
 * <p>
 * Canonical-table row content is not asserted directly (sibling integration
 * tests truncate/reload {@code analyzer} via DBUnit — same rationale as
 * {@link Sd1LoincSeedIntegrationTest}). This test verifies what is reliable
 * here:
 *
 * <ol>
 * <li><b>The changesets executed.</b> Both {@code analyzer-014-*} changesets
 * (column widen + CHECK-constraint replacement) are recorded {@code EXECUTED}
 * in {@code databasechangelog} — proving they are wired into
 * {@code liquibase/analyzer/base.xml} and apply cleanly in a from-scratch
 * migration.</li>
 * <li><b>The extended schema accepts CALIBRATION_* end-to-end.</b> A fixture
 * analyzer plus a {@code CALIBRATION_SPECIMEN_ID_PATTERN} rule (31 chars — one
 * over the pre-014 VARCHAR(30)) insert cleanly against the widened column and
 * re-created CHECK constraint, and
 * {@link BridgeRegistrationService#attachQcRules} pushes the rule verbatim from
 * the real DB — the payload contract the bridge's calibration gate (LIS-125)
 * consumes.</li>
 * </ol>
 */
public class CalibrationRuleTypeIntegrationTest extends BaseWebContextSensitiveTest {

    /** Synthetic fixture id, high enough never to collide with catalog data. */
    private static final long FIXTURE_ANALYZER_ID = 990_269_000L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BridgeRegistrationService bridgeRegistrationService;

    @Test
    public void calibrationRuleTypeChangesetsAreApplied() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM clinlims.databasechangelog"
                        + " WHERE id LIKE 'analyzer-014-%' AND exectype = 'EXECUTED'")) {
            assertTrue(rs.next());
            assertEquals("both analyzer-014 changesets (widen column + replace CHECK) must be EXECUTED"
                    + " in the migrated schema", 2, rs.getInt(1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void calibrationRuleSurvivesSchemaAndReachesBridgePayload() throws SQLException {
        try {
            seedFixture();

            Map<String, Object> payload = new LinkedHashMap<>();
            bridgeRegistrationService.attachQcRules(payload, Long.toString(FIXTURE_ANALYZER_ID));

            List<Map<String, Object>> rules = (List<Map<String, Object>>) payload.get("qcRules");
            assertNotNull("attachQcRules must always attach a qcRules list", rules);
            assertEquals(
                    "exactly the two ACTIVE fixture rules must be pushed — the inactive"
                            + " CALIBRATION_SPECIMEN_ID_PREFIX placeholder must never reach the bridge"
                            + " (its inertness is this filter; the bridge honors any rule it receives)",
                    2, rules.size());
            assertEquals("FIELD_EQUALS", rules.get(0).get("ruleType"));
            assertEquals("O.12", rules.get(0).get("targetField"));
            assertEquals("Q", rules.get(0).get("operand"));
            assertEquals("the CALIBRATION_ prefix must survive verbatim to the bridge payload",
                    "CALIBRATION_SPECIMEN_ID_PATTERN", rules.get(1).get("ruleType"));
            assertEquals("^CAL-[A-Z0-9-]+$", rules.get(1).get("operand"));
            for (Map<String, Object> rule : rules) {
                assertTrue("inactive rule leaked into the bridge payload", !"CAL-".equals(rule.get("operand")));
            }
        } finally {
            cleanupFixture();
        }
    }

    /**
     * Insert a fixture analyzer + a baseline QC rule + a CALIBRATION_* rule
     * (auto-committed). The CALIBRATION insert is itself an assertion: it fails on
     * a pre-014 schema (CHECK constraint violation and/or VARCHAR(30) overflow).
     */
    private void seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO clinlims.analyzer"
                    + " (id, name, description, is_active, status, protocol_version, identifier_pattern, last_updated)"
                    + " VALUES (" + FIXTURE_ANALYZER_ID
                    + ", 'Maglumi X3 IT Fixture', 'LIS-269 test fixture', true, 'SETUP', 'ASTM_LIS2_A2',"
                    + " '(?i)maglumi-it-fixture', now())");
            st.executeUpdate("INSERT INTO clinlims.analyzer_qc_rule"
                    + " (id, analyzer_id, rule_type, target_field, operand, is_active, display_order, last_updated)"
                    + " VALUES (gen_random_uuid()::text, " + FIXTURE_ANALYZER_ID
                    + ", 'FIELD_EQUALS', 'O.12', 'Q', true, 1, now())");
            st.executeUpdate("INSERT INTO clinlims.analyzer_qc_rule"
                    + " (id, analyzer_id, rule_type, target_field, operand, is_active, display_order, last_updated)"
                    + " VALUES (gen_random_uuid()::text, " + FIXTURE_ANALYZER_ID
                    + ", 'CALIBRATION_SPECIMEN_ID_PATTERN', NULL, '^CAL-[A-Z0-9-]+$', true, 2, now())");
            // Mirrors the snibe-maglumi-x3 profile's shipped-inactive placeholder: the
            // bridge has no isActive concept and honors any rule it receives, so OE's
            // active-only filter is the ONLY thing keeping this off the wire.
            st.executeUpdate("INSERT INTO clinlims.analyzer_qc_rule"
                    + " (id, analyzer_id, rule_type, target_field, operand, is_active, display_order, last_updated)"
                    + " VALUES (gen_random_uuid()::text, " + FIXTURE_ANALYZER_ID
                    + ", 'CALIBRATION_SPECIMEN_ID_PREFIX', NULL, 'CAL-', false, 3, now())");
        }
    }

    /**
     * Remove the fixture rows (child rows first for the FK) regardless of assertion
     * outcome.
     */
    private void cleanupFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM clinlims.analyzer_qc_rule WHERE analyzer_id = " + FIXTURE_ANALYZER_ID);
            st.executeUpdate("DELETE FROM clinlims.analyzer WHERE id = " + FIXTURE_ANALYZER_ID);
        }
    }
}
