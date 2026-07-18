package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.analyzerimport.service.AnalyzerTestMappingService;
import org.openelisglobal.analyzerimport.valueholder.AnalyzerTestMapping;
import org.openelisglobal.test.service.TestService;

/**
 * OE2 pushes the analyzer's test_code → LOINC map to the bridge at
 * registration, built from the lab's AnalyzerTestMapping (code → testId) joined
 * to Test.loinc. This is what lets the bridge own analyzer↔LOINC translation so
 * OE2 stays analyzer-agnostic.
 */
public class BridgeRegistrationServiceTest {

    private BridgeRegistrationService svc;
    private AnalyzerTestMappingService mappingService;
    private TestService testService;

    @Before
    public void setUp() throws Exception {
        svc = new BridgeRegistrationService();
        mappingService = mock(AnalyzerTestMappingService.class);
        testService = mock(TestService.class);
        inject("analyzerTestMappingService", mappingService);
        inject("testService", testService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = BridgeRegistrationService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(svc, value);
    }

    private AnalyzerTestMapping mapping(String code, String testId) {
        AnalyzerTestMapping m = mock(AnalyzerTestMapping.class);
        when(m.getAnalyzerTestName()).thenReturn(code);
        when(m.getTestId()).thenReturn(testId);
        return m;
    }

    private void stubTestLoinc(String testId, String loinc) {
        org.openelisglobal.test.valueholder.Test t = mock(org.openelisglobal.test.valueholder.Test.class);
        when(t.getLoinc()).thenReturn(loinc);
        when(testService.get(testId)).thenReturn(t);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attachAndGet(String analyzerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachTestCodeLoinc(payload, analyzerId);
        return (Map<String, String>) payload.get("testCodeLoinc");
    }

    @Test
    public void buildsCodeToLoincFromMappingsAndTestLoinc() {
        // Build the mapping mocks BEFORE stubbing getAllForAnalyzer — nesting
        // mock-stubbing inside a thenReturn(...) argument trips Mockito.
        AnalyzerTestMapping m1 = mapping("MTB-RIF", "42");
        AnalyzerTestMapping m2 = mapping("HIV-VL", "43");
        when(mappingService.getAllForAnalyzer("AN-1")).thenReturn(List.of(m1, m2));
        stubTestLoinc("42", "85362-2");
        stubTestLoinc("43", "20447-9");

        Map<String, String> m = attachAndGet("AN-1");

        assertEquals("85362-2", m.get("MTB-RIF"));
        assertEquals("20447-9", m.get("HIV-VL"));
        assertEquals(2, m.size());
    }

    @Test
    public void skipsTestsWithoutLoinc() {
        AnalyzerTestMapping mw = mapping("WBC", "42");
        AnalyzerTestMapping mv = mapping("VENDOR-ONLY", "99");
        when(mappingService.getAllForAnalyzer("AN-2")).thenReturn(List.of(mw, mv));
        stubTestLoinc("42", "6690-2");
        stubTestLoinc("99", null); // no LOINC on this test

        Map<String, String> m = attachAndGet("AN-2");

        assertEquals(1, m.size());
        assertEquals("6690-2", m.get("WBC"));
        assertTrue("vendor-only test with no LOINC is omitted", !m.containsKey("VENDOR-ONLY"));
    }

    @Test
    public void alwaysAttachesEvenWhenEmpty() {
        when(mappingService.getAllForAnalyzer("AN-3")).thenReturn(List.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachTestCodeLoinc(payload, "AN-3");
        // Key present (empty) so a sync push can clear stale bridge mappings.
        assertTrue(payload.containsKey("testCodeLoinc"));
        assertNotNull(payload.get("testCodeLoinc"));
    }

    @Test
    public void attachTestUnitUcumBuildsFromUnitMasterList() throws Exception {
        org.openelisglobal.unitofmeasure.service.UnitOfMeasureService uomService = mock(
                org.openelisglobal.unitofmeasure.service.UnitOfMeasureService.class);
        inject("unitOfMeasureService", uomService);
        Map<String, String> unitMap = new LinkedHashMap<>();
        unitMap.put("mmol/L", "mmol/L");
        unitMap.put("x10^9/L", "10*9/L");
        when(uomService.getActiveUnitUcumMap()).thenReturn(unitMap);

        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachTestUnitUcum(payload);

        assertEquals(unitMap, payload.get("testUnitUcum"));
    }

    @Test
    public void attachTestUnitUcumAlwaysAttachesWithoutService() {
        // unitOfMeasureService left null (older deployment) — the key must
        // still be present so the bridge /sync contract stays stable.
        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachTestUnitUcum(payload);
        assertTrue(payload.containsKey("testUnitUcum"));
        assertNotNull(payload.get("testUnitUcum"));
    }

    @Test
    public void attachTestUnitUcumDegradesOnDataAccessError() throws Exception {
        org.openelisglobal.unitofmeasure.service.UnitOfMeasureService uomService = mock(
                org.openelisglobal.unitofmeasure.service.UnitOfMeasureService.class);
        inject("unitOfMeasureService", uomService);
        when(uomService.getActiveUnitUcumMap()).thenThrow(new RuntimeException("db down"));

        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachTestUnitUcum(payload);

        // Registration must not fail because the unit map couldn't be built.
        assertTrue(payload.containsKey("testUnitUcum"));
        assertEquals(new LinkedHashMap<String, String>(), payload.get("testUnitUcum"));
    }

    // ---- attachQcRules (LIS-173 / LIS-269) ----

    @SuppressWarnings("unchecked")
    @Test
    public void attachQcRulesCarriesCalibrationRuleTypeToBridgePayload() throws Exception {
        AnalyzerQcRuleService qcRuleService = mock(AnalyzerQcRuleService.class);
        inject("analyzerQcRuleService", qcRuleService);
        when(qcRuleService.getActiveRuleDtosForAnalyzer("AN-9"))
                .thenReturn(List.of(new QcRuleDto("FIELD_EQUALS", "O.12", "Q"),
                        new QcRuleDto("CALIBRATION_SPECIMEN_ID_PREFIX", null, "CAL-")));

        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachQcRules(payload, "AN-9");

        List<Map<String, Object>> rules = (List<Map<String, Object>>) payload.get("qcRules");
        assertEquals(2, rules.size());
        assertEquals("FIELD_EQUALS", rules.get(0).get("ruleType"));
        assertEquals("O.12", rules.get(0).get("targetField"));
        assertEquals("Q", rules.get(0).get("operand"));
        // The CALIBRATION_ prefix must survive verbatim — the bridge strips it to
        // pick the base predicate and routes the match to its CALIBRATION class.
        assertEquals("CALIBRATION_SPECIMEN_ID_PREFIX", rules.get(1).get("ruleType"));
        assertTrue(!rules.get(1).containsKey("targetField"));
        assertEquals("CAL-", rules.get(1).get("operand"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void attachQcRulesAlwaysAttachesEvenWhenEmpty() throws Exception {
        AnalyzerQcRuleService qcRuleService = mock(AnalyzerQcRuleService.class);
        inject("analyzerQcRuleService", qcRuleService);
        when(qcRuleService.getActiveRuleDtosForAnalyzer("AN-10")).thenReturn(List.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        svc.attachQcRules(payload, "AN-10");

        // Empty list ≠ field absent: sync must be able to distinguish "no rules —
        // clear bridge state" from "leave bridge state alone".
        List<Map<String, Object>> rules = (List<Map<String, Object>>) payload.get("qcRules");
        assertNotNull(rules);
        assertEquals(0, rules.size());
    }
}
