package com.example.traceability.masterdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建产品主数据请求 DTO。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record ProductCreateRequest(

        @NotBlank(message = "产品全局统一编码不能为空")
        @Size(min = 2, max = 32, message = "产品编码长度必须在 2 到 32 个字符之间")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "产品编码只能包含英文字母、数字、下划线和短横线")
        String productCode,

        @NotBlank(message = "产品公开名称不能为空")
        @Size(min = 1, max = 128, message = "产品公开名称长度不能超过 128 个字符")
        String publicName,

        @Size(max = 128, message = "水产品学名不能超过 128 个字符")
        String scientificName,

        @NotBlank(message = "水产大类不能为空")
        @Size(max = 32, message = "水产大类长度不能超过 32 个字符")
        String category,

        @NotBlank(message = "产品规格与包装说明不能为空")
        @Size(min = 1, max = 128, message = "产品规格说明不能超过 128 个字符")
        String specification,

        @NotBlank(message = "来源类型不能为空")
        @Size(max = 32, message = "来源类型长度不能超过 32 个字符")
        String sourceType,

        @Pattern(regexp = "(?i)^kg$", message = "产品基准计量单位仅允许 kg")
        @Size(max = 16, message = "基准计量单位不能超过 16 个字符")
        String baseUnitCode,

        @Size(max = 16, message = "产品状态不能超过 16 个字符")
        String status
) {
    public ProductCreateRequest {
        if (baseUnitCode == null || baseUnitCode.isBlank()) {
            baseUnitCode = "kg";
        } else {
            baseUnitCode = baseUnitCode.trim().toLowerCase();
        }
        if (status == null || status.isBlank()) {
            status = "DRAFT";
        }
    }
}
