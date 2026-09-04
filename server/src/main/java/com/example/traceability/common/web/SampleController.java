package com.example.traceability.common.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 示例端点控制器 (Sample Controller)。
 * <p>
 * 提供最小受控端点，用于支撑 Phase 2 自动化测试套件对以下特性的验证：
 * <ul>
 *   <li>{@code GET /api/v1/samples/ping}：验证 200 统一成功响应包装器 (SuccessEnvelope)</li>
 *   <li>{@code POST /api/v1/samples/validate}：验证 Jakarta Validation 与 400 Problem fieldErrors 输出</li>
 *   <li>{@code GET /api/v1/samples/not-found}：验证受控 404 ResourceNotFoundException 转换为 RFC 9457 Problem 响应</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/samples")
public class SampleController {

    /**
     * 联通性与统一成功响应测试端点。
     *
     * @return 统一成功响应包装器（包含 "pong" 与元数据）
     */
    @GetMapping("/ping")
    public SuccessEnvelope<String> ping() {
        return SuccessEnvelope.of("pong");
    }

    /**
     * 参数校验测试端点。
     *
     * @param dto 待校验的样本传输对象
     * @return 校验通过后的回显响应
     */
    @PostMapping("/validate")
    public SuccessEnvelope<SampleDto> validate(@Valid @RequestBody SampleDto dto) {
        return SuccessEnvelope.of(dto);
    }

    /**
     * 受控 404 异常触发测试端点。
     *
     * @return 永远不正常返回，直接抛出受控异常
     */
    @GetMapping("/not-found")
    public SuccessEnvelope<Void> notFound() {
        throw new ResourceNotFoundException("测试资源不存在: sample-404");
    }
}
