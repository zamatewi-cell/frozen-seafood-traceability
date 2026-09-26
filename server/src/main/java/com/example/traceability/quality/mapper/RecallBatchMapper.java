package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.RecallBatch;
import com.example.traceability.quality.domain.RecallScopeFact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 模拟召回影响范围快照 Mapper（Phase B PB5）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：快照只在召回发起事务内插入，之后不可改写或删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface RecallBatchMapper {

    @Insert("""
            INSERT INTO recall_batch
                (recall_id, batch_id, scope_role, depth, holder_org_id, flow_status, risk_status_before, action, risk_transition_id,
                 declared_quantity, remaining_quantity, sold_quantity, unit_code, open_transfer_id, open_transfer_status,
                 shipment_status, public_code_active, created_at)
            VALUES
                (#{recallId}, #{batchId}, #{scopeRole}, #{depth}, #{holderOrgId}, #{flowStatus}, #{riskStatusBefore}, #{action},
                 #{riskTransitionId}, #{declaredQuantity}, #{remainingQuantity}, #{soldQuantity}, #{unitCode}, #{openTransferId},
                 #{openTransferStatus}, #{shipmentStatus}, #{publicCodeActive}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RecallBatch row);

    /**
     * 召回影响范围快照，关联批次当前事实（角色顺序 SEED → DESCENDANT → ANCESTOR，其内按谱系距离与批次 ID）。
     */
    @Select("""
            SELECT rb.*, b.trace_batch_no, p.public_name AS product_name, b.risk_status AS current_risk_status,
                   b.flow_status AS current_flow_status, t.transfer_no AS open_transfer_no
            FROM recall_batch rb
            JOIN batch b ON b.id = rb.batch_id
            LEFT JOIN product p ON p.id = b.product_id
            LEFT JOIN transfer t ON t.id = rb.open_transfer_id
            WHERE rb.recall_id = #{recallId}
            ORDER BY FIELD(rb.scope_role, 'SEED', 'DESCENDANT', 'ANCESTOR'), ABS(rb.depth), rb.batch_id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<RecallBatch> selectByRecallId(@Param("recallId") Long recallId);

    /**
     * 批次是否出现在其他召回的正向影响范围中（NORMAL 批次紧急召回的证据之一：上游召回已圈定该批次）。
     */
    @Select("SELECT COUNT(*) FROM recall_batch WHERE batch_id = #{batchId} AND scope_role = 'DESCENDANT'")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int countDescendantScopeByBatchId(@Param("batchId") Long batchId);

    /**
     * 范围批次的下游事实：已终端销售数量、未结束交接（DRAFT / PENDING / QUARANTINED）及其运输状态、公开追溯码是否启用。
     */
    @Select("""
            <script>
            SELECT b.id AS batch_id,
                   (SELECT COALESCE(SUM(s.quantity), 0) FROM sale s WHERE s.batch_id = b.id AND s.status = 'SUBMITTED') AS sold_quantity,
                   ot.id AS open_transfer_id,
                   ot.status AS open_transfer_status,
                   sh.status AS shipment_status,
                   (SELECT COUNT(*) FROM public_trace_code c WHERE c.batch_id = b.id AND c.status = 'ACTIVE' AND c.is_deleted = 0)
                       AS public_code_active
            FROM batch b
            LEFT JOIN transfer ot ON ot.open_batch_id = b.id
            LEFT JOIN shipment sh ON sh.id = ot.shipment_id
            WHERE b.id IN
            <foreach collection="batchIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<RecallScopeFact> selectScopeFacts(@Param("batchIds") Collection<Long> batchIds);
}
