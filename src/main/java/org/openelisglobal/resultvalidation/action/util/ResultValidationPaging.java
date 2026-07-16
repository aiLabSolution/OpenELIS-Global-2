/**
 * The contents of this file are subject to the Mozilla Public License Version 1.1 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.mozilla.org/MPL/
 *
 * <p>Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF
 * ANY KIND, either express or implied. See the License for the specific language governing rights
 * and limitations under the License.
 *
 * <p>The Original Code is OpenELIS code.
 *
 * <p>Copyright (C) CIRG, University of Washington, Seattle WA. All Rights Reserved.
 */
package org.openelisglobal.resultvalidation.action.util;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.paging.IPageDivider;
import org.openelisglobal.common.paging.IPageFlattener;
import org.openelisglobal.common.paging.IPageUpdater;
import org.openelisglobal.common.paging.PagingBean;
import org.openelisglobal.common.paging.PagingProperties;
import org.openelisglobal.common.paging.PagingUtility;
import org.openelisglobal.common.util.IdValuePair;
import org.openelisglobal.resultvalidation.bean.AnalysisItem;
import org.openelisglobal.resultvalidation.form.ValidationPagingForm;
import org.openelisglobal.spring.util.SpringContext;

public class ResultValidationPaging {
    private PagingUtility<List<AnalysisItem>> paging = new PagingUtility<>();
    private static AnalysisItemPageHelper pagingHelper = new AnalysisItemPageHelper();

    public void setDatabaseResults(HttpServletRequest request, ValidationPagingForm form,
            List<AnalysisItem> analysisItems)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        paging.setDatabaseResults(request.getSession(), analysisItems, pagingHelper);

        List<AnalysisItem> resultPage = paging.getPage(1, request.getSession());

        if (resultPage != null) {
            form.setResultList(resultPage);
            form.setPaging(paging.getPagingBeanWithSearchMapping(1, request.getSession()));
        }
    }

    public void page(HttpServletRequest request, ValidationPagingForm form, int newPage)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        request.getSession().setAttribute(IActionConstants.SAVE_DISABLED, IActionConstants.FALSE);
        // List<AnalysisItem> clientAnalysis = form.getResultList();
        // PagingBean bean = form.getPaging();
        String testSectionId = form.getTestSectionId();

        // paging.updatePagedResults(request.getSession(), clientAnalysis, bean,
        // pagingHelper);

        if (newPage < 0) {
            newPage = 0;
        }

        List<AnalysisItem> resultPage = paging.getPage(newPage, request.getSession());
        if (resultPage != null) {
            form.setResultList(resultPage);
            form.setTestSectionId(testSectionId);
            form.setPaging(paging.getPagingBeanWithSearchMapping(newPage, request.getSession()));
        }
    }

    public void updatePagedResults(HttpServletRequest request, ValidationPagingForm form) {
        List<AnalysisItem> clientAnalysis = form.getResultList();
        PagingBean bean = form.getPaging();

        paging.updatePagedResults(request.getSession(), clientAnalysis, bean, pagingHelper);
    }

    public List<AnalysisItem> getResults(HttpServletRequest request) {
        return paging.getAllResults(request.getSession(), pagingHelper);
    }

    private static class AnalysisItemPageHelper implements IPageDivider<List<AnalysisItem>>,
            IPageUpdater<List<AnalysisItem>>, IPageFlattener<List<AnalysisItem>> {

        @Override
        public void createPages(List<AnalysisItem> analysisList, List<List<AnalysisItem>> pagedResults) {
            List<AnalysisItem> page = new ArrayList<>();

            String currentAccessionNumber = null;
            int resultCount = 0;

            for (AnalysisItem item : analysisList) {
                if (currentAccessionNumber != null && !currentAccessionNumber.equals(item.getAccessionNumber())) {
                    resultCount = 0;
                    currentAccessionNumber = null;
                    pagedResults.add(page);
                    page = new ArrayList<>();
                }
                if (resultCount >= SpringContext.getBean(PagingProperties.class).getValidationPageSize()) {
                    currentAccessionNumber = item.getAccessionNumber();
                }

                page.add(item);
                resultCount++;
            }

            if (!page.isEmpty() || pagedResults.isEmpty()) {
                pagedResults.add(page);
            }
        }

        /**
         * LIS-56: merge only the client's DECISION fields onto the server-cached items.
         * The cache previously took the client objects wholesale, which made every
         * field — including analysisId, readOnly and the identity fields the downstream
         * save trusts — client-controlled at save time: a tampered analysisId could
         * release an analysis outside the queue page the user was shown. The merge
         * stays positional (the client posts the page exactly as served — one item per
         * result row, so several rows can share an analysisId and an id-keyed merge
         * would collapse them), but identity now stays server-owned: a row whose client
         * analysisId does not match the cached row is ignored rather than trusted.
         */
        @Override
        public void updateCache(List<AnalysisItem> cacheItems, List<AnalysisItem> clientItems) {
            for (int i = 0; i < cacheItems.size() && i < clientItems.size(); i++) {
                AnalysisItem cacheItem = cacheItems.get(i);
                AnalysisItem clientItem = clientItems.get(i);
                if (cacheItem.getAnalysisId() == null
                        || !cacheItem.getAnalysisId().equals(clientItem.getAnalysisId())) {
                    LogEvent.logWarn("ResultValidationPaging", "updateCache",
                            "client row " + i + " does not match the cached page row (analysisId "
                                    + clientItem.getAnalysisId() + " vs cached " + cacheItem.getAnalysisId()
                                    + ") — client edits for that row ignored");
                    continue;
                }
                cacheItem.setIsAccepted(clientItem.getIsAccepted());
                cacheItem.setIsRejected(clientItem.getIsRejected());
                cacheItem.setNote(clientItem.getNote());
                cacheItem.setResult(clientItem.getResult());
                cacheItem.setQualifiedResultValue(clientItem.getQualifiedResultValue());
                cacheItem.setMultiSelectResultValues(clientItem.getMultiSelectResultValues());
                cacheItem.setSampleIsAccepted(clientItem.isSampleIsAccepted());
                cacheItem.setSampleIsRejected(clientItem.isSampleIsRejected());
            }
        }

        @Override
        public List<AnalysisItem> flattenPages(List<List<AnalysisItem>> pages) {

            List<AnalysisItem> allResults = new ArrayList<>();

            for (List<AnalysisItem> page : pages) {
                for (AnalysisItem item : page) {
                    allResults.add(item);
                }
            }

            return allResults;
        }

        @Override
        public List<IdValuePair> createSearchToPageMapping(List<List<AnalysisItem>> allPages) {
            List<IdValuePair> mappingList = new ArrayList<>();

            int page = 0;
            for (List<AnalysisItem> analysisList : allPages) {
                page++;
                String pageString = String.valueOf(page);

                String currentAccession = null;

                for (AnalysisItem analysisItem : analysisList) {
                    if (!analysisItem.getAccessionNumber().equals(currentAccession)) {
                        currentAccession = analysisItem.getAccessionNumber();
                        mappingList.add(new IdValuePair(currentAccession, pageString));
                    }
                }
            }

            return mappingList;
        }
    }
}
