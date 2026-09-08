package com.example.traceability.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 批次草稿提交激活请求 DTO。
 * <p>
 * 仅需携带当前乐观锁版本号 {@code version}，防止并发更新下的错误流转。
 * 组织、操作人和目标状态由服务端安全上下文与业务规则唯一推导。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchSubmitRequest(
        @NotNull(message = "乐观锁版本号 version 不能为空")
        @Min(value = 0, message = "乐观锁版本号 version 必须非负")
        Long version
) {
}
