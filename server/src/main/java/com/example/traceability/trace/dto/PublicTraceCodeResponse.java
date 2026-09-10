package com.example.traceability.trace.dto;

import com.example.traceability.trace.domain.PublicTraceCode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 企业端对外公开追溯码响应 DTO。
 * <p>
 * 严格白名单数据投影，绝不暴露 {@code token_hash}、统一幂等台账或
 * {@code is_deleted} 等内部字段。
 * </p>
 *
 * @param id          公开追溯码内部 ID
 * @param batchId     绑定的批次内部 ID
 * @param publicId    26 位 Base32 消费者公开追溯编码
 * @param status      追溯码状态 (ACTIVE/DISABLED/RECALLED)
 * @param activatedAt 激活时间 (UTC ISO 8601)
 * @param disabledAt  停用时间 (UTC ISO 8601，可为 null)
 * @param createdAt   创建时间 (UTC ISO 8601)
 * @param updatedAt   更新时间 (UTC ISO 8601)
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record PublicTraceCodeResponse(
        Long id,
        Long batchId,
        String publicId,
        String status,
        String activatedAt,
        String disabledAt,
        String createdAt,
        String updatedAt
) {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public static PublicTraceCodeResponse fromEntity(PublicTraceCode code) {
        if (code == null) {
            return null;
        }
        return new PublicTraceCodeResponse(
                code.getId(),
                code.getBatchId(),
                code.getPublicId(),
                code.getStatus(),
                formatUtc(code.getActivatedAt()),
                formatUtc(code.getDisabledAt()),
                formatUtc(code.getCreatedAt()),
                formatUtc(code.getUpdatedAt())
        );
    }

    private static String formatUtc(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atOffset(ZoneOffset.UTC).format(ISO_FORMATTER);
    }
}
