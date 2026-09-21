package com.example.traceability.identity.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.application.OrganizationDirectoryApplicationService;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织目录最小只读控制器。
 * <p>
 * 仅提供企业端页面展示所需的组织白名单摘要读取，不提供组织列表或任何写操作。
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
     * 查询指定组织的白名单摘要（企业用户仅限本组织，PLATFORM 作用域不限）。
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
}
