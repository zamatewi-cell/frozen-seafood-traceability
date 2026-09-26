package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.AlertApplicationService;
import com.example.traceability.quality.dto.AlertAcknowledgeRequest;
import com.example.traceability.quality.dto.AlertResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警接口（Phase B PB3）：查询与确认。告警只由系统在温度登记时创建，不提供创建、修改或删除接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertApplicationService alertService;

    public AlertController(AlertApplicationService alertService) {
        this.alertService = alertService;
    }

    /**
     * 可见告警列表（按主键倒序），可按状态与运输任务过滤。
     */
    @GetMapping
    public SuccessEnvelope<List<AlertResponse>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "shipmentId", required = false) Long shipmentId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(alertService.listAlerts(status, shipmentId, principal));
    }

    /**
     * 告警详情（受影响批次与处置历史）。
     */
    @GetMapping("/{alertId}")
    public SuccessEnvelope<AlertResponse> get(
            @PathVariable Long alertId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(alertService.getAlert(alertId, principal));
    }

    /**
     * 确认告警 OPEN → ACKNOWLEDGED（同一幂等键同一语义重放，返回当前告警详情）。
     */
    @PostMapping("/{alertId}/acknowledge")
    public SuccessEnvelope<AlertResponse> acknowledge(
            @PathVariable Long alertId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AlertAcknowledgeRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(alertService.acknowledge(alertId, request, idempotencyKey, principal));
    }
}
