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
package org.openelisglobal.analyzerresults.service;

import org.openelisglobal.common.exception.LIMSRuntimeException;

/**
 * Thrown when an accepted sample grouping contains a linked analyzer-result
 * correction (a readOnly row with a {@code duplicateAnalyzerResultId} backlink)
 * that has not been explicitly resolved by the technician (USE or DISMISS).
 * Accept is blocked fail-closed rather than silently discarding the correction
 * or the original — see LIS-158.
 */
public class UnresolvedCorrectionException extends LIMSRuntimeException {
    private static final long serialVersionUID = 1L;

    public UnresolvedCorrectionException(String message) {
        super(message);
    }
}
