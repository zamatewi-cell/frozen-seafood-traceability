package com.example.traceability.sale.dto;

import com.example.traceability.sale.domain.Sale;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 终端销售白名单响应 DTO（不暴露幂等键与请求哈希）。
 * <p>
 * {@code occurredAt} 与数据库 {@code sale.occurred_at} 完全一致（UTC，微秒精度），不做二次截断。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record SaleResponse(
        Long id,
        Long batchId,
        Long orgId,
        Long siteId,
        String siteName,
        BigDecimal quantity,
        String unitCode,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime occurredAt,
        String status,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime createdAt
) {

    public static SaleResponse fromEntity(Sale sale, String siteName) {
        return new SaleResponse(
                sale.getId(),
                sale.getBatchId(),
                sale.getOrgId(),
                sale.getSiteId(),
                siteName,
                sale.getQuantity(),
                sale.getUnitCode(),
                sale.getOccurredAt() != null ? sale.getOccurredAt().atOffset(ZoneOffset.UTC) : null,
                sale.getStatus(),
                sale.getCreatedBy(),
                sale.getCreatedAt() != null ? sale.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
