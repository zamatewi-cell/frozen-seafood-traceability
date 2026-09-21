package com.example.traceability.identity.dto;

import com.example.traceability.identity.domain.Organization;

/**
 * 组织目录只读摘要响应体（严格白名单投影）。
 * <p>
 * 仅用于企业端页面展示当前责任组织名称等基础信息，
 * 绝不暴露 creditCode、version、审计人等内部或敏感字段。
 * </p>
 *
 * @param id      组织内部主键
 * @param orgNo   组织业务编号
 * @param name    组织名称
 * @param orgType 组织类型
 * @param status  组织状态
 */
public record OrganizationSummaryResponse(
        Long id,
        String orgNo,
        String name,
        String orgType,
        String status
) {

    public static OrganizationSummaryResponse fromEntity(Organization organization) {
        if (organization == null) {
            return null;
        }
        return new OrganizationSummaryResponse(
                organization.getId(),
                organization.getOrgNo(),
                organization.getName(),
                organization.getOrgType(),
                organization.getStatus()
        );
    }
}
