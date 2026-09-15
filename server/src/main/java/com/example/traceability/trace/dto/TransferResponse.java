package com.example.traceability.trace.dto;

import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 企业间整批交接响应 DTO。
 * <p>
 * 严格白名单结构，屏蔽 isDeleted、idempotencyKey、requestHash、openBatchId 等底层持久化细节。
 * 时间字段统一以 UTC 带时区 ISO 8601 输出。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferResponse(
        Long id,
        String transferNo,
        Long batchId,
        Long senderOrgId,
        Long receiverOrgId,
        BigDecimal quantity,
        String unitCode,
        TransferStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime shippedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime submittedRecordedAt,
        Long submittedBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime receivedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime decisionRecordedAt,
        Long decidedBy,
        BigDecimal receivedQuantity,
        String differenceReason,
        String rejectionReason,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime updatedAt
) {

    public static TransferResponse fromEntity(Transfer t) {
        if (t == null) {
            return null;
        }
        return new TransferResponse(
                t.getId(),
                t.getTransferNo(),
                t.getBatchId(),
                t.getSenderOrgId(),
                t.getReceiverOrgId(),
                t.getQuantity(),
                t.getUnitCode(),
                t.getStatus(),
                t.getShippedAt() != null ? t.getShippedAt().atOffset(ZoneOffset.UTC) : null,
                t.getSubmittedRecordedAt() != null ? t.getSubmittedRecordedAt().atOffset(ZoneOffset.UTC) : null,
                t.getSubmittedBy(),
                t.getReceivedAt() != null ? t.getReceivedAt().atOffset(ZoneOffset.UTC) : null,
                t.getDecisionRecordedAt() != null ? t.getDecisionRecordedAt().atOffset(ZoneOffset.UTC) : null,
                t.getDecidedBy(),
                t.getReceivedQuantity(),
                t.getDifferenceReason(),
                t.getRejectionReason(),
                t.getVersion(),
                t.getCreatedAt() != null ? t.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                t.getUpdatedAt() != null ? t.getUpdatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
