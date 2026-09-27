package com.example.traceability.quality.application;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase B 质量处置写路径的共享校验（告警处置、检验报告、召回）：认证、质量管理员角色、平台代办限制、幂等键格式与
 * 保留前缀、规范化请求语义哈希。组织与业务状态校验由各服务在持有业务锁后执行。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
final class QualityWriteGuards {

    static final String ROLE_QUALITY_MANAGER = "QUALITY_MANAGER";
    static final String RESERVED_SYSTEM_KEY_PREFIX = "SYS:";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

    private QualityWriteGuards() {
    }

    static void requireAuthenticated(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
    }

    static boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    /**
     * 写权限：未认证 401；平台 / 系统管理员不可代办 403 ADMIN_RESTRICTED；必须是 QUALITY_MANAGER（403 ACCESS_DENIED）。
     */
    static void checkQualityManager(TraceSecurityPrincipal principal, String label) {
        requireAuthenticated(principal);
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办企业质量处置");
        }
        if (!principal.getRoles().contains(ROLE_QUALITY_MANAGER)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                    label + "仅限质量管理员（QUALITY_MANAGER）执行");
        }
    }

    static String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw badRequest("Idempotency-Key 请求头必填，长度 16 到 128 个字符，只能包含字母、数字与 . _ : -");
        }
        String clean = idempotencyKey.trim();
        if (clean.toUpperCase(Locale.ROOT).startsWith(RESERVED_SYSTEM_KEY_PREFIX)) {
            throw badRequest("Idempotency-Key 不能以保留前缀 SYS: 开头（系统生成键专用）");
        }
        return clean;
    }

    /** 以单元分隔符拼接规范化字段后的 SHA-256（null 记为空串）。 */
    static String hash(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                canonical.append('\u001F');
            }
            canonical.append(parts[i] == null ? "" : parts[i]);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    /** 去除首尾空白；空白视为未填写；超长 400。 */
    static String optionalText(String raw, int max, String label) {
        String text = raw == null ? null : raw.trim();
        if (text != null && text.isEmpty()) {
            return null;
        }
        if (text != null && text.length() > max) {
            throw badRequest(label + " 不能超过 " + max + " 个字符");
        }
        return text;
    }

    /** 必填文本：去除首尾空白后 1..max 个字符。 */
    static String requiredText(String raw, int max, String label) {
        String text = optionalText(raw, max, label);
        if (text == null) {
            throw badRequest(label + " 不能为空");
        }
        return text;
    }

    static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
