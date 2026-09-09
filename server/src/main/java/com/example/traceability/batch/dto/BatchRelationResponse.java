package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.BatchRelation;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 批次谱系图谱边响应 DTO。
 * <p>
 * 输出父子批次有向关系及关系类型。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchRelationResponse(
        Long id,
        Long operationId,
        Long parentBatchId,
        Long childBatchId,
        String relationType,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt
) {

    public static BatchRelationResponse fromEntity(BatchRelation relation) {
        if (relation == null) {
            return null;
        }
        return new BatchRelationResponse(
                relation.getId(),
                relation.getOperationId(),
                relation.getParentBatchId(),
                relation.getChildBatchId(),
                relation.getRelationType(),
                relation.getCreatedAt() != null ? relation.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
