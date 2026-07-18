package org.openelisglobal.result.valueholder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ResultTest {

    @Test
    public void setValue_whenValueChanges_clearsAnalyzerEvidence() {
        Result result = resultWithAnalyzerEvidence();

        result.setValue("5.2");

        assertNull(result.getReferenceRange());
        assertNull(result.getAbnormalFlag());
    }

    @Test
    public void setValue_whenValueIsUnchanged_preservesAnalyzerEvidence() {
        Result result = resultWithAnalyzerEvidence();

        result.setValue("4.1");

        assertEquals("3.5 to 5.1", result.getReferenceRange());
        assertEquals("N", result.getAbnormalFlag());
    }

    private Result resultWithAnalyzerEvidence() {
        Result result = new Result();
        result.setValue("4.1");
        result.setReferenceRange("3.5 to 5.1");
        result.setAbnormalFlag("N");
        return result;
    }
}
