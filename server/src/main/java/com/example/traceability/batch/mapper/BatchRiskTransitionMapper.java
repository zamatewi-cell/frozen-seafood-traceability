package com.example.traceability.batch.mapper;

import com.example.traceability.batch.domain.BatchRiskTransition;
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
                (batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id, reason,
                 idempotency_key, request_hash, occurred_at, created_at)
            VALUES
                (#{batchId}, #{orgId}, #{flowStatus}, #{fromStatus}, #{toStatus}, #{sourceType}, #{actorUserId}, #{reason},
                 #{idempotencyKey}, #{requestHash}, #{occurredAt}, #{createdAt})
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
}
