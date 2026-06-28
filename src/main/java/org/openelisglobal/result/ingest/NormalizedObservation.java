package org.openelisglobal.result.ingest;

/**
 * The edge&rarr;core ingest contract's input (LIS-15 / S1.3): one normalized
 * observation ready to land in the append-only Result store.
 *
 * <p>
 * Mirrors the edge normalizer's intermediate row (ADR-0006): the
 * analyzer-native {@code rawCode}/{@code rawUnit} captured <em>beside</em> the
 * normalized LOINC/UCUM form ({@code loinc}/{@code ucumValue}) and a
 * normalization {@code status} ({@code NORMALIZED} / {@code PARTIAL} /
 * {@code UNMAPPED}). The same raw-beside-normalized shape the
 * {@code clinlims.result} row persists (core ADR-0001, LIS-7 / S0.5).
 *
 * <p>
 * Transport-neutral and immutable on purpose: the edge driver is a separate
 * process, so it targets this stable value shape rather than the heavyweight
 * {@code Result} entity. Normalization happens at the edge; core persists what
 * it is handed (sourcing the mapping from {@code clinlims.vendor_code_mapping}
 * is deferred &mdash; ADR-0002 / ADR-0006).
 */
public final class NormalizedObservation {

    private final String value;
    private final String rawCode;
    private final String rawUnit;
    private final String loinc;
    private final String ucumValue;
    private final String status;

    public NormalizedObservation(String value, String rawCode, String rawUnit, String loinc, String ucumValue,
            String status) {
        this.value = value;
        this.rawCode = rawCode;
        this.rawUnit = rawUnit;
        this.loinc = loinc;
        this.ucumValue = ucumValue;
        this.status = status;
    }

    public String getValue() {
        return value;
    }

    public String getRawCode() {
        return rawCode;
    }

    public String getRawUnit() {
        return rawUnit;
    }

    public String getLoinc() {
        return loinc;
    }

    public String getUcumValue() {
        return ucumValue;
    }

    public String getStatus() {
        return status;
    }
}
