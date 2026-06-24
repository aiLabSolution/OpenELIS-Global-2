package org.openelisglobal.security;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.audittrail.service.AccessDeniedAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * LIS-5 acceptance criterion 1: named-user RBAC is enforced — a user holding
 * the required role performs the action successfully, a named user lacking that
 * role is denied with HTTP 403, and an unauthenticated caller gets 401.
 *
 * <p>
 * This also exercises the real {@link AuditingAccessDeniedHandler} wired into
 * the filter chain, asserting that a chained 403 drives an attributable
 * denial-recording call (the persistence of that record is proven end-to-end
 * against a real database in
 * {@code AccessDeniedAuditPersistenceIntegrationTest}).
 */
@WebAppConfiguration
@ContextConfiguration(classes = { AccessDeniedRbacSecurityTest.TestConfig.class })
public class AccessDeniedRbacSecurityTest extends SecuritySliceMockMvcTest {

    private static final String PROTECTED_PATH = "/rest/lis5/admin-only";

    @Autowired
    private AccessDeniedAuditService accessDeniedAuditService;

    @Before
    public void resetAuditService() {
        reset(accessDeniedAuditService);
    }

    @Test
    public void userWithRequiredRole_isAllowed() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).with(user("labmanager").roles("ADMIN"))).andExpect(status().isOk());
        verify(accessDeniedAuditService, never()).recordAccessDenial(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    public void unauthenticatedUser_isUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isUnauthorized());
        // Anonymous requests are rejected by the authentication entry point (401),
        // not the access-denied handler, so no denial record is attempted.
        verify(accessDeniedAuditService, never()).recordAccessDenial(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    public void namedUserLackingRole_isForbidden_andDenialIsRecorded() throws Exception {
        String body = mockMvc.perform(get(PROTECTED_PATH).with(user("medtech").roles("RESULTS")))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertTrue("denial response carries the access-denied message", body.contains("Access denied"));

        // The denial is recorded, attributable to the named user, capturing what was
        // attempted and the 403 outcome.
        verify(accessDeniedAuditService, times(1)).recordAccessDenial(any(), eq("medtech"), eq("GET"),
                eq(PROTECTED_PATH), eq(403), eq("Access denied"));
    }

    @RestController
    static class AdminOnlyController {
        @GetMapping(PROTECTED_PATH)
        @PreAuthorize("hasRole('ADMIN')")
        public String adminOnly() {
            return "ok";
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestConfig {

        @Bean
        AccessDeniedAuditService accessDeniedAuditService() {
            return mock(AccessDeniedAuditService.class);
        }

        @Bean
        AccessDeniedHandler auditingAccessDeniedHandler(AccessDeniedAuditService accessDeniedAuditService) {
            return new AuditingAccessDeniedHandler(accessDeniedAuditService);
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, AccessDeniedHandler auditingAccessDeniedHandler)
                throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable())
                    .exceptionHandling(ex -> ex.accessDeniedHandler(auditingAccessDeniedHandler));
            return http.build();
        }

        @Bean
        AdminOnlyController adminOnlyController() {
            return new AdminOnlyController();
        }
    }
}
