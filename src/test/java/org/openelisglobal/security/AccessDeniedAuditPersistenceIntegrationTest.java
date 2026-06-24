package org.openelisglobal.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.audittrail.service.AccessDeniedAuditService;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * LIS-5 acceptance criterion 2: a 403 access denial produces a persisted audit
 * record attributable to the named user, read back and asserted — against a
 * real (Testcontainers) database through the production
 * {@link AuditingAccessDeniedHandler} and {@link AccessDeniedAuditService}.
 *
 * <p>
 * Criterion 1 (the role-based 403 enforcement itself) is covered by {@code
 * AccessDeniedRbacSecurityTest}; this test focuses on the persistence +
 * attribution delta, driving the real denial handler exactly as the security
 * filter chain does.
 */
public class AccessDeniedAuditPersistenceIntegrationTest extends BaseWebContextSensitiveTest {

    private static final String DENIED_LOGIN = "medtech1";
    private static final String DENIED_USER_ID = "501";
    private static final String DENIED_PATH = "/rest/DataExportStatus";

    @Autowired
    private AccessDeniedAuditService accessDeniedAuditService;

    private AuditingAccessDeniedHandler handler;

    @Before
    public void prepareDenialScenario() throws Exception {
        // Isolate this test's denial rows from any other audit activity in the suite.
        cleanRowsInCurrentConnection(new String[] { "history" });
        seedDeniedUser();
        handler = new AuditingAccessDeniedHandler(accessDeniedAuditService);
        // The named, authenticated-but-unauthorized user whose 403 we are recording.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(DENIED_LOGIN,
                "N/A", List.of(new SimpleGrantedAuthority("ROLE_RESULTS"))));
    }

    @Test
    public void rest403Denial_isPersisted_attributableToNamedUser_andReadBack() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", DENIED_PATH);
        request.setContextPath("");
        UserSessionData sessionData = new UserSessionData();
        sessionData.setSytemUserId(Integer.parseInt(DENIED_USER_ID));
        request.getSession().setAttribute(IActionConstants.USER_SESSION_DATA, sessionData);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("Access is denied"));

        // The denial response is preserved exactly (criterion: still a 403).
        assertEquals(403, response.getStatus());
        assertNotNull("denial response declares a content type", response.getContentType());
        assertTrue("denial response is JSON", response.getContentType().contains("application/json"));
        assertTrue("denial response carries the access-denied message",
                response.getContentAsString().contains("Access denied"));

        // The denial is persisted, attributable to the named user, and readable back.
        List<History> denials = accessDeniedAuditService.getDenialsForUser(DENIED_USER_ID);
        assertEquals("exactly one access-denial record for the named user", 1, denials.size());

        History denial = denials.get(0);
        assertEquals("recorded under the access-denied activity code", AccessDeniedAuditService.ACCESS_DENIED_ACTIVITY,
                denial.getActivity());
        assertEquals("attributable to the named user's SystemUser id", DENIED_USER_ID, denial.getSysUserId());
        assertNotNull("the denial captures when it happened", denial.getTimestamp());

        String detail = new String(denial.getChanges(), StandardCharsets.UTF_8);
        assertTrue("captures the named login", detail.contains(DENIED_LOGIN));
        assertTrue("captures the denied path", detail.contains(DENIED_PATH));
        assertTrue("captures the HTTP method", detail.contains("GET"));
        assertTrue("captures the 403 outcome", detail.contains("403"));
    }

    private void seedDeniedUser() {
        Integer existing = jdbcTemplate.queryForObject("SELECT count(*) FROM clinlims.system_user WHERE id = ?",
                Integer.class, Integer.parseInt(DENIED_USER_ID));
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO clinlims.system_user (id, external_id, login_name, last_name, first_name, "
                + "initials, is_active, is_employee, lastupdated) VALUES (?, ?, ?, 'Tech', 'Med', 'MT', 'Y', 'Y', now())",
                Integer.parseInt(DENIED_USER_ID), DENIED_USER_ID, DENIED_LOGIN);
    }
}
