package org.openelisglobal.autoverification.dao;

import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.result.valueholder.Result;

/**
 * Read-only lookups the delta-check engine (LIS-54) needs: resolving the
 * patient behind an analysis and fetching that patient's most recent prior
 * final result for the same test, through the canonical
 * Patient-&gt;Sample-&gt;SampleItem-&gt;Analysis-&gt;Result chain.
 */
public interface DeltaCheckPriorResultDAO {

    /**
     * The patient id an analysis belongs to (via its sample's SampleHuman row), or
     * null when the analysis is not linked to a patient.
     */
    String findPatientIdForAnalysis(String analysisId) throws LIMSRuntimeException;

    /**
     * The patient's most recent prior final result for a test: the Result whose
     * analysis is Finalized with the latest non-null released date (analysis id,
     * then result id, break exact ties), excluding the incoming analysis itself. A
     * Finalized analysis without a released date cannot be ordered "most recent"
     * and is deliberately not considered. Null when the patient has no such prior.
     */
    Result findMostRecentPriorFinalResult(String patientId, String testId, String excludeAnalysisId,
            String finalizedStatusId) throws LIMSRuntimeException;
}
