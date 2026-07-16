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

    /**
     * The last name PatientUtil.initializeUnknowns() gives the placeholder person
     * behind the shared unknown patient (there is no named constant upstream).
     */
    private static final String UNKNOWN_PERSON_LAST_NAME = "UNKNOWN_";

    @Override
    public boolean isUnknownPlaceholderPatient(String patientId) throws LIMSRuntimeException {
        try {
            String sql = "SELECT count(p.id) FROM Patient p WHERE p.id = :patientId"
                    + " AND p.person.lastName = :unknownLastName";
            Long count = entityManager.createQuery(sql, Long.class).setParameter("patientId", patientId)
                    .setParameter("unknownLastName", UNKNOWN_PERSON_LAST_NAME).getSingleResult();
            return count != null && count > 0;
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error checking unknown-placeholder patient " + patientId, e);
        }
    }

    @Override
    public Result findMostRecentPriorFinalResult(String patientId, String testId, String excludeAnalysisId,
            String finalizedStatusId) throws LIMSRuntimeException {
        try {
            // resultType 'N' + non-null value: a multi-result analysis may carry
            // qualifier/child rows (type A/D/M) with higher result ids — the
            // delta prior must be the numeric row, not the newest row
            // (adversarial-review P2 on LIS-54)
            String sql = "FROM Result r WHERE r.analysis.test.id = :testId"
                    + " AND r.analysis.statusId = :finalizedStatusId" + " AND r.analysis.id <> :excludeAnalysisId"
                    + " AND r.analysis.releasedDate IS NOT NULL" + " AND r.resultType = 'N' AND r.value IS NOT NULL"
                    + " AND r.analysis.sampleItem.sample.id IN"
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
