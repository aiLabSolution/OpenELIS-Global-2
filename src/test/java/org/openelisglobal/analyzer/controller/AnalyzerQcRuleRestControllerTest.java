package org.openelisglobal.analyzer.controller;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzer.service.AnalyzerQcRuleService;
import org.openelisglobal.analyzer.valueholder.AnalyzerQcRule;
import org.openelisglobal.analyzer.valueholder.AnalyzerQcRule.RuleType;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * LIS-297: the isActive flag on the QC-rule REST endpoints must be an explicit
 * boolean act. A partial body without the key must not touch the stored flag
 * (the entity default used to silently ACTIVATE inactive rules), and
 * string-typed values must be rejected instead of truthiness-coerced (which
 * used to silently DEACTIVATE).
 */
public class AnalyzerQcRuleRestControllerTest extends BaseWebContextSensitiveTest {

    @Mock
    private AnalyzerQcRuleService analyzerQcRuleService;

    private AnalyzerQcRuleRestController controller;
    private Object originalService;
    private Object originalBridgeService;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext).apply(springSecurity()).build();
        MockitoAnnotations.initMocks(this);
        controller = webApplicationContext.getBean(AnalyzerQcRuleRestController.class);
        originalService = ReflectionTestUtils.getField(controller, "analyzerQcRuleService");
        originalBridgeService = ReflectionTestUtils.getField(controller, "bridgeRegistrationService");
        ReflectionTestUtils.setField(controller, "analyzerQcRuleService", analyzerQcRuleService);
        ReflectionTestUtils.setField(controller, "bridgeRegistrationService", null);
    }

    @After
    public void restoreController() {
        ReflectionTestUtils.setField(controller, "analyzerQcRuleService", originalService);
        ReflectionTestUtils.setField(controller, "bridgeRegistrationService", originalBridgeService);
    }

    private AnalyzerQcRule rule(boolean active) {
        AnalyzerQcRule rule = new AnalyzerQcRule();
        rule.setId("rule-1");
        rule.setAnalyzerId("101");
        rule.setRuleType(RuleType.CALIBRATION_SPECIMEN_ID_PREFIX);
        rule.setOperand("CAL-");
        rule.setActive(active);
        rule.setDisplayOrder(1);
        return rule;
    }

    @Test
    public void testUpdateQcRule_WithoutIsActive_PassesNullIsActiveToService() throws Exception {
        when(analyzerQcRuleService.updateRule(eq("101"), eq("rule-1"), any(AnalyzerQcRule.class),
                isNull(), nullable(String.class))).thenReturn(rule(false));

        mockMvc.perform(put("/rest/analyzer/analyzers/101/qc-rules/rule-1").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"operand\":\"CAL-\",\"description\":\"amended\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false));

        verify(analyzerQcRuleService).updateRule(eq("101"), eq("rule-1"), any(AnalyzerQcRule.class),
                isNull(), nullable(String.class));
    }

    @Test
    public void testUpdateQcRule_StringTypedIsActive_Returns400WithoutUpdating() throws Exception {
        mockMvc.perform(put("/rest/analyzer/analyzers/101/qc-rules/rule-1").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":\"true\"}"))
                .andExpect(status().isBadRequest());

        verify(analyzerQcRuleService, never()).updateRule(any(), any(), any(), any(), any());
    }

    @Test
    public void testUpdateQcRule_NumericIsActive_Returns400WithoutUpdating() throws Exception {
        mockMvc.perform(put("/rest/analyzer/analyzers/101/qc-rules/rule-1").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":1}"))
                .andExpect(status().isBadRequest());

        verify(analyzerQcRuleService, never()).updateRule(any(), any(), any(), any(), any());
    }

    @Test
    public void testUpdateQcRule_BooleanFalse_PassedThroughExplicitly() throws Exception {
        when(analyzerQcRuleService.updateRule(eq("101"), eq("rule-1"), any(AnalyzerQcRule.class), eq(Boolean.FALSE),
                nullable(String.class))).thenReturn(rule(false));

        mockMvc.perform(put("/rest/analyzer/analyzers/101/qc-rules/rule-1").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}")).andExpect(status().isOk());

        verify(analyzerQcRuleService).updateRule(eq("101"), eq("rule-1"), any(AnalyzerQcRule.class), eq(Boolean.FALSE),
                nullable(String.class));
    }

    @Test
    public void testCreateQcRule_StringTypedIsActive_Returns400WithoutCreating() throws Exception {
        mockMvc.perform(post("/rest/analyzer/analyzers/101/qc-rules").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"SPECIMEN_ID_PREFIX\",\"operand\":\"QC-\",\"isActive\":\"false\"}"))
                .andExpect(status().isBadRequest());

        verify(analyzerQcRuleService, never()).createRule(any(), any(), any());
    }

    @Test
    public void testCreateQcRule_ExplicitFalse_CreatesInactiveRule() throws Exception {
        ArgumentCaptor<AnalyzerQcRule> captor = ArgumentCaptor.forClass(AnalyzerQcRule.class);
        when(analyzerQcRuleService.createRule(eq("101"), captor.capture(), nullable(String.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        mockMvc.perform(post("/rest/analyzer/analyzers/101/qc-rules").with(user("admin").roles("GLOBAL_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"CALIBRATION_SPECIMEN_ID_PREFIX\",\"operand\":\"CAL-\",\"isActive\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isActive").value(false));

        assertFalse("explicit isActive:false must reach the service as inactive", captor.getValue().isActive());
    }
}
