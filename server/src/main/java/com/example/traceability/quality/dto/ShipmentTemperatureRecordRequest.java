package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登记一条 Shipment 在途温度记录的请求 DTO（Phase B PB2）。
 * <p>
 * {@code measuredAt} 为测量业务时间（带时区偏移），服务端统一规范化为 UTC 微秒精度；{@code temperature} 为摄氏度，
 * 最多两位小数；{@code dataSource} 只接受 MANUAL / SIMULATED；{@code deviceNo} 可选。
 * 环节（TRANSPORT）、温标（CELSIUS）、判定结果、登记组织与登记时间全部由服务端决定：任何未声明字段
 * （例如 evaluation、stageCode、orgId、recordedAt）都会收集到 {@link #unknownFields()}，由应用服务以 400 明确拒绝。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record ShipmentTemperatureRecordRequest(
        @NotNull(message = "测量时间 measuredAt 不能为空")
        OffsetDateTime measuredAt,

        @NotNull(message = "温度 temperature 不能为空")
        BigDecimal temperature,

        @NotBlank(message = "数据来源 dataSource 不能为空")
        String dataSource,

        String deviceNo,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public ShipmentTemperatureRecordRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public ShipmentTemperatureRecordRequest(OffsetDateTime measuredAt, BigDecimal temperature, String dataSource, String deviceNo) {
        this(measuredAt, temperature, dataSource, deviceNo, Map.of());
    }
}
