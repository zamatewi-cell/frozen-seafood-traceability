package com.example.traceability.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路请求追踪拦截器 (RequestIdFilter)。
 * <p>
 * 继承自 {@link OncePerRequestFilter}，核心职责包括：
 * <ul>
 *   <li>自动从 HTTP 请求头提取 {@code X-Request-Id}；若未提供或为空则自动生成标准 UUID</li>
 *   <li>在 HTTP 响应头中强制回显 {@code X-Request-Id}，保障端到端可追踪性</li>
 *   <li>将请求追踪 ID 绑定至 SLF4J MDC（固定键名为 {@code requestId}）与请求上下文属性中</li>
 *   <li>在 {@code finally} 块中严格执行 {@code MDC.remove("requestId")}，防止线程池复用产生 MDC 污染</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /**
     * HTTP 请求头与响应头使用的请求追踪 ID 键名。
     */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /**
     * SLF4J MDC 绑定的固定键名。
     */
    public static final String MDC_KEY = "requestId";

    /**
     * HttpServletRequest 属性绑定的键名。
     */
    public static final String REQUEST_ATTR_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);

        // 1. 绑定至 MDC 与请求属性
        MDC.put(MDC_KEY, requestId);
        request.setAttribute(REQUEST_ATTR_KEY, requestId);

        // 2. 强制回显至响应头
        response.setHeader(HEADER_REQUEST_ID, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 3. 必须在 finally 中清理，防止 MDC 跨请求污染
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 解析请求追踪 ID。优先从请求头提取，若无则自动生成 UUID。
     *
     * @param request HTTP 请求
     * @return 最终确定的追踪 ID
     */
    private String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(HEADER_REQUEST_ID);
        if (headerValue != null) {
            String candidate = headerValue.trim();
            if (SAFE_REQUEST_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString();
    }
}
