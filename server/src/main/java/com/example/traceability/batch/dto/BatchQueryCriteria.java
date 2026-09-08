package com.example.traceability.batch.dto;

/**
 * 批次分页查询过滤条件。
 *
 * @param status 批次状态代码（可选，支持 DRAFT/ACTIVE/FROZEN/RECALLED/CLOSED）
 * @param page   分页页码（从 1 开始）
 * @param size   每页大小（1~100）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchQueryCriteria(
        String status,
        int page,
        int size
) {
}
