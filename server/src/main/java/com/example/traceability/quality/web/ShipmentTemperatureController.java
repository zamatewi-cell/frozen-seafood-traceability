package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.ShipmentTemperatureService;
import com.example.traceability.quality.dto.ShipmentTemperatureRecordRequest;
import com.example.traceability.quality.dto.TemperatureRecordResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Shipment 在途温度记录控制器（Phase B PB2：单点登记与单点判定）。
 * <p>
 * 写接口仅限运输任务指定承运组织的操作员（OPERATOR），运输任务必须处于 IN_TRANSIT，强制校验 CSRF Token 与
 * Idempotency-Key 请求头；查询接口开放给运输任务发送方、承运方、接收方（任意角色）与平台只读角色。
 * 温度记录从不出现在匿名公开追溯接口中。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/shipments/{shipmentId}/temperature-records")
public class ShipmentTemperatureController {

    private final ShipmentTemperatureService temperatureService;

    public ShipmentTemperatureController(ShipmentTemperatureService temperatureService) {
        this.temperatureService = temperatureService;
    }

    /**
     * 登记一条在途温度测量（同一幂等键同一语义重放原记录，返回 201）。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<TemperatureRecordResponse>> record(
            @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ShipmentTemperatureRecordRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TemperatureRecordResponse response = temperatureService.record(shipmentId, request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 查询运输任务的全部在途温度记录（按测量时间、主键稳定排序）。
     */
    @GetMapping
    public SuccessEnvelope<List<TemperatureRecordResponse>> list(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(temperatureService.listRecords(shipmentId, principal));
    }
}
