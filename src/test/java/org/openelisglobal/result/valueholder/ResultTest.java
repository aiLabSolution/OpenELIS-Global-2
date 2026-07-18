package org.openelisglobal.result.valueholder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.math.BigDecimal;
import org.junit.Test;

public class ResultTest {

    // LIS-252: a numeric Result carrying an off-scale qualified value must yield
    // its bare magnitude from getValue(true), so the FHIR-export new
    // BigDecimal(...) that used to crash on "<=0.01" (getActualNumericValue
    // returned "NaN") parses cleanly.
    @Test
    public void getValueTrue_shouldReturnMagnitudeForEveryComparatorForm() {
        for (String[] pair : new String[][] { { "<0.008", "0.008" }, { ">1000", "1000" }, { "<=0.01", "0.01" },
                { ">=500", "500" }, { "≤0.01", "0.01" }, { "≥500", "500" } }) {
            Result result = new Result();
            result.setResultType("N");
            result.setValue(pair[0]);

            String magnitude = result.getValue(true);
            assertEquals(pair[0] + " -> magnitude", pair[1], magnitude);
            // the crux: this is what FhirTransformServiceImpl does — it must not throw
            assertEquals(new BigDecimal(pair[1]), new BigDecimal(magnitude));
        }
    }

    @Test
    public void getValueTrue_shouldReturnPlainNumericUnchanged() {
        Result result = new Result();
        result.setResultType("N");
        result.setValue("2.31");
        assertEquals("2.31", result.getValue(true));
    }

    @Test
    public void getValue_shouldAlwaysKeepTheRawQualifiedStringVisible() {
        Result result = new Result();
        result.setResultType("N");
        result.setValue("<=0.01");
        // the human-readable qualified value is preserved on the plain accessor
        assertEquals("<=0.01", result.getValue());
    }

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
