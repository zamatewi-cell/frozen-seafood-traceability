package com.example.traceability.batch.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * 追溯批次号独立生成器。
 * <p>
 * 使用 {@link SecureRandom} 生成 128-bit（16 字节）随机熵，
 * 按照 RFC 4648 Base32 无 padding 规范编码为 26 位大写字符，
 * 并拼接业务前缀 {@code TB-} 输出全局唯一的追溯批次号（格式：{@code TB-XXXXXXXXXXXXXXXXXXXXXXXXXX}，总长 29 字符）。
 * </p>
 * <p>
 * 本类完全线程安全，支持重载输入确定性字节以满足单元测试需求，不依赖 UUID 文本。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Component
public class TraceBatchNoGenerator {

    private static final String PREFIX = "TB-";
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int ENTROPY_BYTES = 16; // 128 bits

    private final SecureRandom secureRandom;

    public TraceBatchNoGenerator() {
        this.secureRandom = new SecureRandom();
    }

    public TraceBatchNoGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom 不能为空");
    }

    /**
     * 生成一个全局唯一的追溯批次号。
     *
     * @return 格式为 TB- 加 26 位大写 Base32 字符的追溯批次号
     */
    public String generate() {
        byte[] randomBytes = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(randomBytes);
        return formatTraceBatchNo(randomBytes);
    }

    /**
     * 将 16 字节（128-bit）二进制数据按照 RFC 4648 Base32（无 padding）编码，并拼接 TB- 前缀。
     *
     * @param bytes 16 字节数组
     * @return 格式为 TB- 加 26 位 Base32 字符的字符串
     */
    public String formatTraceBatchNo(byte[] bytes) {
        if (bytes == null || bytes.length != ENTROPY_BYTES) {
            throw new IllegalArgumentException("追溯批次号随机熵必须为恰好 16 字节 (128-bit)");
        }

        char[] chars = new char[26];
        // 128 bits: 遍历 26 次，每次提取 5 bits
        for (int i = 0; i < 26; i++) {
            int bitIndex = i * 5;
            int byteIndex = bitIndex / 8;
            int bitOffset = bitIndex % 8;

            int val;
            if (bitOffset <= 3) {
                // 5 bit 完全在当前字节内，或者恰好跨越
                val = ((bytes[byteIndex] & 0xFF) >> (3 - bitOffset)) & 0x1F;
            } else {
                // 跨越两个字节
                int firstPart = (bytes[byteIndex] & 0xFF) << (bitOffset - 3);
                int secondPart = (byteIndex + 1 < bytes.length) ? ((bytes[byteIndex + 1] & 0xFF) >> (11 - bitOffset)) : 0;
                val = (firstPart | secondPart) & 0x1F;
            }
            chars[i] = BASE32_ALPHABET[val];
        }

        return PREFIX + new String(chars);
    }
}
