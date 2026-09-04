package com.example.traceability.common.envelope;

/**
 * 分页元数据对象 (Page Metadata)。
 * <p>
 * 遵循 OpenAPI 3.1 契约约定，作为可选节点嵌套在 {@link ResponseMeta#page()} 中。
 * 注意：根据项目 API 设计规范，页码 {@code number} 严格从 1 开始计数。
 * </p>
 *
 * @param number        当前页码（从 1 开始计数，minimum: 1）
 * @param size          每页记录数条数（minimum: 1, maximum: 100）
 * @param totalElements 符合条件的总记录数（minimum: 0）
 * @param totalPages    计算出的总页数（minimum: 0）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record PageMeta(
    int number,
    int size,
    long totalElements,
    int totalPages
) {

    public PageMeta {
        if (number < 1) {
            throw new IllegalArgumentException("页码必须从 1 开始");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("每页记录数必须在 1 到 100 之间");
        }
        if (totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("总记录数与总页数不能为负数");
        }
    }

    /**
     * 便捷构造器：根据当前页码、每页记录数与总记录数自动推导计算总页数。
     *
     * @param number        当前页码（从 1 开始）
     * @param size          每页条数
     * @param totalElements 符合条件的总记录数
     */
    public PageMeta(int number, int size, long totalElements) {
        this(
            number,
            size,
            totalElements,
            calculateTotalPages(size, totalElements)
        );
    }

    private static int calculateTotalPages(int size, long totalElements) {
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("每页记录数必须在 1 到 100 之间");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("总记录数不能为负数");
        }
        return Math.toIntExact(Math.ceilDiv(totalElements, size));
    }
}
