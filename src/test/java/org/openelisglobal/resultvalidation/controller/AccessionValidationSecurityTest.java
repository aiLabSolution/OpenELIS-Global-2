package org.openelisglobal.resultvalidation.controller;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.common.constants.Constants;
import org.openelisglobal.config.ControllerSetup;
import org.openelisglobal.dataexchange.fhir.service.FhirTransformService;
import org.openelisglobal.login.dao.UserModuleService;
import org.openelisglobal.note.service.NoteService;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.referencetables.valueholder.ReferenceTables;
import org.openelisglobal.reports.service.DocumentTrackService;
import org.openelisglobal.reports.service.DocumentTypeService;
import org.openelisglobal.reports.valueholder.DocumentType;
import org.openelisglobal.resultvalidation.controller.rest.AccessionValidationRestController;
import org.openelisglobal.resultvalidation.service.ResultValidationService;
import org.openelisglobal.role.service.RoleService;
import org.openelisglobal.role.valueholder.Role;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.search.service.SearchResultsService;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.service.UserService;
import org.openelisglobal.test.service.TestSectionService;
import org.openelisglobal.testresult.service.TestResultService;
import org.openelisglobal.view.PageBuilderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * LIS-56 — endpoint-boundary authorization on the three result-validation save
 * POSTs. The saves handle paging, rejection AND release, so the endpoint admits
 * validation-queue actors ({@code hasAnyRole('PATHOLOGIST','VALIDATION')}): 401
 * unauthenticated, 403 for roles outside the queue (Results; and Global
 * Administrator ALONE — see the caveat on that test), admission for Validation
 * and Pathologist. The release decision itself is gated deeper, at the
 * accepted-item branch ({@code requireReleaseAuthority}); that half —
 * pathologist role + lab-unit scope, and the no-mutation-on-deny property — is
 * proven end-to-end in PathologistReleaseComponentTest, because it needs the
 * real paging session and database. This slice additionally pins the
 * ControllerSetup interplay: an AccessDeniedException raised INSIDE a handler
 * body must surface as 403, not be swallowed into a 500 by the generic
 * RuntimeException advice (ControllerSetup is registered in this slice for
 * exactly that reason).
 *
 * <p>
 * The admission probes stop at the first server-owned collaborator or the
 * stale-page early return — deeper layers reach for SpringContext statics that
 * a security slice deliberately does not provide, so asserting deeper behavior
 * here would be order-dependent on which application context a prior test left
 * behind.
 */
@WebAppConfiguration
@ContextConfiguration(classes = { AccessionValidationSecurityTest.TestConfig.class, ControllerSetup.class })
@TestPropertySource("classpath:common.properties")
public class AccessionValidationSecurityTest extends SecuritySliceMockMvcTest {

    private static final String REST_SAVE_PATH = "/rest/AccessionValidation";

    @Autowired
    private RoleService roleService;

    @Before
    public void clearMockState() {
        // the slice context (and its mocks) is shared across the test methods
        Mockito.clearInvocations(roleService);
    }

