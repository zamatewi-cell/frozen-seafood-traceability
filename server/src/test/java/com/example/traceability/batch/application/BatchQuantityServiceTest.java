package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.sale.mapper.SaleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派生剩余量唯一计算入口的兼容性回归测试。
 * <p>
 * 每个场景同时经过单批次 {@link BatchQuantityService#remainingOf(Batch)} 与批量
 * {@link BatchQuantityService#remainingOf(java.util.Collection)} 两条路径，断言结果一致；
 * 保留 Slice 5 之前的语义：CLOSED 批次剩余量为 0、数学上为负的历史数据截断为 0、声明数量缺失时为 null。
 * 数值一律以 {@code BigDecimal.compareTo} 比较，不依赖标度。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchQuantityService：单批次与批量派生剩余量语义一致且保持历史兼容")
class BatchQuantityServiceTest {

    @Mock
    private BatchOperationItemMapper itemMapper;

    @Mock
    private SaleMapper saleMapper;

    private BatchQuantityService service;

    @BeforeEach
    void setUp() {
        service = new BatchQuantityService(itemMapper, saleMapper);
    }

    private static Batch batch(long id, String quantity, String flowStatus) {
        Batch b = new Batch();
        b.setId(id);
        b.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        b.setFlowStatus(flowStatus);
        b.setRiskStatus("NORMAL");
        return b;
    }

    private static Map<String, Object> row(Object batchId, Object total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("total", total);
        return m;
    }

    /**
     * 同一批次在单批次与批量两条路径上分别计算，断言两者都等于期望值（期望为 null 时两者均为 null）。
     */
    private void assertBothPaths(Batch b, String consumed, String sold, String expected) {
        when(itemMapper.sumSubmittedInputQuantityByBatchId(b.getId())).thenReturn(consumed == null ? null : new BigDecimal(consumed));
        when(saleMapper.sumSubmittedQuantityByBatchId(b.getId())).thenReturn(sold == null ? null : new BigDecimal(sold));
        when(itemMapper.sumSubmittedInputQuantityByBatchIds(List.of(b.getId())))
                .thenReturn(consumed == null ? List.of() : List.of(row(BigInteger.valueOf(b.getId()), new BigDecimal(consumed))));
        when(saleMapper.sumSubmittedQuantityByBatchIds(List.of(b.getId())))
                .thenReturn(sold == null ? List.of() : List.of(row(b.getId(), new BigDecimal(sold))));

        BigDecimal single = service.remainingOf(b);
        BigDecimal bulk = service.remainingOf(List.of(b)).get(b.getId());
        if (expected == null) {
            assertThat(single).isNull();
            assertThat(bulk).isNull();
        } else {
            assertThat(single).isEqualByComparingTo(expected);
            assertThat(bulk).isEqualByComparingTo(expected);
            assertThat(single.signum()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("ACTIVE 600，无消耗无销售 → 600")
    void active_noFacts() {
        assertBothPaths(batch(1L, "600.000", "ACTIVE"), null, null, "600");
    }

    @Test
    @DisplayName("ACTIVE 600，已售 200 → 400")
    void active_partialSale() {
        assertBothPaths(batch(2L, "600.000", "ACTIVE"), null, "200.000", "400");
    }

    @Test
    @DisplayName("ACTIVE 600，已售 600 → 0")
    void active_soldOutBeforeClose() {
        assertBothPaths(batch(3L, "600.000", "ACTIVE"), null, "600", "0");
    }

    @Test
    @DisplayName("CLOSED 历史批次，没有任何匹配的 INPUT / Sale 事实 → 0（保持 Slice 5 前语义）")
    void closed_withoutFacts_isZero() {
        assertBothPaths(batch(4L, "12.345", "CLOSED"), null, null, "0");
    }

    @Test
    @DisplayName("CLOSED 批次即使事实加总不为声明数量 → 0")
    void closed_withPartialFacts_isZero() {
        assertBothPaths(batch(5L, "1000.000", "CLOSED"), "400.000", null, "0");
    }

    @Test
    @DisplayName("历史超额消耗（INPUT 700 > 声明 600）→ 截断为 0，绝不为负")
    void overConsumed_clampedToZero() {
        assertBothPaths(batch(6L, "600.000", "ACTIVE"), "700.000", null, "0");
    }

    @Test
    @DisplayName("历史数据 INPUT 500 + 已售 200 > 声明 600 → 截断为 0，绝不为负")
    void overConsumedAndSold_clampedToZero() {
        assertBothPaths(batch(7L, "600.000", "ACTIVE"), "500", "200.000", "0");
    }

    @Test
    @DisplayName("历史部分 INPUT（1000 - 400）→ 600")
    void legacyPartialInput() {
        assertBothPaths(batch(8L, "1000.000", "ACTIVE"), "400.000", null, "600");
    }

    @Test
    @DisplayName("批次操作产出的 OUTPUT 批次（B3 声明 360）：未消耗未销售 → 360；已售 100 → 260；DRAFT 产出草稿 → 声明数量")
    void transformedOutput() {
        Batch output = batch(9L, "360.000", "ACTIVE");
        output.setProducedByOperationId(77L);
        assertBothPaths(output, null, null, "360");

        Batch sold = batch(10L, "360.000", "ACTIVE");
        sold.setProducedByOperationId(77L);
        sold.setFirstSaleId(501L);
        assertBothPaths(sold, null, "100", "260");

        Batch draft = batch(11L, "600.000", "DRAFT");
        draft.setProducedByOperationId(78L);
        assertBothPaths(draft, null, null, "600");
    }

    @Test
    @DisplayName("OUTPUT 批次随后被全量消耗（CLOSED + consumedBy）→ 0")
    void transformedOutput_laterConsumed() {
        Batch b1 = batch(12L, "960.000", "CLOSED");
        b1.setProducedByOperationId(77L);
        b1.setConsumedByOperationId(78L);
        assertBothPaths(b1, "960.000", null, "0");
    }

    @Test
    @DisplayName("标度无关：声明 600.000 - 已售 199.9 - INPUT 0.100 = 400")
    void scaleInsensitive() {
        assertBothPaths(batch(13L, "600.000", "ACTIVE"), "0.100", "199.9", "400");
    }

    @Test
    @DisplayName("声明数量缺失（历史异常数据）→ null，与 Slice 5 前一致")
    void nullQuantity() {
        assertBothPaths(batch(14L, null, "ACTIVE"), null, null, null);
    }

    @Test
    @DisplayName("批量路径：任意批次数恰好 2 次聚合查询（无 N+1），不调用单批次查询；多批次结果按 ID 归属且与单批次一致")
    void bulk_twoQueriesNoNPlusOne() {
        List<Batch> batches = new ArrayList<>();
        batches.add(batch(21L, "600.000", "ACTIVE"));
        batches.add(batch(22L, "360.000", "ACTIVE"));
        batches.add(batch(23L, "1000.000", "CLOSED"));
        batches.add(batch(24L, "50.000", "ACTIVE"));
        when(itemMapper.sumSubmittedInputQuantityByBatchIds(List.of(21L, 22L, 23L, 24L)))
                .thenReturn(List.of(row(BigInteger.valueOf(23L), new BigDecimal("1000.000")), row(24L, "80.000")));
        when(saleMapper.sumSubmittedQuantityByBatchIds(List.of(21L, 22L, 23L, 24L)))
                .thenReturn(List.of(row(BigInteger.valueOf(21L), new BigDecimal("200.000")), row(22L, new BigDecimal("360"))));

        Map<Long, BigDecimal> remaining = service.remainingOf(batches);

        assertThat(remaining).hasSize(4);
        assertThat(remaining.get(21L)).isEqualByComparingTo("400");
        assertThat(remaining.get(22L)).isEqualByComparingTo("0");
        assertThat(remaining.get(23L)).isEqualByComparingTo("0");
        assertThat(remaining.get(24L)).isEqualByComparingTo("0");
        verify(itemMapper, times(1)).sumSubmittedInputQuantityByBatchIds(anyCollection());
        verify(saleMapper, times(1)).sumSubmittedQuantityByBatchIds(anyCollection());
        verify(itemMapper, never()).sumSubmittedInputQuantityByBatchId(anyLong());
        verify(saleMapper, never()).sumSubmittedQuantityByBatchId(anyLong());
    }

    @Test
    @DisplayName("批量路径：空集合不访问数据库")
    void bulk_emptyNoQueries() {
        assertThat(service.remainingOf(List.<Batch>of())).isEmpty();
        verify(itemMapper, never()).sumSubmittedInputQuantityByBatchIds(anyCollection());
        verify(saleMapper, never()).sumSubmittedQuantityByBatchIds(anyCollection());
    }

    @Test
    @DisplayName("operationConsumed / sold：无记录时为 0（不为 null）")
    void componentQueries_zeroWhenAbsent() {
        when(itemMapper.sumSubmittedInputQuantityByBatchId(30L)).thenReturn(null);
        when(saleMapper.sumSubmittedQuantityByBatchId(30L)).thenReturn(null);
        assertThat(service.operationConsumed(30L)).isEqualByComparingTo("0");
        assertThat(service.sold(30L)).isEqualByComparingTo("0");
    }
}
