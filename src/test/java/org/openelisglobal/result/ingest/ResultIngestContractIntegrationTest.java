package org.openelisglobal.result.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LIS-15 / S1.3 — Append-only Result store, raw + normalized side by side, via
 * the ingest contract.
 *
 * <p>
 * Where
 * {@link org.openelisglobal.result.ResultVersionAppendOnlyIntegrationTest}
 * (S0.5) and
 * {@link org.openelisglobal.normalization.VendorCodeNormalizationIntegrationTest}
 * (S0.6) proved the schema and triggers by driving <em>raw JDBC</em>, this
 * slice proves the seam the edge actually targets: the
 * {@link ResultIngestService} contract. The edge hands core a normalized
 * observation (ADR-0006); the contract persists it through the real Result
 * persistence path, so the append-only {@code clinlims.result_version} spine
 * records the write — no raw SQL.
 *
 * <ol>
 * <li><b>Raw beside normalized, via the contract.</b> {@code ingest} returns a
 * persisted result id whose {@code clinlims.result} row carries {@code value}
 * and the analyzer-native {@code raw_code}/{@code raw_unit} alongside the
 * normalized {@code loinc}/{@code ucum_value}/{@code status}.</li>
 * <li><b>It lands in the append-only store.</b> The contract write
 * auto-appended version 1 (the {@code result_append_version} trigger, LIS-7 /
 * S0.5), and that version is immutable — a direct mutation is rejected by the
 * append-only guard, so the ingest landed in a no-last-writer-wins spine.</li>
 * <li><b>Tolerant ingest, on the real wire shape.</b> A partially-normalized
 * observation parsed from the <em>actual edge-emitted JSON</em> (not a
 * hand-built value object) still persists — raw captured; an unmapped LOINC
 * persists as the edge's empty string {@code ""} (never null — the edge cannot
 * emit null per the shared {@code ingest-contract.schema.json}); a mapped UCUM
 * unit is populated; status {@code PARTIAL} — mirroring the edge normalizer's
 * status vocabulary.</li>
 * </ol>
 *
 * <p>
 * Mirrors the S0.5/S0.6 harness: {@link BaseWebContextSensitiveTest} commits
 * (propagation {@code NOT_SUPPORTED}), so each ingested {@code result} row is
 * dropped in {@link #cleanUp()} (the append-only guard is on
 * {@code result_version}, not {@code result}; the immutable version snapshots
 * are left to the ephemeral container, as in S0.5).
 */
public class ResultIngestContractIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private ResultIngestService resultIngestService;

    @Autowired
    private DataSource dataSource;

    private final List<String> ingestedResultIds = new ArrayList<>();

    @After
    public void cleanUp() throws SQLException {
        for (String id : ingestedResultIds) {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM clinlims.result WHERE id = " + id);
            }
        }
    }

    @Test
    public void ingestPersistsRawBesideNormalized_andAppendsAnImmutableVersion() throws Exception {
        // a fully-normalized observation as the edge hands it over (ADR-0006): the
        // analyzer-native GLU/mg/dL captured beside the LOINC/UCUM it normalized to.
        NormalizedObservation observation = new NormalizedObservation("5.2", "GLU", "mg/dL", "2345-7", "mg/dL",
                "NORMALIZED");

        String resultId = resultIngestService.ingest(observation);
        assertNotNull("the ingest contract returns the persisted result id", resultId);
        ingestedResultIds.add(resultId);

        // CLAIM 1: one result row carries the raw analyzer code/unit BESIDE the
        // normalized LOINC/UCUM + status — persisted through the ingest contract.
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT value, raw_code, raw_unit, loinc, ucum_value, status"
                        + " FROM clinlims.result WHERE id = " + resultId)) {
            assertTrue("the ingested result row must exist", rs.next());
            assertEquals("5.2", rs.getString("value"));
            assertEquals("raw analyzer code preserved", "GLU", rs.getString("raw_code"));
            assertEquals("raw analyzer unit preserved", "mg/dL", rs.getString("raw_unit"));
            assertEquals("normalized LOINC persisted", "2345-7", rs.getString("loinc"));
            assertEquals("normalized UCUM persisted", "mg/dL", rs.getString("ucum_value"));
            assertEquals("normalization status persisted", "NORMALIZED", rs.getString("status"));
        }

        // CLAIM 2: the contract wrote through the append-only Result store — the insert
        // auto-appended version 1 via the result_append_version trigger (LIS-7 / S0.5).
        long versionId = onlyVersionId(resultId, 1);

        // CLAIM 2 (cont.): that version is immutable — a direct UPDATE is rejected by
        // the append-only guard, so the contract's write landed in a no-LWW spine.
        SQLException rejected = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE clinlims.result_version SET value = 'X' WHERE id = " + versionId);
            }
        });
        assertTrue("rejection should come from the append-only guard, was: " + rejected.getMessage(),
                String.valueOf(rejected.getMessage()).toLowerCase().contains("append-only"));
    }

    @Test
    public void ingestPersistsUnmappedLoincAsEmptyString_fromTheEmittedJson() throws Exception {
        // Drive the contract from the real edge-emitted JSON.
        NormalizedObservation observation = readEmittedDto("edge-emitted-unmapped.json");

        String resultId = resultIngestService.ingest(observation);
        assertNotNull(resultId);
        ingestedResultIds.add(resultId);

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT raw_code, raw_unit, loinc, ucum_value, status"
                        + " FROM clinlims.result WHERE id = " + resultId)) {
            assertTrue("the ingested result row must exist", rs.next());
            assertEquals("raw analyzer code preserved", "99XYZ", rs.getString("raw_code"));
            assertEquals("raw analyzer unit preserved", "K/uL", rs.getString("raw_unit"));
            // An unmapped LOINC persists as "" (the edge wire value), never null.
            assertEquals("unmapped LOINC persists as the edge's empty string, not null", "", rs.getString("loinc"));
            assertEquals("the mapped UCUM unit persists", "10*3/uL", rs.getString("ucum_value"));
            assertEquals("status carried from the edge normalizer", "PARTIAL", rs.getString("status"));
        }
    }

    // Parse a committed edge-emitted ingest DTO into a NormalizedObservation.
    private NormalizedObservation readEmittedDto(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull("missing edge-emitted contract fixture: " + resource, in);
            JsonNode dto = new ObjectMapper().readTree(in);
            String value = dto.get("value").asText();
            String rawCode = dto.get("rawCode").asText();
            String rawUnit = dto.get("rawUnit").asText();
            String loinc = dto.get("loinc").asText();
            String ucumValue = dto.get("ucumValue").asText();
            String status = dto.get("status").asText();
            return new NormalizedObservation(value, rawCode, rawUnit, loinc, ucumValue, status);
        }
    }

    /**
     * The single result_version id for (resultId, versionNumber); fails if absent.
     */
    private long onlyVersionId(String resultId, int versionNumber) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT id FROM clinlims.result_version WHERE result_id = " + resultId
                        + " AND version_number = " + versionNumber)) {
            assertTrue("the append-only store must hold version " + versionNumber + " for the ingested result",
                    rs.next());
            return rs.getLong("id");
        }
    }
}
