package com.example.traceability.identity.dto;

/**
 * CSRF 凭据响应体。
 *
 * @param headerName    请求头名称 (如 X-CSRF-TOKEN)
 * @param parameterName 请求参数名称 (如 _csrf)
 * @param token         Token 凭据值
 */
public record CsrfTokenResponse(
        String headerName,
        String parameterName,
        String token
) {
}
