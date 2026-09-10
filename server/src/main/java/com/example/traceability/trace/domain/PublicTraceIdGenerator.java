package com.example.traceability.trace.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 消费者公开追溯标识码与内部令牌哈希生成器。
 * <p>
 * 严格遵循 RFC 4648 Base32 标准大写字母表（{@code A-Z, 2-7}），
 * 完全基于密码学安全随机数（{@link SecureRandom}）生成 26 位不可预测随机字符序列，
 * 绝不包含或派生自任何内部 ID、自增主键或时间戳信息。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class PublicTraceIdGenerator {

    /**
     * 标准 RFC 4648 Base32 编码表（32 个字符：A-Z, 2-7）。
     */
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PublicTraceIdGenerator() {
    }

    /**
     * 生成 26 位大写 RFC 4648 Base32 消费者公开追溯标识。
     * <p>
     * 使用 16 字节（128-bit）加密安全随机字节，经 5-bit 流水线提取映射为 26 位大写字符（无填充 padding）。
     * </p>
     *
     * @return 26 位 Base32 大写字符串
     */
    public static String generatePublicId() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);

        char[] out = new char[26];
        int charIdx = 0;
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out[charIdx++] = BASE32_ALPHABET[(buffer >> (bitsLeft - 5)) & 0x1F];
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out[charIdx++] = BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1F];
        }

        return new String(out);
    }

    /**
     * 计算 public_id 的 SHA-256 哈希指纹（仅作为内部唯一索引与指纹存证，绝不对外暴露）。
     *
     * @param publicId 公开追溯标识字符串
     * @return 64 位十六进制小写哈希字符串
     */
    public static String computeTokenHash(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("publicId 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法在当前运行时不可用", e);
        }
    }
}
