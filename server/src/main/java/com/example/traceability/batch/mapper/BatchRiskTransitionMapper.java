package com.example.traceability.batch.mapper;

import com.example.traceability.batch.domain.BatchRiskTransition;
import com.example.traceability.batch.domain.PendingAlertHold;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 批次风险状态转换台账持久层访问接口。
 * <p>
 * 台账追加式不可修改：刻意<b>不</b>继承 {@code BaseMapper}，只提供插入与查询，不存在任何 UPDATE / DELETE 方法。
 * 只允许 {@code BatchRiskService} 注入（{@code RiskStatusWriteContainmentTest} 校验）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchRiskTransitionMapper {

    /**
     * 追加一行风险状态转换；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO batch_risk_transition
                (batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id, source_recall_id, actor_user_id,
                 reason, idempotency_key, request_hash, occurred_at, created_at)
            VALUES
                (#{batchId}, #{orgId}, #{flowStatus}, #{fromStatus}, #{toStatus}, #{sourceType}, #{sourceAlertId}, #{sourceRecallId},
                 #{actorUserId}, #{reason}, #{idempotencyKey}, #{requestHash}, #{occurredAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(BatchRiskTransition transition);

    /**
     * 组织内幂等键查询（预读与持锁后复读）。
     * <p>
     * 必须每次真正查询数据库：同一事务内 MyBatis 会话级一级缓存会让持锁后复读直接返回预读时缓存的 null，
     * 从而看不到等待批次行锁期间已提交的同键转换（READ COMMITTED 的复读意义即在于此），因此执行前清空本地缓存。
     * </p>
     */
    @Select("SELECT * FROM batch_risk_transition WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BatchRiskTransition selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 当前锁定读：并发插入触发唯一键冲突后读取最新已提交的同组织同幂等键转换。
     */
    @Select("SELECT * FROM batch_risk_transition WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    BatchRiskTransition selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 批次完整风险转换历史（按登记顺序）：当前责任组织与平台只读角色使用。
     */
    @Select("SELECT * FROM batch_risk_transition WHERE batch_id = #{batchId} ORDER BY id ASC")
    List<BatchRiskTransition> selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 某组织在该批次上登记的风险转换（历史参与组织只读：只含本组织当时作为责任组织的转换）。
     */
    @Select("SELECT * FROM batch_risk_transition WHERE batch_id = #{batchId} AND org_id = #{orgId} ORDER BY id ASC")
    List<BatchRiskTransition> selectByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 统计某组织在该批次上登记的风险转换数（历史参与组织只读判定，与追溯事件 / 批次操作的判定方式一致）。
     */
    @Select("SELECT COUNT(*) FROM batch_risk_transition WHERE batch_id = #{batchId} AND org_id = #{orgId}")
    int countByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 统计批次所在的、尚未对该批次形成放行决定的未处置告警数（PB4）。只在持有批次行锁后调用：
     * 告警冻结与告警放行同样先锁批次行，因此 READ COMMITTED 下的这次读取稳定。大于 0 时人工解除冻结必须改走告警质量结论放行。
     */
    @Select("""
            SELECT COUNT(*)
            FROM alert_batch ab
            JOIN alert a ON a.id = ab.alert_id
            WHERE ab.batch_id = #{batchId}
              AND a.status <> 'RESOLVED'
              AND NOT EXISTS (SELECT 1 FROM alert_action x
                              WHERE x.alert_id = ab.alert_id AND x.action = 'RELEASE_BATCH' AND x.batch_id = ab.batch_id)
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int countPendingAlertDecisions(@Param("batchId") Long batchId);

    /**
     * 批次上的未解除告警风险事项（Phase B 独立评审修复；按告警 ID 升序），定义与 {@link #countPendingAlertDecisions} 相同。
     * 告警放行在持有批次行锁后调用：创建风险事项（告警冻结 / 快照先锁批次行再插入 alert_batch）与解除风险事项（放行结论、召回）
     * 同样先锁批次行，而仍有未解除风险事项的告警不能形成处置结论，因此 READ COMMITTED 下的这次读取稳定。
     */
    @Select("""
            SELECT a.id AS alert_id, a.alert_no, a.status AS alert_status, a.shipment_id
            FROM alert_batch ab
            JOIN alert a ON a.id = ab.alert_id
            WHERE ab.batch_id = #{batchId}
              AND a.status <> 'RESOLVED'
              AND NOT EXISTS (SELECT 1 FROM alert_action x
                              WHERE x.alert_id = ab.alert_id AND x.action = 'RELEASE_BATCH' AND x.batch_id = ab.batch_id)
            ORDER BY a.id ASC
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<PendingAlertHold> selectPendingAlertHolds(@Param("batchId") Long batchId);

    /**
     * 批次最近一次转入 FROZEN 的来源类型（Phase B 独立评审修复：人工风险冻结事项判定）。批次当前为 FROZEN 时，
     * 这次转换开启了当前冻结期；来源为 MANUAL 表示质量管理员的人工冻结尚未经人工解除。
     */
    @Select("SELECT source_type FROM batch_risk_transition WHERE batch_id = #{batchId} AND to_status = 'FROZEN' ORDER BY id DESC LIMIT 1")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    String selectLatestFreezeSourceType(@Param("batchId") Long batchId);
}
