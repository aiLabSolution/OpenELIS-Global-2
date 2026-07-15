package org.openelisglobal.autoverification.service;

import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.result.valueholder.Result;

/**
 * SPI consumed by the autoverification gate (LIS-55) for the delta-check leg.
 *
 * <p>
 * The real engine — comparing the incoming value against the patient's most
 * recent prior final Result for the same analyte with configurable
 * absolute/relative thresholds — is LIS-54's deliverable. Until it lands, the
 * default {@link NotInstalledDeltaCheckService} answers
 * {@link DeltaCheckVerdict.Outcome#NOT_EVALUABLE}, leaving the delta leg inert.
 * LIS-54's implementation should be annotated {@code @Primary} (or replace the
 * default bean) so the gate picks it up without changes.
 */
public interface DeltaCheckService {

    /**
     * Evaluate the delta check for one incoming result.
     *
     * @param analysis the persisted analysis the result belongs to (carries test,
     *                 sample and patient linkage)
     * @param result   the persisted incoming result
     * @return the verdict; never null
     */
    DeltaCheckVerdict evaluate(Analysis analysis, Result result);
}
