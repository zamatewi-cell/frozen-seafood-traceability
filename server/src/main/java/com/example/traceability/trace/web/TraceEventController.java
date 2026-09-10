package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TraceEventResponse;
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
 * 追溯事件控制器。
 * <p>
 * 提供批次追溯事件查询、追加记录以及防分叉更正接口。
 * 写操作严格要求同组织 OPERATOR 角色、CSRF 防护以及 Idempotency-Key 防重幂等键。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batches/{batchId}/events")
public class TraceEventController {

    private final TraceEventApplicationService traceEventService;

    public TraceEventController(TraceEventApplicationService traceEventService) {
        this.traceEventService = traceEventService;
    }

    /**
     * 查询指定批次的追溯事件列表。
     *
     * @param batchId   批次 ID
     * @param principal 当前认证主体
     * @return 追溯事件列表封套
     */
    @GetMapping
    public SuccessEnvelope<List<TraceEventResponse>> listEvents(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        List<TraceEventResponse> responses = traceEventService.listEvents(batchId, principal);
        return SuccessEnvelope.of(responses);
    }

    /**
     * 提交记录批次追溯事件。
     *
     * @param batchId        批次 ID
     * @param req            事件创建请求
     * @param idempotencyKey 客户端幂等键
     * @param principal      当前认证主体
     * @return 创建或重放的事件响应封套 (201 Created)
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<TraceEventResponse>> createEvent(
            @PathVariable Long batchId,
            @Valid @RequestBody CreateTraceEventRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TraceEventResponse response = traceEventService.createEvent(batchId, req, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 追加更正批次追溯事件。
     *
     * @param batchId        批次 ID
     * @param eventId        待更正的目标原事件 ID
     * @param req            更正请求
     * @param idempotencyKey 客户端更正幂等键
     * @param principal      当前认证主体
     * @return 新生成的追溯事件响应封套 (201 Created)
     */
    @PostMapping("/{eventId}/corrections")
    public ResponseEntity<SuccessEnvelope<TraceEventResponse>> correctEvent(
            @PathVariable Long batchId,
            @PathVariable Long eventId,
            @Valid @RequestBody CorrectTraceEventRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TraceEventResponse response = traceEventService.correctEvent(batchId, eventId, req, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 明确拒绝任何对已提交追溯事件的就地修改或删除操作。
     * <p>
     * 追溯事件严格遵循 Append-only 不可变设计原则，任何尝试通过 PUT、PATCH 或 DELETE 修改或删除事件的请求
     * 均直接返回 HTTP 405 Method Not Allowed。
     * </p>
     *
     * @param batchId 批次 ID
     * @param eventId 目标事件 ID
     * @return 405 响应
     */
    @RequestMapping(
            value = "/{eventId}",
            method = {org.springframework.web.bind.annotation.RequestMethod.PUT,
                      org.springframework.web.bind.annotation.RequestMethod.PATCH,
                      org.springframework.web.bind.annotation.RequestMethod.DELETE}
    )
    public ResponseEntity<Void> rejectEventMutation(
            @PathVariable Long batchId,
            @PathVariable Long eventId
    ) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
