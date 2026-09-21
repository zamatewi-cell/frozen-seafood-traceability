package com.example.traceability.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceBatchNoGenerator 追溯批次号生成器单元测试")
class TraceBatchNoGeneratorTest {

    private static final Pattern TRACE_BATCH_NO_PATTERN = Pattern.compile("^TB-[A-Z2-7]{26}$");

    private final TraceBatchNoGenerator generator = new TraceBatchNoGenerator();

    @Test
    @DisplayName("生成的追溯批次号符合 TB- 前缀 + 26位大写 Base32 字符规范（总长 29，无 padding）")
    void generate_FormatCompliance() {
        String batchNo = generator.generate();

        assertThat(batchNo).isNotNull();
        assertThat(batchNo).startsWith("TB-");
        assertThat(batchNo).hasSize(29);
        assertThat(batchNo).matches(TRACE_BATCH_NO_PATTERN);
        assertThat(batchNo).doesNotContain("=");
    }

    @Test
    @DisplayName("大量生成追溯批次号（10,000 次）无碰撞且均符合格式")
    void generate_BulkGenerationNoCollision() {
        int count = 10_000;
        Set<String> generatedNos = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            String batchNo = generator.generate();
            assertThat(batchNo).matches(TRACE_BATCH_NO_PATTERN);
            generatedNos.add(batchNo);
        }

        assertThat(generatedNos).hasSize(count);
    }

    @Test
    @DisplayName("多线程高并发生成保证线程安全且无重复")
    void generate_ConcurrentThreadSafety() throws InterruptedException {
        int threadCount = 16;
        int perThreadCount = 1_000;
        int totalExpected = threadCount * perThreadCount;

        Set<String> generatedSet = ConcurrentHashMap.newKeySet(totalExpected);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < perThreadCount; j++) {
                        String no = generator.generate();
                        generatedSet.add(no);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertThat(generatedSet).hasSize(totalExpected);
    }

    @Test
    @DisplayName("可测试性与 RFC4648 Base32 无 padding 算法确定性验证")
    void generateFromBytes_DeterministicRFC4648Base32() {
        // 全 0 的 16 字节 (128-bit)
        byte[] allZeros = new byte[16];
        // 128 bit 全部为 0：128 / 5 = 25 余 3 bits。全部 0 映射为 26 个 'A'
        String encodedZeros = generator.formatTraceBatchNo(allZeros);
        assertThat(encodedZeros).isEqualTo("TB-AAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(encodedZeros).hasSize(29);

        // 全 0xFF 的 16 字节
        byte[] allOnes = new byte[16];
        for (int i = 0; i < 16; i++) {
            allOnes[i] = (byte) 0xFF;
        }
        // 128 bit 全部为 1：5 bit 都是 31 (0x1F) -> 字符 '7'。最后一个字符补了 2 个 0 (0x1C -> 28 -> '4')
        String encodedOnes = generator.formatTraceBatchNo(allOnes);
        assertThat(encodedOnes).startsWith("TB-7777777777777777777777777");
        assertThat(encodedOnes).hasSize(29);
        assertThat(encodedOnes).matches(TRACE_BATCH_NO_PATTERN);
    }
}
