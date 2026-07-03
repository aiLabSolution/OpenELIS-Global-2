package org.openelisglobal.analyzer.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.Test;
import org.openelisglobal.analyzer.service.AnalyzerOrderMenuService;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Component test for the analyzer-bridge order-menu endpoint: a known accession
 * returns its pending LOINC menu, an unknown accession is 404, and the endpoint
 * is auth-gated like the rest of the analyzer bridge surface (authenticated,
 * not anonymous).
 */
@WebAppConfiguration
@ContextConfiguration(classes = { AnalyzerOrderMenuRestControllerSecurityTest.TestConfig.class })
@TestPropertySource("classpath:common.properties")
public class AnalyzerOrderMenuRestControllerSecurityTest extends SecuritySliceMockMvcTest {

    @Test
    public void testOrderMenu_WithoutAuthentication_Returns401() throws Exception {
        mockMvc.perform(get("/rest/analyzer/order-menu/ACC-1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testOrderMenu_Authenticated_ReturnsPendingLoincMenu() throws Exception {
        mockMvc.perform(get("/rest/analyzer/order-menu/ACC-1").with(user("bridge").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessionNumber").value("ACC-1")).andExpect(jsonPath("$.patientId").value("S1"))
                .andExpect(jsonPath("$.loincCodes[0]").value("6690-2"))
                .andExpect(jsonPath("$.loincCodes[1]").value("718-7"))
                .andExpect(jsonPath("$.loincCodes.length()").value(2));
    }

    @Test
    public void testOrderMenu_UnknownAccession_Returns404() throws Exception {
        mockMvc.perform(get("/rest/analyzer/order-menu/NOPE-1").with(user("bridge").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // Deliberately NOT @Configuration: AppTestConfig component-scans the whole
    // org.openelisglobal.analyzer tree, and test classes are on that classpath —
    // a scannable configuration here would leak these mock beans (notably a
    // second SampleService) into the shared web context and break it. The class
    // is registered explicitly via @ContextConfiguration, which still processes
    // its @Bean methods (lite mode) and @Enable* annotations.
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        // The mocked AnalyzerOrderMenuService is still a Spring bean, so its
        // @Autowired fields get processed — satisfy them with mocks.
        @Bean
        org.openelisglobal.sample.service.SampleService sampleService() {
            return mock(org.openelisglobal.sample.service.SampleService.class);
        }

        @Bean
        org.openelisglobal.analysis.service.AnalysisService analysisService() {
            return mock(org.openelisglobal.analysis.service.AnalysisService.class);
        }

        @Bean
        org.openelisglobal.common.services.IStatusService statusService() {
            return mock(org.openelisglobal.common.services.IStatusService.class);
        }

        @Bean
        AnalyzerOrderMenuService analyzerOrderMenuService() {
            AnalyzerOrderMenuService service = mock(AnalyzerOrderMenuService.class);
            AnalyzerOrderMenuService.OrderMenu menu = new AnalyzerOrderMenuService.OrderMenu();
            menu.accessionNumber = "ACC-1";
            menu.patientId = "S1";
            menu.loincCodes = List.of("6690-2", "718-7");
            when(service.getOrderMenu("ACC-1")).thenReturn(menu);
            when(service.getOrderMenu("NOPE-1")).thenReturn(null);
            return service;
        }

        @Bean
        AnalyzerOrderMenuRestController analyzerOrderMenuRestController(
                AnalyzerOrderMenuService analyzerOrderMenuService) {
            AnalyzerOrderMenuRestController controller = new AnalyzerOrderMenuRestController();
            ReflectionTestUtils.setField(controller, "analyzerOrderMenuService", analyzerOrderMenuService);
            return controller;
        }
    }
}
