package org.openelisglobal.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.openelisglobal.audittrail.service.AccessDeniedAuditService;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.util.ControllerUtills;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * {@link AccessDeniedHandler} for the default form-login filter chain that, in
 * addition to producing the established denial response (a 403 JSON body for
 * REST/Provider/API paths, or a {@code /Home?access=denied} redirect for
 * browser navigation), appends an attributable access-denial record to the
 * audit trail.
 *
 * <p>
 * This realizes the LIS-5 requirement (ISO 15189:2022, RA 5527) that a named
 * user denied an action with HTTP 403 has that denial provably recorded and
 * attributable. The denial response is unchanged from the previous inline
 * handler; recording is the added behavior.
 *
 * <p>
 * Recording is best-effort: any failure to attribute or persist the denial is
 * logged and swallowed so it can never alter or block the denial response the
 * caller receives.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedAuditService accessDeniedAuditService;

    public AuditingAccessDeniedHandler(AccessDeniedAuditService accessDeniedAuditService) {
        this.accessDeniedAuditService = accessDeniedAuditService;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean restCall = path.startsWith("/rest") || path.startsWith("/Provider")
                || path.startsWith("/api/OpenELIS-Global/rest");
        String message = (accessDeniedException instanceof CsrfException) ? "CSRF token missing or invalid"
                : "Access denied";

        recordDenial(request, path, message);

        if (restCall) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{ \"status\": 403, \"message\": \"" + message + "\" }");
        } else {
            response.sendRedirect(request.getContextPath() + "/Home?access=denied");
        }
    }

    private void recordDenial(HttpServletRequest request, String path, String message) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String loginName = (authentication != null) ? authentication.getName() : null;
            accessDeniedAuditService.recordAccessDenial(resolveSysUserId(request), loginName, request.getMethod(), path,
                    403, message);
        } catch (Exception e) {
            // Auditing must never break the denial response the caller receives.
            LogEvent.logError(this.getClass().getSimpleName(), "recordDenial",
                    "Failed to record access denial for " + request.getMethod() + " " + path + ": " + e.getMessage());
        }
    }

    private String resolveSysUserId(HttpServletRequest request) {
        try {
            return ControllerUtills.getSysUserId(request);
        } catch (Exception e) {
            return null;
        }
    }
}
