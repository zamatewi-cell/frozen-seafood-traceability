package com.example.traceability.batch.domain;

import com.example.traceability.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 首次终端销售锁定守卫（统一业务契约 v1.1 §5 第 7 条、§9.1）。
 * <p>
 * 第一次有效 Sale 成功后，批次永久不得再参与 Transfer 或 BatchOperation（PROCESS / SPLIT / MERGE / REPACK），
 * 即使剩余量仍大于 0。判定依据是批次行上的写一次标记 {@code first_sale_id}：
 * 调用方必须传入<b>已持有行锁</b>（SELECT ... FOR UPDATE，当前读）的批次实体，
 * 从而在 REPEATABLE READ 事务中也能看到等待行锁期间已提交的首次销售，而不依赖快照读统计销售表。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class BatchSaleGuard {

    public static final String CODE = "BATCH_SALE_STARTED";

    private BatchSaleGuard() {
    }

    /**
     * 已开始终端销售的批次拒绝指定动作，返回 409 BATCH_SALE_STARTED。
     *
     * @param lockedBatch 已持有行锁的批次
     * @param action      被拒绝的动作说明（例如"交接"、"加工或拆分"）
     */
    public static void rejectIfSaleStarted(Batch lockedBatch, String action) {
        if (lockedBatch != null && lockedBatch.getFirstSaleId() != null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    CODE,
                    "批次已开始终端销售",
                    "批次 " + lockedBatch.getTraceBatchNo() + " 已发生终端销售，第一次有效销售后禁止再" + action
                            + "（统一业务契约 v1.1 §5）；剩余量只能继续终端销售"
            );
        }
    }
}
