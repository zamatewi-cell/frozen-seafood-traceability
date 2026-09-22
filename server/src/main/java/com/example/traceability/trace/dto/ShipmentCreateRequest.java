package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发货方创建冷链运输任务 (PLANNED) 请求。
 * <p>
 * 接收组织由目的场所所属组织确定，客户端不得单独指定；发送组织为当前登录主体所属组织。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentCreateRequest(
        @NotNull(message = "承运组织 carrierOrgId 不能为空")
        Long carrierOrgId,

        @NotNull(message = "启运场所 originSiteId 不能为空")
        Long originSiteId,

        @NotNull(message = "目的场所 destinationSiteId 不能为空")
        Long destinationSiteId,

        @NotBlank(message = "车牌号或冷藏集装箱编号不能为空")
        @Size(max = 64, message = "车牌号或冷藏集装箱编号长度不得超过 64 个字符")
        String vehicleOrContainerNo
) {
}
