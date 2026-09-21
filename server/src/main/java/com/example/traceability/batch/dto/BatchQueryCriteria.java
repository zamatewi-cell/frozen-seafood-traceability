package com.example.traceability.batch.dto;

/**
 * 批次分页查询过滤条件。
 *
 * @param traceBatchNo    服务端追溯批次号（可选）
 * @param externalBatchNo 企业外部业务批次号（可选）
 * @param flowStatus      批次流转状态代码（可选，支持 DRAFT/ACTIVE/CLOSED）
 * @param riskStatus      批次风险状态代码（可选，支持 NORMAL/FROZEN/RECALLED）
 * @param page            分页页码（从 1 开始）
 * @param size            每页大小（1~100）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchQueryCriteria(
        String traceBatchNo,
        String externalBatchNo,
        String flowStatus,
        String riskStatus,
        int page,
        int size
) {
}
