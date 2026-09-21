package com.example.traceability.identity.application;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 组织目录最小只读应用服务。
 * <p>
 * 企业用户仅可读取本组织摘要；PLATFORM 作用域可读取任意组织摘要。
 * 越权请求在查询数据库之前即返回 403 ORG_SCOPE_DENIED，避免通过 404/403 差异探测其他组织是否存在。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class OrganizationDirectoryApplicationService {

    private final OrganizationMapper organizationMapper;

    public OrganizationDirectoryApplicationService(OrganizationMapper organizationMapper) {
        this.organizationMapper = organizationMapper;
    }

    /**
     * 查询组织白名单摘要。
     *
     * @param orgId     组织 ID
     * @param principal 当前认证主体
     * @return 组织摘要
     */
    public OrganizationSummaryResponse getOrganization(Long orgId, TraceSecurityPrincipal principal) {
        if (!isPlatformScope(principal) && !Objects.equals(orgId, principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权访问其他组织的目录信息"
            );
        }

        Organization organization = organizationMapper.selectById(orgId);
        if (organization == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + orgId + " 的组织");
        }
        return OrganizationSummaryResponse.fromEntity(organization);
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }
}
