package org.openelisglobal.audittrail.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.history.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessDeniedAuditServiceImpl implements AccessDeniedAuditService {

    // HISTORY.reference_id / reference_table are NOT-NULL numeric columns with no
    // foreign key. An access denial references no entity row, so use the same
    // sentinel that DatabaseCleaningRestController uses for non-mutation system
    // events; the denial detail (who/what/when) lives in the sysUserId, timestamp
    // and the changes payload instead.
    private static final String NO_ENTITY_SENTINEL = "0";

    private static final int READ_BACK_PAGE_SIZE = 1000;

    @Autowired
    private HistoryService historyService;

    @Override
    @Transactional
    public void recordAccessDenial(String sysUserId, String loginName, String requestMethod, String requestUri,
            int httpStatus, String reason) {
        if (sysUserId == null || sysUserId.isEmpty() || !GenericValidator.isInt(sysUserId)) {
            // HISTORY.sys_user_id is NOT NULL and numeric; an unattributable denial
            // (e.g. an authenticated principal with no provisioned SystemUser) cannot
            // be persisted. Log and skip rather than fail the denial response.
            LogEvent.logWarn(this.getClass().getSimpleName(), "recordAccessDenial",
                    "Access denial for login '" + loginName + "' to " + requestMethod + " " + requestUri
                            + " not recorded: no numeric SystemUser id to attribute it to");
            return;
        }
        History history = new History();
        history.setActivity(ACCESS_DENIED_ACTIVITY);
        history.setTimestamp(new Timestamp(System.currentTimeMillis()));
        history.setSysUserId(sysUserId);
        history.setReferenceId(NO_ENTITY_SENTINEL);
        history.setReferenceTable(NO_ENTITY_SENTINEL);
        history.setChanges(
                buildDetail(loginName, requestMethod, requestUri, httpStatus, reason).getBytes(StandardCharsets.UTF_8));
        historyService.insert(history);
    }

    @Override
    @Transactional(readOnly = true)
    public List<History> getDenialsForUser(String sysUserId) {
        if (sysUserId == null || sysUserId.isEmpty() || !GenericValidator.isInt(sysUserId)) {
            return Collections.emptyList();
        }
        return historyService.getSystemEventHistory(null, null, sysUserId, null, ACCESS_DENIED_ACTIVITY, null, null, 1,
                READ_BACK_PAGE_SIZE);
    }

    // Serialize the denial detail as the same simple <tag>value</tag> XML the audit
    // trail's changes column already carries, so a denial record is self-describing
    // (who attempted what, when, and the outcome) when read back.
    private String buildDetail(String loginName, String requestMethod, String requestUri, int httpStatus,
            String reason) {
        StringBuilder detail = new StringBuilder("<accessDenial>");
        appendElement(detail, "loginName", loginName);
        appendElement(detail, "method", requestMethod);
        appendElement(detail, "path", requestUri);
        appendElement(detail, "httpStatus", String.valueOf(httpStatus));
        appendElement(detail, "reason", reason);
        detail.append("</accessDenial>");
        return detail.toString();
    }

    private void appendElement(StringBuilder builder, String tag, String value) {
        builder.append('<').append(tag).append('>').append(escapeXml(value)).append("</").append(tag).append('>');
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
