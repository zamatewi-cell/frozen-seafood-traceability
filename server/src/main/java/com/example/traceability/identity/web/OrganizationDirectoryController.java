package com.example.traceability.identity.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.application.OrganizationDirectoryApplicationService;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.dto.SiteSummaryResponse;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织与场所目录最小只读控制器。
 * <p>
 * 仅提供企业端交接与运输页面所需的组织 / 场所白名单摘要读取，不提供任何写操作。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationDirectoryController {

    private final OrganizationDirectoryApplicationService organizationDirectoryService;

    public OrganizationDirectoryController(OrganizationDirectoryApplicationService organizationDirectoryService) {
        this.organizationDirectoryService = organizationDirectoryService;
    }

    /**
     * 查询启用组织目录 (GET /api/v1/organizations?orgType=)。
     */
    @GetMapping
    public SuccessEnvelope<List<OrganizationSummaryResponse>> listOrganizations(
            @RequestParam(value = "orgType", required = false) String orgType,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(organizationDirectoryService.listActiveOrganizations(orgType, principal));
    }

    /**
     * 查询指定组织的白名单摘要 (GET /api/v1/organizations/{orgId})。
     *
     * @param orgId     组织 ID
     * @param principal 当前认证主体
     * @return 组织摘要封套
     */
    @GetMapping("/{orgId}")
    public SuccessEnvelope<OrganizationSummaryResponse> getOrganization(
            @PathVariable("orgId") Long orgId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(organizationDirectoryService.getOrganization(orgId, principal));
    }

    /**
     * 查询指定组织的启用场所目录 (GET /api/v1/organizations/{orgId}/sites)。
     */
    @GetMapping("/{orgId}/sites")
    public SuccessEnvelope<List<SiteSummaryResponse>> listSites(
            @PathVariable("orgId") Long orgId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(organizationDirectoryService.listActiveSites(orgId, principal));
    }
}
