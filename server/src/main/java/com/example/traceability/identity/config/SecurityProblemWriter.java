package com.example.traceability.identity.config;

import com.example.traceability.common.exception.Problem;
import com.example.traceability.common.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 将 Spring Security 过滤器链中的 401/403 写成统一 RFC 9457 响应。 */
final class SecurityProblemWriter {

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    private final ObjectMapper objectMapper;

    SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request,
               HttpServletResponse response,
               int status,
               String code,
               String title,
               String detail) throws IOException {
        String requestId = resolveRequestId(request);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(PROBLEM_JSON.toString());
        response.setHeader(RequestIdFilter.HEADER_REQUEST_ID, requestId);

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/" + code.toLowerCase().replace('_', '-')))
                .title(title)
                .status(status)
                .code(code)
                .detail(detail)
                .instance(request.getRequestURI())
                .requestId(requestId)
                .fieldErrors(List.of())
                .build();
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTR_KEY);
        return value instanceof String requestId && !requestId.isBlank()
                ? requestId
                : UUID.randomUUID().toString();
    }
}
