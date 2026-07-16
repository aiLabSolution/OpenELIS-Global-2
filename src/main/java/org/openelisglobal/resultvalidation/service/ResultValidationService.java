package org.openelisglobal.resultvalidation.service;

import java.util.ArrayList;
import java.util.List;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.services.IResultSaveService;
import org.openelisglobal.common.services.registration.interfaces.IResultUpdate;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.resultvalidation.bean.AnalysisItem;
import org.openelisglobal.sample.valueholder.Sample;

public interface ResultValidationService {

    void persistdata(List<Result> deletableList, List<Analysis> analysisUpdateList, ArrayList<Result> resultUpdateList,
            List<AnalysisItem> resultItemList, ArrayList<Sample> sampleUpdateList, ArrayList<Note> noteUpdateList,
            IResultSaveService resultSaveService, List<IResultUpdate> updaters, String sysUserId);

    /**
     * The release transition of the human result-validation path: stamps the named
     * releasing user as the audit attribution, moves the analysis to Finalized and
     * records the release moment. Fail-closed: refuses (throws) unless the analysis
     * is currently in a held validation-queue status (Technical Acceptance, or
     * Technical Rejection when VALIDATE_REJECTED_TESTS is on) — the caller must
     * hand in the database-loaded analysis, never a client-built one. Mutates in
     * place; persisting the analysis afterwards through
     * {@code AnalysisService#update} (persistdata's analysis leg) writes the
     * before/after transition to the append-only history table — that row is the
     * release audit record (LIS-56).
     */
    void markAnalysisReleased(Analysis analysis, String sysUserId);
}
