package org.openelisglobal.analyzerresults.service;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzerresults.dao.AnalyzerResultsDAO;
import org.openelisglobal.analyzerresults.valueholder.AnalyzerResults;
import org.openelisglobal.analyzerresults.valueholder.SampleGrouping;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.service.AuditableBaseObjectServiceImpl;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.RecordStatus;
import org.openelisglobal.note.service.NoteService;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.result.action.util.ResultUtil;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.sampleitem.service.SampleItemService;
import org.openelisglobal.spring.util.SpringContext;
import org.openelisglobal.testanalyte.valueholder.TestAnalyte;
import org.openelisglobal.testreflex.action.util.TestReflexBean;
import org.openelisglobal.testreflex.action.util.TestReflexUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyzerResultsServiceImpl extends AuditableBaseObjectServiceImpl<AnalyzerResults, String>
        implements AnalyzerResultsService {
    @Autowired
    protected AnalyzerResultsDAO baseObjectDAO;

    @Autowired
    private NoteService noteService;
    @Autowired
    private SampleHumanService sampleHumanService;
    @Autowired
    private SampleItemService sampleItemService;
    @Autowired
    private SampleService sampleService;
    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private ResultService resultService;

    AnalyzerResultsServiceImpl() {
        super(AnalyzerResults.class);
        this.auditTrailLog = true;
    }

    @Override
    protected AnalyzerResultsDAO getBaseObjectDAO() {
        return baseObjectDAO;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalyzerResults> getResultsbyAnalyzer(String analyzerId) {
        return baseObjectDAO.getAllMatchingOrdered("analyzerId", analyzerId, "id", false);
    }

    @Override
    public AnalyzerResults readAnalyzerResults(String idString) {
        return getBaseObjectDAO().readAnalyzerResults(idString);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalyzerResults> findWithImportIssues(int limit) {
        return getBaseObjectDAO().findWithImportIssues(limit);
    }

    /**
     * Upsert / dedupe staging persistence for analyzer results.
     *
     * <p>
     * This is the single entry point for every analyzer import path in the system —
     * legacy plugins (GenericASTM, GenericHL7, GenericFile, per-device plugins
     * under {@code plugins/analyzers/**}) route through
     * {@code AnalyzerLineInserter.persistImport()} and the new FHIR bridge path
     * ({@code AnalyzerFhirImportController.importFhirBundle}) calls it directly.
     * </p>
     *
     * <p>
     * <b>Dedupe key</b>: {@code (analyzerId, accessionNumber, testName)}. See
     * {@code AnalyzerResultsDAOImpl.getDuplicateResultByAccessionAndTest}.
     * </p>
     *
     * <p>
     * <b>Three-case contract</b> (validated by
     * {@code AnalyzerResultsServiceImplTest}):
     * </p>
     * <ol>
     * <li><b>No previous row</b> — fresh insert, nothing flagged.</li>
     * <li><b>True re-import</b> — previous row with the same key AND
     * {@code completeDate} equal AND {@code result} value equal: skip silently.
     * This makes re-dropping an identical file completely idempotent (the "same
     * file output twice" scenario).</li>
     * <li><b>Anything else on an existing key</b> — insert the incoming row as a
     * linked correction ({@code readOnly=true}, {@code duplicateAnalyzerResultId}
     * backlink on both rows). The staging UI shows both rows linked and
     * highlighted, so the discrepancy is visible to the tech before accepting.
     * NOTE: the accept flow requires an explicit per-correction USE/DISMISS choice
     * and fails closed until one is made (LIS-158,
     * AnalyzerResultsAcceptServiceImpl.resolveLinkedCorrections); the readOnly row
     * is still excluded from sample groupings, but USE substitutes its value onto
     * the editable original before persistence rather than dropping it. Skipping on
     * date-or-value alone silently lost results (a re-run with an unchanged value,
     * or a corrected value re-sent with the same completion timestamp) — and the
     * key carries no patient dimension, so the lost row can belong to a different
     * sample or patient (LIS-121).</li>
     * </ol>
     *
     * <p>
     * This contract satisfies the plan <em>mellow-honking-cascade</em> Phase 1.6
     * upsert invariants — the bridge (DIGI-UW/openelis-analyzer-bridge#34) relies
     * on it to make the corrected-result workflow observable in the staging UI. Do
     * not rip out the duplicate-detection block without replacing it with an
     * equivalent semantic — the bridge's content-hash keyed state store produces
     * new FHIR POSTs whenever file content changes, and this method is what stops
     * those from creating duplicate staging rows.
     * </p>
     */
    @Override
    public void insertAnalyzerResults(List<AnalyzerResults> results, String sysUserId) {
        try {
            for (AnalyzerResults result : results) {
                boolean duplicateByAccessionAndTestOnly = false;
                List<AnalyzerResults> previousResults = baseObjectDAO.getDuplicateResultByAccessionAndTest(result);
                AnalyzerResults previousResult = null;

                // Duplicate detection: skip insert ONLY for a true re-import — an
                // existing staging entry with the SAME completeDate AND the SAME
                // value (an idempotent re-POST of the same message). Anything else
                // on an existing (analyzer, accession, test) key stages as a
                // read-only linked correction so the tech sees it. Matching on
                // date-or-value alone silently dropped real results: a re-run whose
                // value equals the previous run's, or a corrected value re-sent
                // with the same completion timestamp — and the key carries no
                // patient dimension, so the dropped result can belong to a
                // different sample or patient entirely (LIS-121).
                if (previousResults != null) {
                    duplicateByAccessionAndTestOnly = true;
                    for (AnalyzerResults foundResult : previousResults) {
                        previousResult = foundResult;
                        boolean sameCompleteDate = foundResult.getCompleteDate() == null
                                ? result.getCompleteDate() == null
                                : foundResult.getCompleteDate().equals(result.getCompleteDate());
                        boolean sameValue = foundResult.getResult() == null ? result.getResult() == null
                                : foundResult.getResult().equals(result.getResult());
                        if (sameCompleteDate && sameValue) {
                            duplicateByAccessionAndTestOnly = false;
                            break;
                        }
                    }
                }

                if (duplicateByAccessionAndTestOnly && previousResult != null) {
                    result.setDuplicateAnalyzerResultId(previousResult.getId());
                    result.setReadOnly(true);
                }

                if (previousResults == null || duplicateByAccessionAndTestOnly) {
                    result.setSysUserId(sysUserId);
                    String id = insert(result);
                    result.setId(id);

                    if (duplicateByAccessionAndTestOnly && previousResult != null) {
                        previousResult.setDuplicateAnalyzerResultId(id);
                        previousResult.setSysUserId(sysUserId);
                    }

                    if (duplicateByAccessionAndTestOnly) {
                        update(previousResult);
                    }
                } else {
                    // True re-import (same accession+test key, same completeDate AND
                    // same value) — an idempotent re-POST of a message already staged.
                    // Skipping is by design, but trace it so the skip is not
                    // structurally invisible (LIS-127): a dropped row that never
                    // reaches the accept worklist should still leave a diagnostic
                    // breadcrumb.
                    LogEvent.logDebug(this.getClass().getSimpleName(), "insertAnalyzerResults",
                            "skipped idempotent re-import: analyzerId=" + result.getAnalyzerId() + " accession="
                                    + result.getAccessionNumber() + " test=" + result.getTestName());
                }
            }

        } catch (RuntimeException e) {
            LogEvent.logError(e);
            throw new LIMSRuntimeException("Error in AnalyzerResult insertAnalyzerResult()", e);
        }
    }

    @Override
    @Transactional
    public void persistAnalyzerResults(List<AnalyzerResults> deletableAnalyzerResults,
            List<SampleGrouping> sampleGroupList, String sysUserId) {
        removeHandledResultsFromAnalyzerResults(deletableAnalyzerResults, sysUserId);

        insertResults(sampleGroupList, sysUserId);
    }

    private void removeHandledResultsFromAnalyzerResults(List<AnalyzerResults> deletableAnalyzerResults,
            String sysUserId) {
        for (AnalyzerResults currentAnalyzerResult : deletableAnalyzerResults) {
            delete(currentAnalyzerResult.getId(), sysUserId);
        }
    }

    private boolean insertResults(List<SampleGrouping> sampleGroupList, String sysUserId) {
        for (SampleGrouping grouping : sampleGroupList) {
            if (grouping.addSample) {
                // try {
                sampleService.insertDataWithAccessionNumber(grouping.sample);
                // } catch (LIMSRuntimeException e) {
                // Errors errors = new BaseErrors();
                // String errorMsg = "warning.duplicate.accession";
                // errors.reject(errorMsg, new String[] { grouping.sample.getAccessionNumber()
                // },
                // errorMsg);
                // saveErrors(errors);
                // return false;
                // }
            } else if (grouping.updateSample) {
                sampleService.update(grouping.sample);
            }

            String sampleId = grouping.sample.getId();

            if (grouping.addSample) {
                grouping.sampleHuman.setSampleId(sampleId);
                sampleHumanService.insert(grouping.sampleHuman);

                RecordStatus patientStatus = grouping.statusSet.getPatientRecordStatus() == null
                        ? RecordStatus.NotRegistered
                        : null;
                RecordStatus sampleStatus = grouping.statusSet.getSampleRecordStatus() == null
                        ? RecordStatus.NotRegistered
                        : null;
                SpringContext.getBean(IStatusService.class).persistRecordStatusForSample(grouping.sample, sampleStatus,
                        grouping.patient, patientStatus, sysUserId);
            }

            if (grouping.addSampleItem) {
                grouping.sampleItem.setSample(grouping.sample);
                sampleItemService.insert(grouping.sampleItem);
            }

            for (int i = 0; i < grouping.analysisList.size(); i++) {

                Analysis analysis = grouping.analysisList.get(i);
                if (GenericValidator.isBlankOrNull(analysis.getId())) {
                    analysis.setSampleItem(grouping.sampleItem);
                    analysisService.insert(analysis);
                } else {
                    analysisService.update(analysis);
                }

                Result result = grouping.resultList.get(i);
                if (GenericValidator.isBlankOrNull(result.getId())) {
                    result.setAnalysis(analysis);
                    setAnalyte(result);
                    resultService.insert(result);
                } else {
                    resultService.update(result);
                }

                Note note = grouping.noteList.get(i);

                if (note != null) {
                    note.setReferenceId(result.getId());
                    noteService.insert(note);
                }
            }
        }

        TestReflexUtil testReflexUtil = new TestReflexUtil();
        testReflexUtil.addNewTestsToDBForReflexTests(convertGroupListToTestReflexBeans(sampleGroupList), sysUserId);

        return true;
    }

    private List<TestReflexBean> convertGroupListToTestReflexBeans(List<SampleGrouping> sampleGroupList) {
        List<TestReflexBean> reflexBeanList = new ArrayList<>();

        for (SampleGrouping sampleGroup : sampleGroupList) {
            if (sampleGroup.accepted) {
                for (Result result : sampleGroup.resultList) {
                    TestReflexBean reflex = new TestReflexBean();
                    reflex.setPatient(sampleGroup.patient);
                    reflex.setTriggersToSelectedReflexesMap(sampleGroup.triggersToSelectedReflexesMap);
                    reflex.setResult(result);
                    reflex.setSample(sampleGroup.sample);
                    reflexBeanList.add(reflex);
                }
            }
        }

        return reflexBeanList;
    }

    private void setAnalyte(Result result) {
        TestAnalyte testAnalyte = ResultUtil.getTestAnalyteForResult(result);

        if (testAnalyte != null) {
            result.setAnalyte(testAnalyte.getAnalyte());
        }
    }
}
