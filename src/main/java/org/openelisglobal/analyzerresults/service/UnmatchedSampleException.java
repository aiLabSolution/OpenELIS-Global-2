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
 * Thrown when an accepted sample grouping's accession number resolves to no
 * human-entered sample or patient (the walk-up analyzer case: the bridge fills
 * the accession slot with the patient MRN or the literal {@code HL7-UNKNOWN})
 * and the technician has not explicitly confirmed committing the results under
 * the shared unidentified-patient placeholder
 * ({@code unmatchedAction=ACCEPT_UNKNOWN}). Accept is blocked fail-closed
 * rather than silently minting — or silently attaching to — an Unknown-patient
 * sample — see LIS-126.
 */
public class UnmatchedSampleException extends LIMSRuntimeException {
    private static final long serialVersionUID = 1L;

    public UnmatchedSampleException(String message) {
        super(message);
    }
}
