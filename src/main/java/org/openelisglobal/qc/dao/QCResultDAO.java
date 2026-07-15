package org.openelisglobal.qc.dao;

import java.sql.Timestamp;
import java.util.List;
import org.openelisglobal.common.dao.BaseDAO;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.qc.valueholder.QCResult;

/**
 * DAO interface for QCResult entity operations.
 */
public interface QCResultDAO extends BaseDAO<QCResult, String> {

    /**
     * Get all results for a specific control lot.
     */
    List<QCResult> findByControlLot(String controlLotId) throws LIMSRuntimeException;

    /**
     * Get historical results for rule evaluation (ordered by run date).
     */
    List<QCResult> findHistoricalForRule(String controlLotId, int limit) throws LIMSRuntimeException;

    /**
     * Get results by instrument and date range.
     */
    List<QCResult> findByInstrumentAndDateRange(String instrumentId, Timestamp startDate, Timestamp endDate)
            throws LIMSRuntimeException;

    /**
     * Get latest N results for a control lot.
     */
    List<QCResult> findLatestByControlLot(String controlLotId, int limit) throws LIMSRuntimeException;

    /**
     * Get all results for a control lot ordered by run date ascending (oldest
     * first). Used for Westgard rule evaluation.
     */
    List<QCResult> findByControlLotIdOrderByRunDateTime(String controlLotId) throws LIMSRuntimeException;

    /**
     * Get results by control lot and date range for chart display.
     */
    List<QCResult> findByControlLotAndDateRange(String controlLotId, Timestamp startDate, Timestamp endDate)
            throws LIMSRuntimeException;

    /**
     * Get latest N results for a specific instrument and test, ordered by run date
     * descending.
     */
    List<QCResult> findLatestByInstrumentAndTest(String instrumentId, String testId, int limit)
            throws LIMSRuntimeException;

    /**
     * Get all distinct instrument IDs that have QC results.
     */
    List<String> findDistinctInstrumentIds() throws LIMSRuntimeException;

    /**
     * Get QC results for an (instrument, test) pair whose resultStatus is still
     * PENDING — i.e. the Westgard evaluation listener has not classified them yet.
     * The autoverification gate (LIS-55) fails closed on these: a run whose
     * evaluation has not finished is not yet known to be in control.
     */
    List<QCResult> findPendingByInstrumentAndTest(String instrumentId, String testId) throws LIMSRuntimeException;
}
