package com.example.traceability.identity.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.dto.CurrentUserResponse;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户信息查询控制器。
 * <p>
 * 提供当前登录主体及组织上下文的白名单数据查询。
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {

    /**
     * 获取当前登录用户的白名单概要信息。
     */
    @GetMapping("/me")
    public SuccessEnvelope<CurrentUserResponse> getCurrentUser(
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        if (principal == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED",
                    "需要登录", "访问该资源前必须完成身份认证");
        }
        return SuccessEnvelope.of(principal.toCurrentUserResponse());
    }
}
