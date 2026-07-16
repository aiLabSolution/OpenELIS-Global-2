package org.openelisglobal.resultvalidation.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.constants.Constants;
import org.openelisglobal.common.controller.BaseController;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.internationalization.MessageUtil;
import org.openelisglobal.resultvalidation.bean.AnalysisItem;
import org.openelisglobal.role.service.RoleService;
import org.openelisglobal.systemuser.controller.UnifiedSystemUserController;
import org.openelisglobal.systemuser.service.UserService;
import org.openelisglobal.userrole.valueholder.LabUnitRoleMap;
import org.openelisglobal.userrole.valueholder.UserLabUnitRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public abstract class BaseResultValidationController extends BaseController {

    private String titleKey = "";

    @Autowired
    private UserService releaseAuthorityUserService;

    @Autowired
    private RoleService releaseAuthorityRoleService;

    @Override
    protected String getPageTitleKey() {
        return titleKey;
    }

    @Override
    protected String getPageSubtitleKey() {
        return titleKey;
    }

    @Override
    protected String getMessageForKey(String messageKey) {
        return MessageUtil.getMessage("validation.title", messageKey);
    }

    protected void setRequestType(String section) {
        if (!GenericValidator.isBlankOrNull(section)) {
            titleKey = section;
        }
    }

    /**
     * LIS-56: reject a whole validation submission that gives one analysis
     * contradictory dispositions. A multi-result analysis is served as several rows
     * (one per result, sharing an analysisId); the save applies the status
     * transition only for the FIRST row of each analysis (guarded by
     * analysisIdList), but still persists every row's result value and note.
     * Without this pre-flight check a request that accepts row A and rejects row B
     * of the same analysis would finalize+release the analysis (row A) yet persist
     * row B's mutated result and a rejection note — a released analysis carrying an
     * explicitly rejected row. Run this over the server-cached, identity-verified
     * rows BEFORE any mutation so the entire request fails closed on a
     * contradiction (this also covers the single-row accept+reject case). Operates
     * on decisions merged onto server-owned rows, so a tampered or reordered client
     * row cannot slip a hidden disposition past it.
     */
    protected void assertConsistentDispositions(List<AnalysisItem> resultItems) {
        Set<String> acceptedAnalyses = new HashSet<>();
        Set<String> rejectedAnalyses = new HashSet<>();
        for (AnalysisItem item : resultItems) {
            if (item.isReadOnly() || GenericValidator.isBlankOrNull(item.getAnalysisId())) {
                continue;
            }
            if (item.getIsAccepted()) {
                acceptedAnalyses.add(item.getAnalysisId());
            }
            if (item.getIsRejected()) {
                rejectedAnalyses.add(item.getAnalysisId());
            }
        }
        for (String analysisId : acceptedAnalyses) {
            if (rejectedAnalyses.contains(analysisId)) {
                throw new LIMSRuntimeException("Analysis " + analysisId + " has both accepted and rejected result"
                        + " rows in one submission — refusing the contradictory validation request");
            }
        }
    }

    /**
     * LIS-56 release authority, checked at the exact decision point (the
     * accepted-item branch of the save): accepting a held result finalizes it, and
     * that authority belongs to the named pathologist login (RA 4688 / RA 5527),
     * scoped to the lab units where the user holds the Validation (queue) role.
     * Everything this check trusts is server-owned: the current authentication's
     * authorities, the analysis loaded from the database (never the client bean),
     * and the user's lab-unit role grants from the database. Rejection, paging and
     * note edits are deliberately NOT gated here — those remain Validation-role
     * work.
     *
     * <p>
     * The lab-unit scope is read straight from the {@code user_lab_unit_roles}
     * grants keyed by system-user id ({@link UserService#getUserLabUnitRoles}), so
     * it resolves identically for form/session, Basic, SAML and OAuth logins —
     * unlike the queue's {@code getUserTestSections}, which branches on the
     * principal type. There is no auth-method-specific release-scope path to
     * diverge.
     *
     * <p>
     * Throws {@link AccessDeniedException}: it reaches Spring Security's
     * ExceptionTranslationFilter (ControllerSetup rethrows it past the generic
     * RuntimeException advice), which answers 403. On the default (form/session)
     * filter chain — where pathologists actually work through the React app — that
     * chain's {@code AuditingAccessDeniedHandler} records the denial (LIS-5). NOTE:
     * the Basic/SAML/OAuth/cert chains do not yet register that handler, so a
     * release denial on a direct Basic-auth API call is a correct 403 but is not
     * audited; wiring the handler onto every chain is a pre-existing LIS-5 gap
     * tracked as LIS-250 (it is not introduced by this release gate).
     */
    protected void requireReleaseAuthority(Analysis analysis, String sysUserId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isPathologist = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_PATHOLOGIST".equals(granted.getAuthority()));
        if (!isPathologist) {
            throw new AccessDeniedException("Releasing (accepting) a validation result requires the Pathologist role");
        }

        String analysisTestSectionId = analysis.getTestSection() == null ? null : analysis.getTestSection().getId();
        if (analysisTestSectionId == null || !inValidationLabUnitScope(sysUserId, analysisTestSectionId)) {
            throw new AccessDeniedException("Releasing analysis " + analysis.getId()
                    + " is outside the user's Validation lab-unit scope (test section " + analysisTestSectionId + ")");
        }
    }

    /**
     * Whether the user holds the Validation role for the given test section —
     * straight from the database-backed lab-unit role grants (the same grants that
     * scope the validation queue), with the {@code AllLabUnits} wildcard honored.
     */
    private boolean inValidationLabUnitScope(String sysUserId, String testSectionId) {
        String validationRoleId = releaseAuthorityRoleService.getRoleByName(Constants.ROLE_VALIDATION).getId();
        UserLabUnitRoles labUnitRoles = releaseAuthorityUserService.getUserLabUnitRoles(sysUserId);
        if (labUnitRoles == null || labUnitRoles.getLabUnitRoleMap() == null) {
            return false;
        }
        for (LabUnitRoleMap unitRoles : labUnitRoles.getLabUnitRoleMap()) {
            if (unitRoles.getRoles() == null || !unitRoles.getRoles().contains(validationRoleId)) {
                continue;
            }
            if (UnifiedSystemUserController.ALL_LAB_UNITS.equals(unitRoles.getLabUnit())
                    || testSectionId.equals(unitRoles.getLabUnit())) {
                return true;
            }
        }
        return false;
    }
}
