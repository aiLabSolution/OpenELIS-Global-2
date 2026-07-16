package org.openelisglobal.resultvalidation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.audittrail.dao.AuditTrailService;
import org.openelisglobal.audittrail.daoimpl.AuditTrailServiceImpl;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.dataexchange.fhir.FhirConfig;
import org.openelisglobal.dataexchange.fhir.service.FhirFacilityOrganizationService;
import org.openelisglobal.dataexchange.fhir.service.FhirTransformServiceImpl;
import org.openelisglobal.history.service.HistoryService;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.resultvalidation.action.util.ResultValidationPaging;
import org.openelisglobal.resultvalidation.bean.AnalysisItem;
import org.openelisglobal.resultvalidation.controller.rest.AccessionValidationRestController;
import org.openelisglobal.resultvalidation.form.ResultValidationForm;
import org.openelisglobal.resultvalidation.service.ResultValidationService;
import org.openelisglobal.resultvalidation.util.ResultValidationSaveService;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.test.service.TestService;
import org.openelisglobal.testterminology.service.TestTerminologyMappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * LIS-56 §S5.5 — pathologist result-release, component-tested at the service
 * seam the three validation controllers share:
 * {@link ResultValidationService#markAnalysisReleased} followed by
 * {@link ResultValidationService#persistdata} (exactly the calls
 * AccessionValidationRestController makes on an accepted item). AC1's release
 * half: a held (TechnicalAcceptance) analysis released under the named
 * pathologist login transitions to Finalized with a releasedDate, and the
 * transition lands in the append-only history table as a 'U' AuditEvent
 * carrying who (sys_user_id = the named releasing user), what (the analysis
 * row), when (timestamp) and the before-image (the prior status id in the
 * changes diff) — the after-image being the live row. Direct mutation of that
 * audit row is rejected by the LIS-6 append-only trigger. The role-gate (403)
 * half of AC1 lives in AccessionValidationSecurityTest — endpoint annotations
 * are MockMvc-security-slice territory, not a DB concern.
 *
 * <p>
 * AC2 lives here too (not in the pure-Mockito FHIR harness of S4.1): the
 * production release transition calls {@code Analysis.setReleasedDate}, whose
 * display-formatting goes through DateUtil — a class whose static initializer
 * requires the runtime configuration (SpringContext), so a Spring-less unit
 * test cannot execute the real transition (and poisons DateUtil for the whole
 * surefire fork when it tries). Running AC2 against the persisted, genuinely
 * released row is also the stronger reading of "a pilot-released result still
 * passes $validate".
 *
 * <p>
 * Fixture ids live in the 82xx range (accession LIS56R1); runtime rows are
 * removed in {@link #tearDown()}. The pathologist system_user (8201) is
 * inserted idempotently and left in place — history rows referencing it are
 * append-only and cannot be deleted.
 */
public class PathologistReleaseComponentTest extends BaseWebContextSensitiveTest {

    private static final String PATHOLOGIST_SYS_USER_ID = "8201";
    private static final String ANALYSIS_ID = "8265";
    private static final String RESULT_ID = "8266";

    /** Base-spec R4 instance validator (construction is expensive — shared). */
    private static FhirValidator fhirValidator;

    @BeforeClass
    public static void initValidator() {
        FhirContext fhirContext = FhirContext.forR4();
        ValidationSupportChain support = new ValidationSupportChain(new DefaultProfileValidationSupport(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext));
        fhirValidator = fhirContext.newValidator();
        fhirValidator.registerValidatorModule(new FhirInstanceValidator(support));
    }

    @Autowired
    private ResultValidationService resultValidationService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ResultService resultService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ReferenceTablesService referenceTablesService;

    @Autowired
    private IStatusService statusService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestService testService;

    @Autowired
    private TestTerminologyMappingService testTerminologyMappingService;

    @Autowired
    private SampleHumanService sampleHumanService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccessionValidationRestController accessionValidationRestController;

    private Object analysisServiceTarget;
    private AuditTrailService originalAnalysisAuditTrail;
    private String analysisRefTableId;

    @Before
    public void setUp() throws Exception {
        // Swap the AppTestConfig-wide AuditTrailService mock for a real one on the
        // analysis leg so the release emits a genuine history row (same idiom as
        // HistoryAppendOnlyIntegrationTest); restored in tearDown because the
        // service is a context singleton.
        AuditTrailServiceImpl realAuditTrailService = new AuditTrailServiceImpl();
        ReflectionTestUtils.setField(realAuditTrailService, "referenceTablesService", referenceTablesService);
        ReflectionTestUtils.setField(realAuditTrailService, "historyService", historyService);
        analysisServiceTarget = AopTestUtils.getUltimateTargetObject(analysisService);
        originalAnalysisAuditTrail = (AuditTrailService) ReflectionTestUtils.getField(analysisServiceTarget,
                "auditTrailService");
        ReflectionTestUtils.setField(analysisServiceTarget, "auditTrailService", realAuditTrailService);

        executeDataSetWithStateManagement("testdata/pathologist-release.xml");

        // Heal status_of_sample idempotently (earlier fixtures may have truncated
        // it) and refresh the id cache — same pattern as
        // AutoverificationGateComponentTest.
        ensureStatusRow("8291", "821", "Technical Acceptance", "ANALYSIS");
        ensureStatusRow("8292", "822", "Finalized", "ANALYSIS");
        ensureStatusRow("8293", "823", "Testing Started", "ORDER");
        ensureStatusRow("8294", "824", "SampleEntered", "SAMPLE");
        statusService.refreshCache();

        analysisRefTableId = referenceTablesService.getReferenceTableByName("ANALYSIS").getId();
        assertNotNull("ANALYSIS must be in reference_tables", analysisRefTableId);

        ensurePathologistUser();
        seedPathologistValidationLabUnit();
        seedHeldSample();
    }

    @After
    public void tearDown() {
        if (analysisServiceTarget != null) {
            ReflectionTestUtils.setField(analysisServiceTarget, "auditTrailService", originalAnalysisAuditTrail);
        }
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM clinlims.lab_unit_roles WHERE system_user_id = 8201");
        jdbcTemplate.update("DELETE FROM clinlims.user_lab_unit_roles WHERE system_user_id = 8201");
        jdbcTemplate.update("DELETE FROM clinlims.lab_roles WHERE lab_unit_role_map_id = 8240");
        jdbcTemplate.update("DELETE FROM clinlims.lab_unit_role_map WHERE lab_unit_role_map_id = 8240");
        // the base class runs without a wrapping transaction, so the release is a
        // real commit — remove the runtime chain (history rows stay: append-only)
        jdbcTemplate.update("DELETE FROM clinlims.result WHERE analysis_id = 8265");
        jdbcTemplate.update("DELETE FROM clinlims.analysis WHERE id = 8265");
        jdbcTemplate.update("DELETE FROM clinlims.sample_item WHERE id = 8264");
        jdbcTemplate.update("DELETE FROM clinlims.sample_human WHERE id = 8263");
        jdbcTemplate.update("DELETE FROM clinlims.sample WHERE accession_number = 'LIS56R1'");
        jdbcTemplate.update("DELETE FROM clinlims.patient WHERE id = 8261");
        jdbcTemplate.update("DELETE FROM clinlims.person WHERE id = 8260");
    }

    @Test
    public void heldAnalysis_releasedByNamedPathologist_finalizesAndWritesAppendOnlyAuditEvent() throws Exception {
        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);
        String finalizedId = statusService.getStatusID(AnalysisStatus.Finalized);

        releaseHeldAnalysisAsPathologist(technicalAcceptanceId);

        // --- the release itself ---
        Analysis released = analysisService.get(ANALYSIS_ID);
        assertEquals("release must finalize the analysis", finalizedId, released.getStatusId());
        assertNotNull("release must stamp the released date", released.getReleasedDate());

        // --- the AuditEvent: who / what / when / before(+after = live row) ---
        List<History> events = historyService.getHistoryByRefIdAndRefTableId(ANALYSIS_ID, analysisRefTableId);
        History releaseEvent = events.stream().filter(h -> "U".equals(h.getActivity())).findFirst().orElse(null);
        assertNotNull("the release must write an update AuditEvent", releaseEvent);
        assertEquals("who: the named releasing pathologist", PATHOLOGIST_SYS_USER_ID, releaseEvent.getSysUserId());
        assertEquals("what: the released analysis row", ANALYSIS_ID, releaseEvent.getReferenceId());
        assertEquals(analysisRefTableId, releaseEvent.getReferenceTable());
        assertNotNull("when: the event carries a timestamp", releaseEvent.getTimestamp());
        assertNotNull("before-image: the event carries the diff", releaseEvent.getChanges());
        String beforeImage = new String(releaseEvent.getChanges());
        assertTrue("before-image must record the prior (held) status, was: " + beforeImage,
                beforeImage.contains(technicalAcceptanceId));

        // --- append-only, proven on the actual release audit row (LIS-6 trigger) ---
        final String auditRowId = releaseEvent.getId();
        SQLException onUpdate = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE clinlims.history SET activity = 'X' WHERE id = " + auditRowId);
            }
        });
        assertTrue("UPDATE rejection must come from the append-only guard, was: " + onUpdate.getMessage(),
                String.valueOf(onUpdate.getMessage()).toLowerCase().contains("append-only"));
        SQLException onDelete = assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM clinlims.history WHERE id = " + auditRowId);
            }
        });
        assertTrue("DELETE rejection must come from the append-only guard, was: " + onDelete.getMessage(),
                String.valueOf(onDelete.getMessage()).toLowerCase().contains("append-only"));
    }

    /**
     * AC2 — the persisted, pathologist-released result still transforms to a FHIR
     * R4 DiagnosticReport + Observation that pass $validate (base-R4 instance
     * validation; only ERROR/FATAL fail, terminology WARNINGs are tolerated
     * offline). The transform runs against the real released DB row through the
     * production {@link FhirTransformServiceImpl} builders, wired with the real
     * context services — only FhirConfig (mocked context-wide) and the facility
     * organization lookup are local mocks.
     */
    @Test
    public void pathologistReleasedResult_stillTransformsToValidateCleanFhir() {
        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);

        releaseHeldAnalysisAsPathologist(technicalAcceptanceId);

        FhirTransformServiceImpl localTransform = new FhirTransformServiceImpl();
        ReflectionTestUtils.setField(localTransform, "statusService", statusService);
        ReflectionTestUtils.setField(localTransform, "resultService", resultService);
        ReflectionTestUtils.setField(localTransform, "sampleHumanService", sampleHumanService);
        ReflectionTestUtils.setField(localTransform, "testService", testService);
        ReflectionTestUtils.setField(localTransform, "testTerminologyMappingService", testTerminologyMappingService);
        FhirConfig fhirConfig = mock(FhirConfig.class);
        when(fhirConfig.getOeFhirSystem()).thenReturn("http://openelis-global.org");
        ReflectionTestUtils.setField(localTransform, "fhirConfig", fhirConfig);
        ReflectionTestUtils.setField(localTransform, "facilityOrganizationService",
                mock(FhirFacilityOrganizationService.class));

        // transform inside one transaction so the released row's lazy
        // associations (sample item, test) resolve during navigation
        Object[] built = new TransactionTemplate(transactionManager).execute(txStatus -> {
            Analysis released = analysisService.get(ANALYSIS_ID);
            Result releasedResult = resultService.get(RESULT_ID);
            Observation observation = localTransform.transformResultToObservation(releasedResult);
            DiagnosticReport diagnosticReport = localTransform.transformResultToDiagnosticReport(released);
            return new Object[] { observation, diagnosticReport, released.getReleasedDate() };
        });
        Observation observation = (Observation) built[0];
        DiagnosticReport diagnosticReport = (DiagnosticReport) built[1];
        java.sql.Timestamp releasedDate = (java.sql.Timestamp) built[2];
        assertNotNull("release must have stamped the released date", releasedDate);

        // released ⇒ FINAL on both resources, issued at the release moment
        assertEquals(ObservationStatus.FINAL, observation.getStatus());
        assertEquals(DiagnosticReportStatus.FINAL, diagnosticReport.getStatus());
        assertEquals(releasedDate.getTime(), observation.getIssued().getTime());

        assertNoFhirErrors("Observation", fhirValidator.validateWithResult(observation));
        assertNoFhirErrors("DiagnosticReport", fhirValidator.validateWithResult(diagnosticReport));
    }

    /**
     * AC1 end-to-end through the AUTHENTICATED endpoint (not the service seam): an
     * authenticated pathologist, scoped to the analysis's lab unit, POSTs an
     * accepted item through the real
     * {@code AccessionValidationRestController#showAccessionValidationRangeSave}
     * against the real database and paging session — and the analysis finalizes
     * with the release AuditEvent. This is the path a regression that dropped the
     * annotation, the {@code requireReleaseAuthority} call, or the release itself
     * would break; the service-seam test above cannot see those.
     */
    @Test
    public void release_throughAuthenticatedEndpoint_asScopedPathologist_finalizesAndAudits() throws Exception {
        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);
        String finalizedId = statusService.getStatusID(AnalysisStatus.Finalized);

        Analysis before = analysisService.get(ANALYSIS_ID);
        assertEquals("fixture must start held", technicalAcceptanceId, before.getStatusId());
        // history for analysis 8265 is append-only and the id is reused across
        // tests, so measure the DELTA this action makes, not the absolute count
        long updateEventsBefore = countUpdateAuditEvents();

        driveSaveEndpointAcceptingHeldAnalysis(authorities("ROLE_PATHOLOGIST", "ROLE_VALIDATION"));

        Analysis released = analysisService.get(ANALYSIS_ID);
        assertEquals("endpoint release must finalize the analysis", finalizedId, released.getStatusId());
        assertNotNull("endpoint release must stamp the released date", released.getReleasedDate());

        assertEquals("the endpoint release must write exactly one new update AuditEvent", updateEventsBefore + 1,
                countUpdateAuditEvents());
        List<History> events = historyService.getHistoryByRefIdAndRefTableId(ANALYSIS_ID, analysisRefTableId);
        assertTrue("the release AuditEvent must attribute the named releasing pathologist", events.stream()
                .anyMatch(h -> "U".equals(h.getActivity()) && PATHOLOGIST_SYS_USER_ID.equals(h.getSysUserId())));
    }

    /**
     * AC1 no-mutation-on-deny: an authenticated user WITHOUT the pathologist role
     * (Validation only) POSTing an accept is refused with
     * {@link AccessDeniedException} at the release decision point, and the analysis
     * is left untouched in the database — the persistence leg is never reached.
     */
    @Test
    public void release_throughAuthenticatedEndpoint_withoutPathologistRole_isDeniedAndDoesNotMutate() {
        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);
        // append-only history + reused analysis id: assert this action adds NO new
        // update AuditEvent, not that none exist (a prior test may have left one)
        long updateEventsBefore = countUpdateAuditEvents();

        assertThrows(AccessDeniedException.class,
                () -> driveSaveEndpointAcceptingHeldAnalysis(authorities("ROLE_VALIDATION")));

        Analysis untouched = analysisService.get(ANALYSIS_ID);
        assertEquals("a denied release must not change the analysis status", technicalAcceptanceId,
                untouched.getStatusId());
        assertNull("a denied release must not stamp a released date", untouched.getReleasedDate());
        assertEquals("a denied release must write no new update AuditEvent", updateEventsBefore,
                countUpdateAuditEvents());
    }

    /** Count of update ('U') history rows for the held analysis under test. */
    private long countUpdateAuditEvents() {
        return historyService.getHistoryByRefIdAndRefTableId(ANALYSIS_ID, analysisRefTableId).stream()
                .filter(h -> "U".equals(h.getActivity())).count();
    }

    /**
     * Drive the real REST save handler in-process for the seeded held analysis
     * (8265) with the given granted authorities. Populates the request-bound
     * SecurityContext, the OE user-session attribution and the paging session cache
     * exactly as production does (via ResultValidationPaging), then invokes the
     * controller. The accepted item carries no result value, so the release is
     * status-only (no result rows) — enough to exercise the authorization + release
     * + audit path.
     */
    private void driveSaveEndpointAcceptingHeldAnalysis(List<SimpleGrantedAuthority> grantedAuthorities)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/rest/AccessionValidation");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        UserSessionData usd = new UserSessionData();
        usd.setSytemUserId(Integer.parseInt(PATHOLOGIST_SYS_USER_ID)); // upstream typo in the setter name
        usd.setLoginName("dr.santos.pathologist");
        session.setAttribute(IActionConstants.USER_SESSION_DATA, usd);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.santos.pathologist", "n/a", grantedAuthorities));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AnalysisItem item = new AnalysisItem();
        item.setAnalysisId(ANALYSIS_ID);
        item.setAccessionNumber("LIS56R1");
        item.setReadOnly(false);
        item.setIsAccepted(true);
        item.setResultType("N");

        ResultValidationForm form = new ResultValidationForm();
        ResultValidationPaging paging = new ResultValidationPaging();
        // stores the item in the session paged cache AND sets form.resultList /
        // form.paging to page 1 — the same shape the GET populates
        paging.setDatabaseResults(request, form, new ArrayList<>(List.of(item)));

        accessionValidationRestController.showAccessionValidationRangeSave(request, form,
                new BeanPropertyBindingResult(form, "form"));
    }

    private static List<SimpleGrantedAuthority> authorities(String... roles) {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        for (String role : roles) {
            granted.add(new SimpleGrantedAuthority(role));
        }
        return granted;
    }

    /**
     * Grant user 8201 the Validation role on lab unit 8230 (the held analysis's
     * test section) — the lab-unit scope requireReleaseAuthority checks. Raw SQL:
     * the Validation role id comes from the Liquibase baseline.
     */
    private void seedPathologistValidationLabUnit() {
        String validationRoleId = jdbcTemplate
                .queryForObject("SELECT id::text FROM clinlims.system_role WHERE name = 'Validation'", String.class);
        jdbcTemplate.update(
                "INSERT INTO clinlims.lab_unit_role_map (lab_unit_role_map_id, lab_unit)" + " VALUES (8240, '8230')");
        jdbcTemplate.update("INSERT INTO clinlims.lab_roles (lab_unit_role_map_id, role) VALUES (8240, ?)",
                validationRoleId);
        jdbcTemplate.update(
                "INSERT INTO clinlims.user_lab_unit_roles (system_user_id, last_updated)" + " VALUES (8201, now())");
        jdbcTemplate.update(
                "INSERT INTO clinlims.lab_unit_roles (system_user_id, lab_unit_role_map_id)" + " VALUES (8201, 8240)");
    }

    /**
     * The exact transition + persistence the three validation controllers perform
     * for an accepted item, under the named pathologist login.
     */
    private void releaseHeldAnalysisAsPathologist(String technicalAcceptanceId) {
        Analysis analysis = analysisService.get(ANALYSIS_ID);
        assertEquals("fixture must start held", technicalAcceptanceId, analysis.getStatusId());
        assertNull("a held analysis has no released date", analysis.getReleasedDate());

        Result result = resultService.get(RESULT_ID);
        // the controllers hand persistdata results whose analysis is set — the
        // loaded entity's lazy proxy is dead once the loading transaction closes
        result.setAnalysis(analysis);
        result.setSysUserId(PATHOLOGIST_SYS_USER_ID);

        resultValidationService.markAnalysisReleased(analysis, PATHOLOGIST_SYS_USER_ID);
        resultValidationService.persistdata(new ArrayList<>(), List.of(analysis), new ArrayList<>(List.of(result)),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ResultValidationSaveService(),
                new ArrayList<>(), PATHOLOGIST_SYS_USER_ID);
    }

    private static void assertNoFhirErrors(String label, ValidationResult validationResult) {
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
                .filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
                        || message.getSeverity() == ResultSeverityEnum.FATAL)
                .collect(Collectors.toList());
        assertTrue(label + " failed FHIR $validate with ERROR/FATAL issues: " + errors, errors.isEmpty());
    }

    private void ensureStatusRow(String id, String code, String name, String statusType) {
        jdbcTemplate.update(
                "INSERT INTO clinlims.status_of_sample (id, code, name, description, status_type, lastupdated)"
                        + " SELECT ?::numeric, ?::numeric, ?, ?, ?, now() WHERE NOT EXISTS"
                        + " (SELECT 1 FROM clinlims.status_of_sample WHERE name = ? AND status_type = ?)",
                id, code, name, name, statusType, name, statusType);
    }

    /**
     * The named releasing user. Left in place across runs (like the base class's
     * ensureAuditSystemUser admin row): the history rows attributing releases to it
     * are append-only, so the user row must outlive the test.
     */
    private void ensurePathologistUser() {
        jdbcTemplate.update("INSERT INTO clinlims.system_user"
                + " (id, login_name, last_name, first_name, is_active, is_employee, lastupdated)"
                + " SELECT 8201, 'dr.santos.pathologist', 'Santos', 'Maria', 'Y', 'Y', now()"
                + " WHERE NOT EXISTS (SELECT 1 FROM clinlims.system_user WHERE id = 8201)");
    }

    /** A held (TechnicalAcceptance) analysis with a result, awaiting release. */
    private void seedHeldSample() {
        String startedId = statusService
                .getStatusID(org.openelisglobal.common.services.StatusService.OrderStatus.Started);
        String sampleEnteredId = statusService
                .getStatusID(org.openelisglobal.common.services.StatusService.SampleStatus.Entered);
        String technicalAcceptanceId = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);

        jdbcTemplate.update("INSERT INTO clinlims.person (id, lastupdated) VALUES (8260, now())");
        jdbcTemplate.update("INSERT INTO clinlims.patient (id, person_id, lastupdated, fhir_uuid)"
                + " VALUES (8261, 8260, now(), '82610000-0000-0000-0000-000000008261')");
        jdbcTemplate.update(
                "INSERT INTO clinlims.sample (id, accession_number, domain, status_id, entered_date,"
                        + " received_date, lastupdated) VALUES (8262, 'LIS56R1', 'H', ?::numeric, now(), now(), now())",
                startedId);
        jdbcTemplate.update("INSERT INTO clinlims.sample_human (id, samp_id, patient_id, lastupdated)"
                + " VALUES (8263, 8262, 8261, now())");
        jdbcTemplate.update("INSERT INTO clinlims.sample_item (id, samp_id, sort_order, typeosamp_id, status_id,"
                + " lastupdated, fhir_uuid) VALUES (8264, 8262, 1, 8215, ?::numeric, now(),"
                + " '82640000-0000-0000-0000-000000008264')", sampleEnteredId);
        jdbcTemplate.update("INSERT INTO clinlims.analysis (id, sampitem_id, test_id, test_sect_id, status_id,"
                + " analysis_type, revision, lastupdated, fhir_uuid) VALUES (8265, 8264, 8211, 8230, ?::numeric,"
                + " 'MANUAL', '1', now(), '82650000-0000-0000-0000-000000008265')", technicalAcceptanceId);
        jdbcTemplate.update("INSERT INTO clinlims.result (id, analysis_id, sort_order, result_type, value,"
                + " is_reportable, significant_digits, lastupdated, fhir_uuid)"
                + " VALUES (8266, 8265, 1, 'N', '5.2', 'Y', 1, now(), '82660000-0000-0000-0000-000000008266')");
    }
}
