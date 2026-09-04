package com.example.traceability.identity.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 已进入安全链但权限或 CSRF 校验失败时返回统一 403 Problem。 */
final class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityProblemWriter problemWriter;

    RestAccessDeniedHandler(SecurityProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        problemWriter.write(request, response, HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "访问被拒绝", "当前请求没有执行该操作所需的权限或有效 CSRF 凭据");
    }
}
