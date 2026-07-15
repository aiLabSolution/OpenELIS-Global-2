package org.openelisglobal.autoverification.dao;

import org.openelisglobal.autoverification.valueholder.DeltaCheckConfig;
import org.openelisglobal.common.dao.BaseDAO;
import org.openelisglobal.common.exception.LIMSRuntimeException;

/**
 * DAO for the per-test delta-check thresholds (LIS-54).
 */
public interface DeltaCheckConfigDAO extends BaseDAO<DeltaCheckConfig, String> {

    /**
     * The active threshold configuration for a test, or null when none is
     * configured (inactive rows do not count).
     */
    DeltaCheckConfig findActiveByTestId(String testId) throws LIMSRuntimeException;
}
