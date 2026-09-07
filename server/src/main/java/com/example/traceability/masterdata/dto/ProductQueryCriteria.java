package com.example.traceability.masterdata.dto;

/**
 * 产品主数据分页查询条件 DTO。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record ProductQueryCriteria(
        String keyword,
        String category,
        String sourceType,
        String status,
        int page,
        int size
) {
}
