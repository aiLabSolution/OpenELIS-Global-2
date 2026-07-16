package org.openelisglobal.autoverification.service;

import java.util.List;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;

/**
 * Autoverification gate (LIS-55 / Stage 5 §S5.4).
 *
 * <p>
 * Composes reference-range gating, Westgard QC-run status (LIS-52) and the
 * delta check ({@link DeltaCheckService}, LIS-54) into a single autorelease
 * decision for analyzer results that have just been accepted. An analysis
 * auto-finalizes (statusId = Finalized + releasedDate — the same status
 * transition the human result-validation path performs, though its release side
 * effects like FHIR export and notifications are LIS-226's scope and gate flag
 * enablement) only if every leg passes; any failure leaves it at
 * TechnicalAcceptance — the normal human-validation queue — with the hold
 * reason recorded as an internal Note.
 */
public interface AutoverificationGateService {

    /**
     * Evaluate every just-persisted analysis in the given groupings and either
     * auto-finalize it or hold it for human review with a recorded reason.
     *
     * <p>
     * No-op unless {@code autoverification.enabled=true}. Only analyses currently
     * at TechnicalAcceptance are considered; everything else (rejected, already
     * finalized) is left untouched.
     *
     * @param sampleGroupings the groupings persisted by the analyzer accept path;
     *                        analyses and results are paired positionally
     * @param sysUserId       the user driving the accept, used for audit trail and
     *                        note attribution
     */
    void evaluateAndFinalize(List<SampleGrouping> sampleGroupings, String sysUserId);
}
