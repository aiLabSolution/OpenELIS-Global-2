package org.openelisglobal.result.ingest;

/**
 * The ingest contract (LIS-15 / S1.3): persist a normalized observation from
 * the edge into the append-only Result store, raw beside normalized.
 *
 * <p>
 * One observation &rarr; one {@code clinlims.result} row carrying {@code value}
 * and the analyzer-native {@code raw_code}/{@code raw_unit} alongside the
 * normalized {@code loinc}/{@code ucum_value}/{@code status}. Persisting
 * through the core Result persistence path means the append-only
 * {@code clinlims.result_version} spine (LIS-7 / S0.5) records the write
 * automatically &mdash; so the edge lands results in a no-last-writer-wins
 * store through this single seam, independent of how messages reach the edge
 * (the S1.0 transport substrate).
 */
public interface ResultIngestService {

    /**
     * Persist one normalized observation as a new append-only Result row.
     *
     * @param observation the edge's normalized intermediate row (raw beside
     *                    normalized)
     * @return the id of the persisted {@code clinlims.result} row
     */
    String ingest(NormalizedObservation observation);
}
