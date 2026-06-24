package org.openelisglobal.audittrail.service;

import java.util.List;
import org.openelisglobal.audittrail.valueholder.History;

/**
 * Records and reads back access-control denial events (HTTP 403) so that every
 * denied action is provably attributable to the named user who attempted it.
 *
 * <p>
 * This is the LIS-5 compliance delta: under ISO 15189:2022 and RA 5527 a user
 * lacking a required role must not only be denied (403) but the denial must be
 * captured as an attributable audit record. Denials are appended to the unified
 * audit trail ({@code HISTORY}) under a dedicated
 * {@link #ACCESS_DENIED_ACTIVITY activity code}, alongside the existing
 * data-mutation (I/U/D) events, so they inherit the same append-only audit
 * spine.
 */
public interface AccessDeniedAuditService {

    /**
     * Activity code stamped on {@code HISTORY} rows that record an access denial.
     * Distinct from the data-mutation codes (I/U/D) and the database-clean code (T)
     * already in use.
     */
    String ACCESS_DENIED_ACTIVITY = "X";

    /**
     * Append an attributable access-denial record to the audit trail.
     *
     * @param sysUserId     the numeric {@code SystemUser} id of the denied
     *                      (authenticated) user; when blank or non-numeric the
     *                      denial cannot be attributed and is not persisted, since
     *                      {@code HISTORY.sys_user_id} is NOT NULL
     * @param loginName     the login name of the denied user, denormalized into the
     *                      record so attribution survives even if the user is later
     *                      removed
     * @param requestMethod the HTTP method of the denied request
     * @param requestUri    the request path that was denied
     * @param httpStatus    the HTTP status returned for the denial (403)
     * @param reason        a short human-readable denial reason
     */
    void recordAccessDenial(String sysUserId, String loginName, String requestMethod, String requestUri, int httpStatus,
            String reason);

    /**
     * Read back all access-denial records attributed to the given user, most recent
     * first.
     *
     * @param sysUserId the numeric {@code SystemUser} id to look up
     * @return the denial records for that user (empty if none or if the id is not
     *         numeric)
     */
    List<History> getDenialsForUser(String sysUserId);
}
