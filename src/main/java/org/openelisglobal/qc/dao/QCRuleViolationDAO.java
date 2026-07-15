package org.openelisglobal.qc.dao;

import java.sql.Timestamp;
import java.util.List;
import org.openelisglobal.common.dao.BaseDAO;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.qc.valueholder.QCRuleViolation;

/**
 * DAO interface for QCRuleViolation entity operations.
 */
public interface QCRuleViolationDAO extends BaseDAO<QCRuleViolation, String> {

    /**
     * Get all violations for a specific instrument.
     */
    List<QCRuleViolation> findByInstrument(String instrumentId) throws LIMSRuntimeException;

    /**
     * Get unresolved violations.
     */
    List<QCRuleViolation> findUnresolved() throws LIMSRuntimeException;

    /**
     * Get violations by severity.
     */
    List<QCRuleViolation> findBySeverity(String severity) throws LIMSRuntimeException;

    /**
     * Get violations by instrument and date range.
     */
    List<QCRuleViolation> findByInstrumentAndDateRange(String instrumentId, Timestamp startDate, Timestamp endDate)
            throws LIMSRuntimeException;

    /**
     * Get unresolved violations for a specific instrument.
     */
    List<QCRuleViolation> findUnresolvedByInstrument(String instrumentId) throws LIMSRuntimeException;

    /**
     * Get violations for a specific triggering QC result.
     */
    List<QCRuleViolation> findByTriggeringResultId(String triggeringResultId) throws LIMSRuntimeException;

    /**
     * Get the REJECTION-severity violations for an (instrument, test) pair that
     * are not yet RESOLVED — i.e. the violations that block autorelease of that
     * instrument's patient results for that test (LIS-55). ACKNOWLEDGED
     * violations are included: acknowledging an alert is not clearing the
     * instrument.
     */
    List<QCRuleViolation> findActiveRejections(String instrumentId, String testId) throws LIMSRuntimeException;
}
