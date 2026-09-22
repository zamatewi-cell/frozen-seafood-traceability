package com.example.traceability.identity.application;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.dto.SiteSummaryResponse;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 组织与场所目录最小只读应用服务。
 * <p>
 * 企业端交接与运输页面需要选择接收组织、承运组织与起止场所，并展示交易对手名称，
 * 因此已认证用户可读取组织白名单摘要（编号、名称、类型、状态）与启用场所白名单摘要
 * （编号、名称、类型，不含详细地址）。目录不提供任何写操作，也不暴露信用代码、地址与审计字段。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class OrganizationDirectoryApplicationService {

    private static final Set<String> ORG_TYPES = Set.of(
            "SOURCE", "PROCESSOR", "WAREHOUSE", "CARRIER", "DISTRIBUTOR", "RETAILER"
    );

    private final OrganizationMapper organizationMapper;
    private final SiteMapper siteMapper;

    public OrganizationDirectoryApplicationService(OrganizationMapper organizationMapper, SiteMapper siteMapper) {
        this.organizationMapper = organizationMapper;
        this.siteMapper = siteMapper;
    }

    /**
     * 查询组织白名单摘要。
     *
     * @param orgId     组织 ID
     * @param principal 当前认证主体
     * @return 组织摘要
     */
    public OrganizationSummaryResponse getOrganization(Long orgId, TraceSecurityPrincipal principal) {
        Organization organization = organizationMapper.selectById(orgId);
        if (organization == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + orgId + " 的组织");
        }
        return OrganizationSummaryResponse.fromEntity(organization);
    }

    /**
     * 查询启用组织目录，可按组织类型筛选。
     *
     * @param orgType   组织类型筛选（可空）
     * @param principal 当前认证主体
     * @return 组织摘要列表
     */
    public List<OrganizationSummaryResponse> listActiveOrganizations(String orgType, TraceSecurityPrincipal principal) {
        String normalized = null;
        if (orgType != null && !orgType.isBlank()) {
            normalized = orgType.trim().toUpperCase(Locale.ROOT);
            if (!ORG_TYPES.contains(normalized)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "orgType 必须为 SOURCE、PROCESSOR、WAREHOUSE、CARRIER、DISTRIBUTOR 或 RETAILER"
                );
            }
        }
        return organizationMapper.selectActive(normalized).stream()
                .map(OrganizationSummaryResponse::fromEntity)
                .toList();
    }

    /**
     * 查询指定组织的启用场所目录。
     *
     * @param orgId     组织 ID
     * @param principal 当前认证主体
     * @return 场所摘要列表
     */
    public List<SiteSummaryResponse> listActiveSites(Long orgId, TraceSecurityPrincipal principal) {
        if (organizationMapper.selectById(orgId) == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + orgId + " 的组织");
        }
        return siteMapper.selectActiveByOrgId(orgId).stream()
                .map(SiteSummaryResponse::fromEntity)
                .toList();
    }
}
