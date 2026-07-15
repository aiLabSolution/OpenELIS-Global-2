package org.openelisglobal.analyzerresults.service;

import java.util.List;
import org.openelisglobal.analyzerresults.action.beanitems.AnalyzerResultItem;

/**
 * Orchestrates the "accept analyzer results" workflow: extracts actionable
 * items from the UI list, builds the domain objects (Sample, Analysis, Result,
 * etc.), and delegates persistence to {@link AnalyzerResultsService}.
 *
 * <p>
 * Extracted from AnalyzerResultsController so that business logic lives in the
 * service layer where it belongs.
 */
public interface AnalyzerResultsAcceptService {

    /**
     * Accept the user-selected analyzer results and persist them into the OE
     * results system.
     *
     * @param allResults every {@link AnalyzerResultItem} on the current page
     *                   (accepted, rejected, deleted, and untouched)
     * @param sysUserId  the authenticated user's system id
     */
    void acceptAndPersist(List<AnalyzerResultItem> allResults, String sysUserId);

    /**
     * Whether accepting results for this accession number would commit them under
     * the shared unidentified-patient placeholder without any human-verified
     * patient identity (LIS-126): either no sample entry has been done and no
     * sample carries the accession (accept would mint a new Unknown-patient
     * sample), or the only sample carrying it was itself created by a previous
     * analyzer accept (both record statuses NotRegistered — accept would attach to
     * it). Such groups require an explicit technician confirmation before accept.
     *
     * @param accessionNumber the group's accession number
     * @return true when accept must be gated behind an explicit confirmation
     */
    boolean requiresUnmatchedConfirmation(String accessionNumber);
}
