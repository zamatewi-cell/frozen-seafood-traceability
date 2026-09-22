package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.ShipmentApplicationService;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.dto.ShipmentBindTransferRequest;
import com.example.traceability.trace.dto.ShipmentCancelRequest;
import com.example.traceability.trace.dto.ShipmentCreateRequest;
import com.example.traceability.trace.dto.ShipmentDispatchRequest;
import com.example.traceability.trace.dto.ShipmentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 冷链运输任务控制器。
 * <p>
 * 提供运输任务创建、装载清单绑定 / 解绑、取消、承运商发运与到达以及三方组织范围查询接口。
 * 写操作严格校验登录主体、CSRF 防护与 Idempotency-Key 防重幂等键（解绑为 DELETE 语义，依赖交接乐观锁版本号）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {

    private static final Set<String> ROLES = Set.of("SENDER", "CARRIER", "RECEIVER");

    private final ShipmentApplicationService shipmentService;

    public ShipmentController(ShipmentApplicationService shipmentService) {
        this.shipmentService = shipmentService;
    }

    /**
     * 发货方创建 PLANNED 运输任务 (POST /api/v1/shipments)。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<ShipmentResponse>> createShipment(
            @Valid @RequestBody ShipmentCreateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        ShipmentResponse response = shipmentService.createShipment(req, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 分页查询当前组织参与的运输任务 (GET /api/v1/shipments)。
     */
    @GetMapping
    public SuccessEnvelope<List<ShipmentResponse>> listShipments(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1")
            @Min(value = 1, message = "页码 page 最小值为 1") int page,
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "分页大小 size 最小值为 1")
            @Max(value = 100, message = "分页大小 size 最大值为 100") int size,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        String normalizedRole = null;
        if (role != null && !role.isBlank()) {
            normalizedRole = role.trim().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(normalizedRole)) {
                throw invalidRequest("role 必须为 SENDER、CARRIER 或 RECEIVER");
            }
        }
        String normalizedStatus = null;
        if (status != null && !status.isBlank()) {
            normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
            String candidate = normalizedStatus;
            if (Arrays.stream(ShipmentStatus.values()).noneMatch(s -> s.name().equals(candidate))) {
                throw invalidRequest("status 必须为 PLANNED、IN_TRANSIT、DELIVERED 或 CANCELLED");
            }
        }
        List<ShipmentResponse> list = shipmentService.listShipments(normalizedRole, normalizedStatus, page, size, principal);
        long total = shipmentService.countShipments(normalizedRole, normalizedStatus, principal);
        return SuccessEnvelope.ofPage(list, new PageMeta(page, size, total));
    }

    /**
     * 查询运输任务详情 (GET /api/v1/shipments/{shipmentId})。
     */
    @GetMapping("/{shipmentId}")
    public SuccessEnvelope<ShipmentResponse> getShipment(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.getShipment(shipmentId, principal));
    }

    /**
     * 发货方绑定 DRAFT 交接 (POST /api/v1/shipments/{shipmentId}/transfers)。
     */
    @PostMapping("/{shipmentId}/transfers")
    public SuccessEnvelope<ShipmentResponse> bindTransfer(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentBindTransferRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.bindTransfer(shipmentId, req, idempotencyKey, principal));
    }

    /**
     * 发货方解绑 DRAFT 交接 (DELETE /api/v1/shipments/{shipmentId}/transfers/{transferId})。
     */
    @DeleteMapping("/{shipmentId}/transfers/{transferId}")
    public SuccessEnvelope<ShipmentResponse> unbindTransfer(
            @PathVariable Long shipmentId,
            @PathVariable Long transferId,
            @RequestParam("expectedTransferVersion")
            @Min(value = 0, message = "交接期望乐观锁版本号不能小于 0") Long expectedTransferVersion,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.unbindTransfer(shipmentId, transferId, expectedTransferVersion, principal));
    }

    /**
     * 承运商确认装载发运 (POST /api/v1/shipments/{shipmentId}/dispatch)。
     */
    @PostMapping("/{shipmentId}/dispatch")
    public SuccessEnvelope<ShipmentResponse> dispatchShipment(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentDispatchRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.dispatchShipment(shipmentId, req, idempotencyKey, principal));
    }

    /**
     * 承运商确认物理到达 (POST /api/v1/shipments/{shipmentId}/arrive)。
     */
    @PostMapping("/{shipmentId}/arrive")
    public SuccessEnvelope<ShipmentResponse> arriveShipment(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentArriveRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.arriveShipment(shipmentId, req, idempotencyKey, principal));
    }

    /**
     * 发货方取消 PLANNED 运输任务 (POST /api/v1/shipments/{shipmentId}/cancel)。
     */
    @PostMapping("/{shipmentId}/cancel")
    public SuccessEnvelope<ShipmentResponse> cancelShipment(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentCancelRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(shipmentService.cancelShipment(shipmentId, req, idempotencyKey, principal));
    }

    private static BusinessException invalidRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
