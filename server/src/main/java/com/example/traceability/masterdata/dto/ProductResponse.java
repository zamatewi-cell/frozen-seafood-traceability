package com.example.traceability.masterdata.dto;

import com.example.traceability.masterdata.domain.Product;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 产品主数据响应 DTO。
 * <p>
 * 严格白名单输出，杜绝泄露 {@code isDeleted} 等内部逻辑删除或敏感标识。
 * 遵循 API 契约，输出带明确 UTC 偏移的 ISO 8601 时间戳。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record ProductResponse(
        Long id,
        String productCode,
        String publicName,
        String scientificName,
        String category,
        String productType,
        String specification,
        String sourceType,
        String baseUnitCode,
        String status,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy
) {

    public static ProductResponse fromEntity(Product p) {
        if (p == null) {
            return null;
        }
        return new ProductResponse(
                p.getId(),
                p.getProductCode(),
                p.getPublicName(),
                p.getScientificName(),
                p.getCategory(),
                p.getProductType(),
                p.getSpecification(),
                p.getSourceType(),
                p.getBaseUnitCode(),
                p.getStatus(),
                p.getVersion(),
                p.getCreatedAt() != null ? p.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                p.getCreatedBy(),
                p.getUpdatedAt() != null ? p.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                p.getUpdatedBy()
        );
    }
}
