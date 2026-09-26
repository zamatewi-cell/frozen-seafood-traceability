package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.AlertBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警受影响批次快照 Mapper（Phase B PB3）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：快照只在告警创建事务内插入，之后不可改写或删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface AlertBatchMapper {

    /**
     * 插入一条受影响批次快照；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO alert_batch
                (alert_id, batch_id, transfer_id, org_id, risk_status_before, freeze_transition_id, created_at)
            VALUES
                (#{alertId}, #{batchId}, #{transferId}, #{orgId}, #{riskStatusBefore}, #{freezeTransitionId}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AlertBatch alertBatch);

    /**
     * 告警受影响批次快照，关联批次与交接的当前事实（按批次 ID 升序）。
     */
    @Select("""
            SELECT ab.id, ab.alert_id, ab.batch_id, ab.transfer_id, ab.org_id, ab.risk_status_before,
                   ab.freeze_transition_id, ab.created_at,
                   b.trace_batch_no, b.org_id AS current_org_id, b.flow_status AS current_flow_status,
                   b.risk_status AS current_risk_status, b.quantity, b.unit_code,
                   t.transfer_no, t.status AS transfer_status
            FROM alert_batch ab
            JOIN batch b ON b.id = ab.batch_id
            JOIN transfer t ON t.id = ab.transfer_id
            WHERE ab.alert_id = #{alertId}
            ORDER BY ab.batch_id ASC
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<AlertBatch> selectByAlertId(@Param("alertId") Long alertId);
}
