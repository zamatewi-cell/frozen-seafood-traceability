package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.sale.mapper.SaleMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批次派生数量唯一计算入口（统一业务契约 v1.1 §5）。
 * <p>
 * {@code remainingQuantity = declaredQuantity - operationConsumedQuantity - soldQuantity - disposedQuantity}：
 * <ul>
 *   <li>operationConsumedQuantity：已提交批次操作中该批次作为 INPUT 的累计数量；</li>
 *   <li>soldQuantity：该批次已提交终端销售 (Sale) 的累计数量；</li>
 *   <li>disposedQuantity：受控处置属于 Phase B，当前恒为 0；</li>
 *   <li>CLOSED 批次剩余量为 0；结果不小于 0。</li>
 * </ul>
 * 服务端从不接受客户端提交的剩余量。批次列表 / 详情、批次操作全量投入校验与终端销售超卖校验共用本服务，
 * 调用方如需并发安全，必须先持有批次行锁并在 READ COMMITTED 下读取。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchQuantityService {

    private final BatchOperationItemMapper itemMapper;
    private final SaleMapper saleMapper;

    public BatchQuantityService(BatchOperationItemMapper itemMapper, SaleMapper saleMapper) {
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper 不能为空");
        this.saleMapper = Objects.requireNonNull(saleMapper, "saleMapper 不能为空");
    }

    /**
     * 已提交批次操作中该批次作为 INPUT 的累计消耗量。
     */
    public BigDecimal operationConsumed(Long batchId) {
        return zeroIfNull(itemMapper.sumSubmittedInputQuantityByBatchId(batchId));
    }

    /**
     * 该批次已提交终端销售的累计数量。
     */
    public BigDecimal sold(Long batchId) {
        return zeroIfNull(saleMapper.sumSubmittedQuantityByBatchId(batchId));
    }

    /**
     * 单个批次派生剩余量。
     */
    public BigDecimal remainingOf(Batch batch) {
        if (batch == null) {
            return null;
        }
        return derive(batch, operationConsumed(batch.getId()), sold(batch.getId()));
    }

    /**
     * 批量派生剩余量（两次聚合查询）。
     *
     * @return 批次 ID → 剩余量
     */
    public Map<Long, BigDecimal> remainingOf(Collection<Batch> batches) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (batches == null || batches.isEmpty()) {
            return result;
        }
        List<Long> ids = batches.stream().map(Batch::getId).toList();
        Map<Long, BigDecimal> consumed = toMap(itemMapper.sumSubmittedInputQuantityByBatchIds(ids));
        Map<Long, BigDecimal> sold = toMap(saleMapper.sumSubmittedQuantityByBatchIds(ids));
        for (Batch b : batches) {
            result.put(b.getId(), derive(b, consumed.get(b.getId()), sold.get(b.getId())));
        }
        return result;
    }

    private static BigDecimal derive(Batch batch, BigDecimal consumed, BigDecimal sold) {
        if (BatchFlowStatus.CLOSED.name().equals(batch.getFlowStatus())) {
            return BigDecimal.ZERO;
        }
        if (batch.getQuantity() == null) {
            return null;
        }
        BigDecimal remaining = batch.getQuantity().subtract(zeroIfNull(consumed)).subtract(zeroIfNull(sold));
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private static Map<Long, BigDecimal> toMap(List<Map<String, Object>> rows) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            Object id = row.get("batchId");
            Object total = row.get("total");
            if (id instanceof Number n && total != null) {
                result.put(n.longValue(), total instanceof BigDecimal bd ? bd : new BigDecimal(total.toString()));
            }
        }
        return result;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
