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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * LIS-56 §S5.5 — the release-authority gate on the three result-validation save
 * endpoints. Accepting a held/flagged result finalizes it, so the save POSTs
 * are {@code @PreAuthorize("hasRole('PATHOLOGIST')")}: 401 unauthenticated, 403
 * for every non-pathologist role (including the "Validation" role that could
 * previously reach the endpoint, and Global Administrator — release authority
 * is a professional credential, not an IT permission), and a pathologist is
 * admitted through the gate. Auth ordering is asserted before any business
 * behavior so a refactor that drops an annotation is caught here (same
 * discipline as TestMethodRestControllerSecurityTest).
 *
 * <p>
 * The pathologist admission probe uses the {@code pageResults=true} branch and
 * proves penetration by verifying the first collaborator the controller body
 * touches ({@code roleService.getRoleByName}) — deeper layers reach for
 * SpringContext statics that a security slice deliberately does not provide, so
 * asserting a full 200 here would be order-dependent on which application
 * context a prior test left behind.
 */
@WebAppConfiguration
@ContextConfiguration(classes = { AccessionValidationSecurityTest.TestConfig.class })
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
    public void restSave_validationRoleWithoutPathologist_returns403() throws Exception {
        // "Validation" was the only role loosely associated with these pages before
        // LIS-56 — it must NOT confer release authority
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("validator").roles("VALIDATION"))).andExpect(status().isForbidden());
        verifyZeroInteractions(roleService); // the request never reached the controller body
    }

    @Test
    public void restSave_resultsRole_returns403() throws Exception {
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("medtech").roles("RESULTS"))).andExpect(status().isForbidden());
    }

    @Test
    public void restSave_globalAdmin_isNotReleaseAuthority_returns403() throws Exception {
        // deliberate strictness: release authority is the pathologist's professional
        // credential (RA 4688 / RA 5527); an administrator must assign themselves the
        // Pathologist role (an audited act) rather than release implicitly
        mockMvc.perform(post(REST_SAVE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(user("admin").roles("GLOBAL_ADMIN", "ADMIN"))).andExpect(status().isForbidden());
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

    @Test
    public void legacyResultValidationSave_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/ResultValidation").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void legacyResultValidationSave_nonPathologist_returns403() throws Exception {
        mockMvc.perform(post("/ResultValidation").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(user("validator").roles("VALIDATION"))).andExpect(status().isForbidden());
    }

    @Test
    public void legacyAccessionValidationRangeSave_nonPathologist_returns403() throws Exception {
        mockMvc.perform(post("/AccessionValidationRange").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(user("validator").roles("VALIDATION"))).andExpect(status().isForbidden());
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
