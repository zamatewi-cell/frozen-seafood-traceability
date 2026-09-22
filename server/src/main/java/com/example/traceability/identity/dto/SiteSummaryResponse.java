package com.example.traceability.identity.dto;

import com.example.traceability.identity.domain.Site;

/**
 * 场所目录只读摘要响应体（严格白名单投影）。
 * <p>
 * 仅用于运输任务起止场所选择与展示，不暴露详细地址、版本号与审计字段。
 * </p>
 *
 * @param id       场所内部主键
 * @param orgId    所属组织 ID
 * @param siteNo   组织内场所编号
 * @param name     场所名称
 * @param siteType 场所类型
 * @param status   场所状态
 */
public record SiteSummaryResponse(
        Long id,
        Long orgId,
        String siteNo,
        String name,
        String siteType,
        String status
) {

    public static SiteSummaryResponse fromEntity(Site site) {
        if (site == null) {
            return null;
        }
        return new SiteSummaryResponse(
                site.getId(),
                site.getOrgId(),
                site.getSiteNo(),
                site.getName(),
                site.getSiteType(),
                site.getStatus()
        );
    }
}
