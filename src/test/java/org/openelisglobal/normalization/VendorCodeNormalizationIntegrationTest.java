package org.openelisglobal.normalization;

import static org.junit.Assert.assertEquals;
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
 * LIS-8 / S0.6 — Seed LOINC/UCUM reference tables and prove one vendor code
 * maps end-to-end onto a {@code clinlims.result} row.
 *
 * <p>
 * Proven end-to-end at the database layer against the migrated schema
 * (Liquibase changeset {@code 048-loinc-ucum-seed.xml}):
 *
 * <ol>
 * <li><b>The LOINC/UCUM reference seed loads.</b> The normalization reference
 * {@code clinlims.vendor_code_mapping} carries one seeded row keyed by the
 * analyzer-native observation code {@code GLU} that resolves to both a LOINC
 * code ({@code 2345-7}, glucose) and a UCUM unit ({@code mg/dL}) — so a vendor
 * code maps to LOINC + UCUM on the one self-contained reference row.</li>
 * <li><b>The mapping reaches a Result row.</b> Resolving {@code GLU} through
 * the seed yields {@code (loinc, ucum)}; writing those onto a
 * {@code clinlims.result} alongside the analyzer-native {@code raw_code} /
 * {@code raw_unit} (the LIS-7 / S0.5 {@code 047-result-shape} columns) persists
 * a row where the raw observation and its normalized LOINC/UCUM form coexist —
 * the normalization tracer-bullet plan §0 / §5.1 calls for.</li>
 * </ol>
 *
 * <p>
 * Mirrors
 * {@link org.openelisglobal.result.ResultVersionAppendOnlyIntegrationTest}
 * (LIS-7 / S0.5): drives raw JDBC against the migrated schema, scoping the
 * result write to a single synthetic {@code result.id} so the test is
 * independent of any other rows in the shared container. The seed row is
 * present because the test harness applies the full Liquibase changelog (incl.
 * {@code 048}); the assertion targets only
 * {@code clinlims.vendor_code_mapping}, the new table the harness does not
 * reset — not the canonical reference tables (e.g. {@code unit_of_measure}) it
 * reloads between tests.
 */
public class VendorCodeNormalizationIntegrationTest extends BaseWebContextSensitiveTest {

    /** A synthetic result id high enough to never collide with fixture data. */
    private static final long RESULT_ID = 999_000_801L;

    /** The seeded analyzer-native (vendor) observation code (glucose). */
    private static final String VENDOR_SOURCE = "ANALYZER";
    private static final String VENDOR_CODE = "GLU";
    private static final String EXPECTED_LOINC = "2345-7";
    private static final String EXPECTED_UCUM = "mg/dL";

    @Autowired
    private DataSource dataSource;

    @Test
    public void seededVendorCodeResolvesToLoincAndUcum_andMapsOntoAResultRow() throws Exception {
        // CLAIM 1a: the LOINC/UCUM reference seed loaded — the vendor code resolves
        // to a LOINC code + UCUM unit on clinlims.vendor_code_mapping.
        String[] resolved = resolveVendorCode(VENDOR_SOURCE, VENDOR_CODE);
        assertTrue("seeded vendor_code_mapping row for " + VENDOR_SOURCE + "/" + VENDOR_CODE + " must exist",
                resolved != null);
        String loinc = resolved[0];
        String ucum = resolved[1];
        assertEquals("vendor code maps to LOINC", EXPECTED_LOINC, loinc);
        assertEquals("vendor unit maps to UCUM", EXPECTED_UCUM, ucum);

        // --- act: map the vendor observation onto a Result row, raw beside normalized
        // ---
        insertNormalizedResult(RESULT_ID, "5.2", VENDOR_CODE, "mg/dL", loinc, ucum, "NORMALIZED");

        // CLAIM 2: one result row carries the analyzer-native raw_code/raw_unit beside
        // the normalized loinc/ucum_value/status resolved from the seed.
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT value, raw_code, raw_unit, loinc, ucum_value, status"
                        + " FROM clinlims.result WHERE id = " + RESULT_ID)) {
            assertTrue("result row must exist", rs.next());
            assertEquals("5.2", rs.getString("value"));
            assertEquals("raw analyzer code preserved", VENDOR_CODE, rs.getString("raw_code"));
            assertEquals("raw analyzer unit preserved", "mg/dL", rs.getString("raw_unit"));
            assertEquals("normalized LOINC from the seed", EXPECTED_LOINC, rs.getString("loinc"));
            assertEquals("normalized UCUM from the seed", EXPECTED_UCUM, rs.getString("ucum_value"));
            assertEquals("normalization status", "NORMALIZED", rs.getString("status"));
        }
    }

    /**
     * Resolve a vendor code through the seed; returns {loinc, ucum} or null if
     * absent.
     */
    private String[] resolveVendorCode(String source, String code) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT loinc, ucum_code FROM clinlims.vendor_code_mapping"
                        + " WHERE source = ? AND vendor_code = ? AND is_active = 'Y'")) {
            ps.setString(1, source);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new String[] { rs.getString("loinc"), rs.getString("ucum_code") };
            }
        }
    }

    private void insertNormalizedResult(long id, String value, String rawCode, String rawUnit, String loinc,
            String ucumValue, String status) throws SQLException {
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
}
