package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 创建企业间整批交接草稿请求。
 * <p>
 * 严禁由客户端指定发货数量与单位，服务端强制从内部实体批次进行快照锁定。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferCreateRequest(
        @NotNull(message = "交接批次ID不能为空")
        Long batchId,

        @NotNull(message = "接收方企业ID不能为空")
        Long receiverOrgId
) {
}
