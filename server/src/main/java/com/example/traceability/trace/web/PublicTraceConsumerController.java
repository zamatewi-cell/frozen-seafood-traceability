package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费者匿名公开追溯查询控制器。
 * <p>
 * 提供匿名无会话、免 CSRF 的公开追溯数据投影查验接口。
 * 仅暴露严格白名单属性，未知或停用码统一返回 404 PUBLIC_TRACE_NOT_FOUND。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/public/v1/public/traces")
public class PublicTraceConsumerController {

    private final PublicTraceApplicationService publicTraceService;

    public PublicTraceConsumerController(PublicTraceApplicationService publicTraceService) {
        this.publicTraceService = publicTraceService;
    }

    /**
     * 根据公开追溯标识查询批次公开追溯投影（完全匿名公开访问）。
     *
     * @param publicTraceId 消费者公开追溯编码 (26 位 Base32)
     * @return 白名单脱敏公开追溯投影
     */
    @GetMapping("/{publicTraceId}")
    public SuccessEnvelope<PublicTraceProjectionResponse> getPublicTrace(
            @PathVariable String publicTraceId
    ) {
        PublicTraceProjectionResponse response = publicTraceService.getPublicTrace(publicTraceId);
        return SuccessEnvelope.of(response);
    }
}
