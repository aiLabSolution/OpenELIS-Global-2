package org.openelisglobal.analyzer.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
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

/**
 * Guards the LIS-272 durable MAGLUMI X3 seed (liquibase 063).
 *
 * <p>
 * The LOINC checks here are deliberately NOT string-presence assertions. The
 * pre-LIS-299 mappings were wrong-axis on two of three analytes, and a test
 * that merely asserts "some LOINC is mapped" passes just as happily with those
 * wrong codes in place. So
 * {@link #seededLoincPropertyAxisMatchesTheReportedUnit()} resolves each seeded
 * code to its LOINC property axis and each seeded unit to the axis it
 * expresses, and requires the two to agree.
 */
public class MaglumiX3SeedMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:14.4");

    private static final String CHANGELOG = "liquibase/lis272-x3-seed-test.xml";
    private static final String ANALYZER = "SNIBE MAGLUMI X3";

    /**
     * LOINC property axis per code, from loinc.org. MCnc = mass/volume, SCnc =
     * moles/volume, ACnc = arbitrary (IU)/volume. The two rejected codes are listed
     * so the axis check can prove they are incompatible rather than merely absent.
     */
    private static final Map<String, String> LOINC_AXIS = Map.of("14928-6", "SCnc", // T3 Free [Moles/volume]
            "3024-7", "MCnc", // T4 Free [Mass/volume]
            "3016-3", "ACnc", // Thyrotropin [Units/volume]
            "3051-0", "MCnc", // T3 Free [Mass/volume] — wrong axis for pmol/L
            "14920-3", "SCnc"); // T4 Free [Moles/volume] — wrong axis for ng/dL

    /** The axis each reported unit expresses. */
    private static final Map<String, String> UNIT_AXIS = Map.of("pmol/L", "SCnc", "ng/dL", "MCnc", "uIU/mL", "ACnc");

    /**
     * Bench truth: wire code -> reported unit (LIS-75 captures, LIS-38 confirmed).
     */
    private static final Map<String, String> WIRE_UNITS = new LinkedHashMap<>();

    static {
        WIRE_UNITS.put("FT3", "pmol/L");
        WIRE_UNITS.put("FT4 II", "ng/dL");
        WIRE_UNITS.put("TSH II", "uIU/mL");
    }

    private static Connection connection;
    private static Liquibase liquibase;
    private static Database database;

    @BeforeClass
    public static void migrate() throws Exception {
        POSTGRES.start();
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(true);
        database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts());
    }

    @AfterClass
    public static void stop() throws Exception {
        if (database != null) {
            database.close();
        }
        if (connection != null) {
            connection.close();
        }
        POSTGRES.stop();
    }

    @Test
    public void seedsTheAnalyzerRowWithTheProfileIdentity() throws Exception {
        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
        assertEquals("(?i)(maglumi|snibe)",
                scalar("SELECT identifier_pattern FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
        assertEquals("IMMUNOASSAY",
                scalar("SELECT analyzer_type FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
        assertNotNull(scalar("SELECT fhir_uuid FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
    }

    /**
     * Transport stays operator config, as with the EDAN seeds. A hardcoded site IP
     * would be wrong everywhere; what makes the OE-wins registry sync safe is the
     * seeded map and QC rules, not a seeded address.
     */
    @Test
    public void doesNotSeedTransport() throws Exception {
        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"
                + " AND ip_address IS NULL AND port IS NULL"));
    }

    /**
     * The wire codes carry a literal " II" suffix on two of three analytes and the
     * bridge's lookup is exact-match, so the un-suffixed spellings resolve nothing.
     */
    @Test
    public void mapsTheLiteralWireCodesIncludingTheIiSuffixes() throws Exception {
        assertEquals(3, count("SELECT COUNT(*) FROM clinlims.analyzer_test_map m"
                + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "'"));
        for (String code : WIRE_UNITS.keySet()) {
            assertEquals("wire code " + code + " should be mapped exactly once", 1, count(
                    "SELECT COUNT(*) FROM clinlims.analyzer_test_map m JOIN clinlims.analyzer a ON a.id = m.analyzer_id"
                            + " WHERE a.name = '" + ANALYZER + "' AND m.analyzer_test_name = '" + code + "'"));
        }
        assertEquals("bare 'FT4' must not be mapped — it would never match the wire", 0,
                count("SELECT COUNT(*) FROM clinlims.analyzer_test_map m"
                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "'"
                        + " AND m.analyzer_test_name IN ('FT4', 'TSH')"));
    }

    /**
     * No mapping may point at a null test_id — that is what breaks QC on ingest.
     */
    @Test
    public void everyMappingResolvesToATest() throws Exception {
        assertEquals(0,
                count("SELECT COUNT(*) FROM clinlims.analyzer_test_map m"
                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "'"
                        + " AND m.test_id IS NULL"));
    }

    /**
     * The core regression. Each seeded wire code resolves to a Test whose LOINC
     * property axis must agree with the axis of the unit the analyzer reports.
     * Reseeding 3051-0 for FT3 or 14920-3 for FT4 II fails here, which is the
     * defect LIS-299 corrected.
     */
    @Test
    public void seededLoincPropertyAxisMatchesTheReportedUnit() throws Exception {
        for (Map.Entry<String, String> wire : WIRE_UNITS.entrySet()) {
            String code = wire.getKey();
            String unit = wire.getValue();
            String loinc = scalar("SELECT t.loinc FROM clinlims.analyzer_test_map m"
                    + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id JOIN clinlims.test t ON t.id = m.test_id"
                    + " WHERE a.name = '" + ANALYZER + "' AND m.analyzer_test_name = '" + code + "'");

            assertNotNull("no LOINC seeded for wire code " + code, loinc);
            String loincAxis = LOINC_AXIS.get(loinc);
            assertNotNull("LOINC " + loinc + " seeded for " + code + " is not in the verified axis table; if this is a"
                    + " deliberate new mapping, add its property from loinc.org rather than deleting this check",
                    loincAxis);
            assertEquals(
                    code + " reports " + unit + " (" + UNIT_AXIS.get(unit) + ") but is mapped to LOINC " + loinc + " ("
                            + loincAxis + ") — a LOINC-keyed consumer reads a wrong-axis value as grossly abnormal",
                    UNIT_AXIS.get(unit), loincAxis);
        }
    }

    /** UCUM must be populated, and uIU/mL is the one non-identity translation. */
    @Test
    public void seedsUcumForEveryReportedUnit() throws Exception {
        assertEquals("pmol/L", ucumFor("pmol/L"));
        assertEquals("ng/dL", ucumFor("ng/dL"));
        assertEquals("u[IU]/mL", ucumFor("uIU/mL"));
    }

    /** Every mapped test must carry the UOM its analyte is reported in. */
    @Test
    public void linksEachMappedTestToItsUnit() throws Exception {
        for (Map.Entry<String, String> wire : WIRE_UNITS.entrySet()) {
            assertEquals("wire code " + wire.getKey() + " should resolve to unit " + wire.getValue(), wire.getValue(),
                    scalar("SELECT u.name FROM clinlims.analyzer_test_map m"
                            + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id"
                            + " JOIN clinlims.test t ON t.id = m.test_id"
                            + " JOIN clinlims.unit_of_measure u ON u.id = t.uom_id WHERE a.name = '" + ANALYZER + "'"
                            + " AND m.analyzer_test_name = '" + wire.getKey() + "'"));
        }
    }

    /**
     * LIS-269: a seeded analyzer row that carries no QC rules pushes qcRules=[] on
     * the registry's full-replace sync and wipes the bridge's QC entry, dropping
     * control specimens into the patient stream. The rules must ship with the row —
     * createQcRulesFromProfile only runs on the REST creation path, which a
     * liquibase seed never traverses.
     */
    @Test
    public void seedsQcRulesAlongsideTheAnalyzerRow() throws Exception {
        assertTrue("a seeded analyzer row with no QC rules would wipe the bridge registry entry",
                count("SELECT COUNT(*) FROM clinlims.analyzer_qc_rule r"
                        + " JOIN clinlims.analyzer a ON a.id = r.analyzer_id WHERE a.name = '" + ANALYZER + "'") > 0);

        assertEquals("the QC discriminator must be active, or QC falls through to the patient stream", 1,
                count("SELECT COUNT(*) FROM clinlims.analyzer_qc_rule r"
                        + " JOIN clinlims.analyzer a ON a.id = r.analyzer_id WHERE a.name = '" + ANALYZER + "'"
                        + " AND r.rule_type = 'FIELD_EQUALS' AND r.target_field = 'O.12' AND r.operand = 'Q'"
                        + " AND r.is_active = true"));

        assertEquals("the unconfirmed calibration prefix must stay inactive so it cannot reclassify a patient specimen",
                1,
                count("SELECT COUNT(*) FROM clinlims.analyzer_qc_rule r"
                        + " JOIN clinlims.analyzer a ON a.id = r.analyzer_id WHERE a.name = '" + ANALYZER + "'"
                        + " AND r.rule_type = 'CALIBRATION_SPECIMEN_ID_PREFIX' AND r.is_active = false"));
    }

    /**
     * LIS-188's changeset 054 is a one-shot UPDATE keyed on analyzer_test_map
     * membership at migration time, so it cannot reach a mapping added afterwards.
     * The fixture leaves a pre-existing catalog TSH test at significant_digits = 2
     * — exactly that case — and 063 cs7 must widen it to raw, or "FT4 II" 1.58
     * ng/dL persists as 1.58 rounded on accept.
     */
    @Test
    public void reappliesRawPrecisionToTestsMappedAfterChangeset054() throws Exception {
        assertEquals("the pre-existing catalog test 054 could not reach must be widened to raw precision", -1,
                Integer.parseInt(scalar("SELECT significant_digits FROM clinlims.test_result WHERE test_id = 900")));
        assertEquals(-1, Integer
                .parseInt(scalar("SELECT significant_digits FROM clinlims.test_result_component WHERE test_id = 900")));

        assertEquals("every X3-mapped numeric result must be at raw precision", 0,
                count("SELECT COUNT(*) FROM clinlims.test_result tr WHERE tr.tst_rslt_type = 'N'"
                        + " AND tr.significant_digits IS DISTINCT FROM -1 AND tr.test_id IN ("
                        + " SELECT m.test_id FROM clinlims.analyzer_test_map m"
                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "')"));
    }

    /** Newly-seeded X3 tests need a numeric result definition to accept against. */
    @Test
    public void givesEachNewlySeededTestANumericResultRow() throws Exception {
        assertEquals(3,
                count("SELECT COUNT(DISTINCT tr.test_id) FROM clinlims.test_result tr"
                        + " JOIN clinlims.analyzer_test_map m ON m.test_id = tr.test_id"
                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id" + " WHERE a.name = '" + ANALYZER
                        + "' AND tr.tst_rslt_type = 'N'"));
    }

    /**
     * The seed reuses a catalog Test that already carries the LOINC rather than
     * inserting a competing row — the same catalog dependency 049/052/053 have.
     */
    @Test
    public void reusesAnExistingCatalogTestRatherThanDuplicatingTheLoinc() throws Exception {
        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.test WHERE loinc = '3016-3'"));
        assertEquals("900",
                scalar("SELECT m.test_id FROM clinlims.analyzer_test_map m"
                        + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "'"
                        + " AND m.analyzer_test_name = 'TSH II'"));
    }

    /** Re-running the changelog must not duplicate any seeded row. */
    @Test
    public void isIdempotent() throws Exception {
        // Simply calling update() again proves nothing: liquibase sees the changesets
        // in
        // DATABASECHANGELOG and skips them, so the seed's own NOT EXISTS / sqlCheck
        // guards are never re-executed — the assertions below would pass even if every
        // guard were deleted. Forget the LIS-272 changesets first so they genuinely
        // re-run against an already-seeded database, which is what idempotency means.
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM databasechangelog WHERE id LIKE 'lis272-0%'");
        }
        liquibase.update(new Contexts());

        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.analyzer WHERE name = '" + ANALYZER + "'"));
        assertEquals(3, count("SELECT COUNT(*) FROM clinlims.analyzer_test_map m"
                + " JOIN clinlims.analyzer a ON a.id = m.analyzer_id WHERE a.name = '" + ANALYZER + "'"));
        assertEquals(2, count("SELECT COUNT(*) FROM clinlims.analyzer_qc_rule r"
                + " JOIN clinlims.analyzer a ON a.id = r.analyzer_id WHERE a.name = '" + ANALYZER + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.unit_of_measure WHERE name = 'uIU/mL'"));
        assertEquals(1, count("SELECT COUNT(*) FROM clinlims.test WHERE loinc = '14928-6'"));
    }

    /**
     * Adopting a catalog Test must link its UOM and nothing else. The fixture's TSH
     * row is deliberately retired (is_active='N', orderable=false), standing for a
     * Test a lab took out of service; an earlier revision of cs2 also set
     * is_active='Y' and orderable=true on every row carrying a seeded LOINC, which
     * would have quietly put it back into service on upgrade — and the rollback,
     * scoped to the X3-* local_code this seed assigns, could not have undone that
     * on an adopted row.
     */
    @Test
    public void retiredCatalogTestIsNotSilentlyReactivated() throws Exception {
        assertEquals("adopting a Test must not re-activate it", "N",
                scalar("SELECT is_active FROM clinlims.test WHERE local_code = 'CATALOG-TSH-Serum'"));
        assertEquals("adopting a Test must not make it orderable again", "f",
                scalar("SELECT orderable FROM clinlims.test WHERE local_code = 'CATALOG-TSH-Serum'"));
        // The adoption itself must still have happened: the UOM is the one field cs2
        // owns.
        assertEquals("uIU/mL", scalar("SELECT u.name FROM clinlims.test t"
                + " JOIN clinlims.unit_of_measure u ON u.id = t.uom_id" + " WHERE t.local_code = 'CATALOG-TSH-Serum'"));
    }

    /**
     * The precision re-pass must not reach beyond this slice. 054's predicate is
     * every analyzer's mapped test; reusing it verbatim would also reset display
     * precision a lab set by hand on unrelated tests, and this changeset's rollback
     * is a no-op.
     */
    @Test
    public void precisionRepassDoesNotTouchTestsOutsideTheX3Map() throws Exception {
        // Test 901 is mapped, but to OTHER ANALYZER. That is the point: an unmapped
        // test
        // would be skipped by the global predicate too, so it could not tell the two
        // apart. This row is reset to -1 by 054's predicate and left alone by the
        // scoped
        // one, which is exactly the difference being asserted.
        assertEquals("another analyzer's hand-set precision must survive the X3 re-pass", "2",
                scalar("SELECT significant_digits FROM clinlims.test_result WHERE test_id = 901"));
        // ...while the X3's own mapped tests are still widened to raw.
        assertEquals("-1", scalar("SELECT significant_digits FROM clinlims.test_result WHERE test_id = 900"));
    }

    private String ucumFor(String unitName) throws SQLException {
        return scalar("SELECT ucum_code FROM clinlims.unit_of_measure WHERE name = '" + unitName + "'");
    }

    private int count(String sql) throws SQLException {
        return Integer.parseInt(scalar(sql));
    }

    private String scalar(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }
}
