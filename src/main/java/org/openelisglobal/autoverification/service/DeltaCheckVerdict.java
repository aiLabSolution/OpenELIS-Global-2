package org.openelisglobal.autoverification.service;

/**
 * Verdict returned by a {@link DeltaCheckService} for a single incoming
 * result.
 *
 * <p>
 * Semantics for the autoverification gate (LIS-55):
 * <ul>
 * <li>{@link Outcome#PASS} — a prior result existed and the change is within
 * the configured thresholds; the delta leg does not block autorelease.</li>
 * <li>{@link Outcome#FLAGGED} — the change versus the prior result exceeds the
 * configured thresholds; the gate holds the result for human review with
 * {@link #getReason()} recorded.</li>
 * <li>{@link Outcome#NOT_EVALUABLE} — no delta comparison is possible (no
 * prior final result, non-numeric value, or no delta engine installed). A
 * delta check that cannot run is not a delta <i>violation</i>, so this does
 * not block autorelease on its own; the range and QC legs still apply.</li>
 * </ul>
 */
public final class DeltaCheckVerdict {

    public enum Outcome {
        PASS, FLAGGED, NOT_EVALUABLE
    }

    private final Outcome outcome;
    private final String reason;

    private DeltaCheckVerdict(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    public static DeltaCheckVerdict pass() {
        return new DeltaCheckVerdict(Outcome.PASS, null);
    }

    public static DeltaCheckVerdict flagged(String reason) {
        return new DeltaCheckVerdict(Outcome.FLAGGED, reason);
    }

    public static DeltaCheckVerdict notEvaluable(String reason) {
        return new DeltaCheckVerdict(Outcome.NOT_EVALUABLE, reason);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "DeltaCheckVerdict{" + outcome + (reason == null ? "" : ": " + reason) + "}";
    }
}
