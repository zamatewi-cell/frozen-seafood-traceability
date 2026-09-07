package com.example.traceability.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户登录请求体。
 *
 * @param username 用户名
 * @param password 密码
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 64, message = "用户名长度必须在 3 到 64 个字符之间")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 个字符之间")
        String password
) {
}
