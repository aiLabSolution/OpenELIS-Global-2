package org.openelisglobal.autoverification.dao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * HQL implementation of {@link DeltaCheckPriorResultDAO}, reusing the
 * SampleHuman join idiom of {@code ResultDAOImpl.getResultsByPatientUuid}.
 */
@Component
@Transactional
public class DeltaCheckPriorResultDAOImpl implements DeltaCheckPriorResultDAO {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String findPatientIdForAnalysis(String analysisId) throws LIMSRuntimeException {
        try {
            String sql = "SELECT sh.patientId FROM SampleHuman sh, Analysis a" + " WHERE a.id = :analysisId"
                    + " AND sh.sampleId = a.sampleItem.sample.id";
            List<String> patientIds = entityManager.createQuery(sql, String.class)
                    .setParameter("analysisId", analysisId).getResultList();
            return patientIds.isEmpty() ? null : patientIds.get(0);
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error resolving patient for analysis " + analysisId, e);
        }
    }

    @Override
    public Result findMostRecentPriorFinalResult(String patientId, String testId, String excludeAnalysisId,
            String finalizedStatusId) throws LIMSRuntimeException {
        try {
            String sql = "FROM Result r WHERE r.analysis.test.id = :testId"
                    + " AND r.analysis.statusId = :finalizedStatusId" + " AND r.analysis.id <> :excludeAnalysisId"
                    + " AND r.analysis.releasedDate IS NOT NULL" + " AND r.analysis.sampleItem.sample.id IN"
                    + " (SELECT sh.sampleId FROM SampleHuman sh WHERE sh.patientId = :patientId)"
                    + " ORDER BY r.analysis.releasedDate DESC, r.analysis.id DESC, r.id DESC";
            List<Result> results = entityManager.createQuery(sql, Result.class).setParameter("testId", testId)
                    .setParameter("finalizedStatusId", finalizedStatusId)
                    .setParameter("excludeAnalysisId", excludeAnalysisId).setParameter("patientId", patientId)
                    .setMaxResults(1).getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException(
                    "Error retrieving prior final result for patient " + patientId + ", test " + testId, e);
        }
    }
}