    @Test
    public void restSave_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void restSave_resultsRole_isOutsideTheValidationQueue_returns403() throws Exception {
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("medtech").roles("RESULTS"))).andExpect(status().isForbidden());
        verifyZeroInteractions(roleService); // the request never reached the controller body
    }

    /**
     * Global Administrator authority ALONE does not admit — authority follows
     * roles, not admin-ness. Caveat for the deployed default admin account: the
     * upstream seeds grant it the Pathologist role (2.8 update_default_setting.xml)
     * and Validation across AllLabUnits (2.7 add_admin_default_roles.xml), so the
     * REAL default admin passes both this gate and the release gate; restricting
     * that account is provisioning-runbook territory (tracked as LIS-249) — the
     * code-level contract pinned here is only that ADMIN grants nothing by itself.
     */
    @Test
    public void restSave_globalAdminAlone_returns403() throws Exception {
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("admin").roles("GLOBAL_ADMIN", "ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    public void restSave_validationRole_isAdmitted() throws Exception {
        // Validation-role users keep paging, rejection and note edits: the
        // endpoint admits them; only the accepted-item branch requires the
        // pathologist (proven end-to-end in PathologistReleaseComponentTest).
        int status;
        try {
            status = mockMvc
                    .perform(post(REST_SAVE_PATH + "?pageResults=true").contentType(MediaType.APPLICATION_JSON)
                            .content("{}").with(user("validator").roles("VALIDATION")))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            status = -1;
        }
        assertNotEquals("validation role must not be rejected at the endpoint", 401, status);
        assertNotEquals("validation role must not be rejected at the endpoint", 403, status);
        verify(roleService).getRoleByName(Constants.ROLE_VALIDATION);
    }

    @Test
    public void restSave_pathologist_isAdmittedThroughTheGate() throws Exception {
        int status;
        try {
            status = mockMvc
                    .perform(post(REST_SAVE_PATH + "?pageResults=true").contentType(MediaType.APPLICATION_JSON)
                            .content("{}").with(user("dr.santos").roles("PATHOLOGIST")))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            // downstream SpringContext statics are unavailable in a security slice;
            // an exception out of the controller body still proves admission
            status = -1;
        }
        assertNotEquals("pathologist must not be rejected by the gate", 401, status);
        assertNotEquals("pathologist must not be rejected by the gate", 403, status);
        // the controller body was actually entered: its first collaborator ran
        verify(roleService).getRoleByName(Constants.ROLE_VALIDATION);
    }

    /**
     * Regression pin for the ControllerSetup contract this slice registers the real
     * advice for: an AccessDeniedException thrown INSIDE a handler body (the shape
     * requireReleaseAuthority produces at the accepted-item branch) must pass the
     * generic RuntimeException advice untouched and surface as 403 through the
     * security filter chain. If someone removes the rethrowing handler from
     * ControllerSetup, this becomes a 500 and fails here.
     */
    @Test
    public void accessDeniedRaisedInsideHandlerBody_surfacesAs403_notAdviceSwallowed500() throws Exception {
        mockMvc.perform(post("/test/lis56-denied-probe").with(user("validator").roles("VALIDATION")))
                .andExpect(status().isForbidden());
    }

    @Test
    public void legacyResultValidationSave_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/ResultValidation").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void legacyResultValidationSave_roleOutsideQueue_returns403() throws Exception {
        mockMvc.perform(post("/ResultValidation").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(user("medtech").roles("RESULTS"))).andExpect(status().isForbidden());
    }

    @Test
    public void legacyAccessionValidationRangeSave_roleOutsideQueue_returns403() throws Exception {
        mockMvc.perform(post("/AccessionValidationRange").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(user("medtech").roles("RESULTS"))).andExpect(status().isForbidden());
    }

    /** Throws the exact exception shape requireReleaseAuthority produces. */
    @Controller
    static class DeniedProbeController {
        @PostMapping("/test/lis56-denied-probe")
        public String deny() {
            throw new AccessDeniedException("probe: release authority denied inside the handler body");
        }
    }

    /**
     * Deliberately NOT {@code @Configuration}: AppTestConfig component-scans the
     * whole org.openelisglobal.resultvalidation tree (its controller subpackage is
     * not excluded), and test classes are on that classpath — a scannable
     * configuration here would leak these mock beans into the shared web context
     * and break it. Registered explicitly via {@code @ContextConfiguration}, which
     * still processes the {@code @Bean} methods (lite mode) and the
     * {@code @Enable*} annotations. Same idiom as
     * AnalyzerOrderMenuRestControllerSecurityTest.
     */
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

        @Bean
        DeniedProbeController deniedProbeController() {
            return new DeniedProbeController();
        }

        @Bean
        AccessionValidationRestController accessionValidationRestController(AnalysisService analysisService,
                TestResultService testResultService, SampleHumanService sampleHumanService,
                DocumentTrackService documentTrackService, TestSectionService testSectionService,
                SystemUserService systemUserService, ReferenceTablesService referenceTablesService,
                DocumentTypeService documentTypeService, ResultValidationService resultValidationService,
                NoteService noteService, FhirTransformService fhirTransformService) {
            return new AccessionValidationRestController(analysisService, testResultService, sampleHumanService,
                    documentTrackService, testSectionService, systemUserService, referenceTablesService,
                    documentTypeService, resultValidationService, noteService, fhirTransformService);
        }

        @Bean
        ResultValidationController resultValidationController(AnalysisService analysisService,
                TestResultService testResultService, SampleHumanService sampleHumanService,
                DocumentTrackService documentTrackService, TestSectionService testSectionService,
                SystemUserService systemUserService, ReferenceTablesService referenceTablesService,
                DocumentTypeService documentTypeService, ResultValidationService resultValidationService,
                NoteService noteService, FhirTransformService fhirTransformService) {
            return new ResultValidationController(analysisService, testResultService, sampleHumanService,
                    documentTrackService, testSectionService, systemUserService, referenceTablesService,
                    documentTypeService, resultValidationService, noteService, fhirTransformService);
        }

        @Bean
        AccessionValidationRangeController accessionValidationRangeController(AnalysisService analysisService,
                TestResultService testResultService, SampleHumanService sampleHumanService,
                DocumentTrackService documentTrackService, TestSectionService testSectionService,
                SystemUserService systemUserService, ReferenceTablesService referenceTablesService,
                DocumentTypeService documentTypeService, ResultValidationService resultValidationService,
                NoteService noteService, FhirTransformService fhirTransformService) {
            return new AccessionValidationRangeController(analysisService, testResultService, sampleHumanService,
                    documentTrackService, testSectionService, systemUserService, referenceTablesService,
                    documentTypeService, resultValidationService, noteService, fhirTransformService);
        }

        // --- collaborators; the controller constructors resolve reference-table and
        // document-type ids eagerly, so those two mocks carry stubs ---

        @Bean
        ReferenceTablesService referenceTablesService() {
            ReferenceTablesService mock = mock(ReferenceTablesService.class);
            ReferenceTables resultTable = mock(ReferenceTables.class);
            when(resultTable.getId()).thenReturn("1");
            when(mock.getReferenceTableByName("RESULT")).thenReturn(resultTable);
            return mock;
        }

        @Bean
        DocumentTypeService documentTypeService() {
            DocumentTypeService mock = mock(DocumentTypeService.class);
            DocumentType resultExport = mock(DocumentType.class);
            when(resultExport.getId()).thenReturn("1");
            when(mock.getDocumentTypeByName("resultExport")).thenReturn(resultExport);
            return mock;
        }

        @Bean
        RoleService roleService() {
            RoleService mock = mock(RoleService.class);
            Role validationRole = mock(Role.class);
            when(validationRole.getId()).thenReturn("10");
            when(mock.getRoleByName(Constants.ROLE_VALIDATION)).thenReturn(validationRole);
            return mock;
        }

        @Bean
        AnalysisService analysisService() {
            return mock(AnalysisService.class);
        }

        @Bean
        TestResultService testResultService() {
            return mock(TestResultService.class);
        }

        @Bean
        SampleHumanService sampleHumanService() {
            return mock(SampleHumanService.class);
        }

        @Bean
        DocumentTrackService documentTrackService() {
            return mock(DocumentTrackService.class);
        }

        @Bean
        TestSectionService testSectionService() {
            return mock(TestSectionService.class);
        }

        @Bean
        SystemUserService systemUserService() {
            return mock(SystemUserService.class);
        }

        @Bean
        ResultValidationService resultValidationService() {
            return mock(ResultValidationService.class);
        }

        @Bean
        NoteService noteService() {
            return mock(NoteService.class);
        }

        @Bean
        FhirTransformService fhirTransformService() {
            return mock(FhirTransformService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        SearchResultsService searchResultsService() {
            return mock(SearchResultsService.class);
        }

        @Bean
        SampleService sampleService() {
            return mock(SampleService.class);
        }

        @Bean
        UserModuleService userModuleService() {
            return mock(UserModuleService.class);
        }

        @Bean
        PageBuilderService pageBuilderService() {
            return mock(PageBuilderService.class);
        }
    }
}
