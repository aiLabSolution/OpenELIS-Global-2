package org.openelisglobal.autoverification.dao;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.openelisglobal.autoverification.valueholder.DeltaCheckConfig;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DAO implementation for {@link DeltaCheckConfig}.
 */
@Component
@Transactional
public class DeltaCheckConfigDAOImpl extends BaseDAOImpl<DeltaCheckConfig, String> implements DeltaCheckConfigDAO {

    public DeltaCheckConfigDAOImpl() {
        super(DeltaCheckConfig.class);
    }

    @Override
    public DeltaCheckConfig findActiveByTestId(String testId) throws LIMSRuntimeException {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<DeltaCheckConfig> cq = cb.createQuery(DeltaCheckConfig.class);
            Root<DeltaCheckConfig> root = cq.from(DeltaCheckConfig.class);
            cq.where(cb.equal(root.get("testId"), testId), cb.equal(root.get("active"), true));
            List<DeltaCheckConfig> results = entityManager.createQuery(cq).getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error retrieving delta-check config by test", e);
        }
    }
}
