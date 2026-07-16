package org.openelisglobal.autoverification.service;

import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.stereotype.Service;

/**
 * Default {@link DeltaCheckService} used until the real delta-check engine
 * (LIS-54) is installed. Always answers NOT_EVALUABLE, which the
 * autoverification gate treats as "the delta leg cannot run" — it neither
 * blocks nor vouches for the result; range and QC gating still apply.
 */
@Service
public class NotInstalledDeltaCheckService implements DeltaCheckService {

    @Override
    public DeltaCheckVerdict evaluate(Analysis analysis, Result result) {
        return DeltaCheckVerdict.notEvaluable("delta-check engine not installed (LIS-54)");
    }
}
