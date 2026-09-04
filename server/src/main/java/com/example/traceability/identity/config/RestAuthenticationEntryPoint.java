package com.example.traceability.identity.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** 未认证访问受保护资源时返回统一 401 Problem。 */
final class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityProblemWriter problemWriter;

    RestAuthenticationEntryPoint(SecurityProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        problemWriter.write(request, response, HttpStatus.UNAUTHORIZED.value(), "AUTH_REQUIRED",
                "需要登录", "访问该资源前必须完成身份认证");
    }
}
