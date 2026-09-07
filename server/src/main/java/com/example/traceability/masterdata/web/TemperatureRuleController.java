package com.example.traceability.masterdata.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.application.TemperatureRuleApplicationService;
import com.example.traceability.masterdata.dto.TemperatureRuleCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分阶段温控基准规则控制器。
 * <p>
 * 提供产品温控方案查询、草稿规则创建及不可变规则发布。
 * 读操作需已认证用户，写与发布操作必须具有 PLATFORM 权限作用域并校验 CSRF。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class TemperatureRuleController {

    private final TemperatureRuleApplicationService ruleService;

    public TemperatureRuleController(TemperatureRuleApplicationService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * 查询指定海产品的全部温控规则版本及其环节明细。
     *
     * @param productId 产品ID
     * @return 温控规则方案列表
     */
    @GetMapping("/products/{productId}/temperature-rules")
    public SuccessEnvelope<List<TemperatureRuleResponse>> listRules(@PathVariable Long productId) {
        return ruleService.listRulesByProductId(productId);
    }

    /**
     * 为指定海产品创建温控基准规则草稿（仅限全平台管理角色）。
     *
     * @param productId 产品ID
     * @param request   规则创建参数
     * @param principal 当前认证主体
     * @return 创建成功后的规则详情
     */
    @PostMapping("/products/{productId}/temperature-rules")
    public ResponseEntity<SuccessEnvelope<TemperatureRuleResponse>> createRule(
            @PathVariable Long productId,
            @Valid @RequestBody TemperatureRuleCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TemperatureRuleResponse response = ruleService.createDraftRule(productId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 发布指定温控基准规则（仅限全平台管理角色）。
     *
     * @param ruleId    规则ID
     * @param principal 当前认证主体
     * @return 发布成功后的规则详情
     */
    @PostMapping("/temperature-rules/{ruleId}/publish")
    public SuccessEnvelope<TemperatureRuleResponse> publishRule(
            @PathVariable Long ruleId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(ruleService.publishRule(ruleId, principal));
    }
}
