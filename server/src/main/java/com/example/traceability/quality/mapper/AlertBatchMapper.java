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
     * 告警受影响批次快照，关联批次与交接的当前事实与质量处置进展（放行结论与其放行转换、关联本告警的最新检验结论与报告数；
     * 按批次 ID 升序）。放行结论形成时仍有其他风险事项的，放行转换为空。
     */
    @Select("""
            SELECT ab.id, ab.alert_id, ab.batch_id, ab.transfer_id, ab.org_id, ab.risk_status_before,
                   ab.freeze_transition_id, ab.created_at,
                   b.trace_batch_no, b.org_id AS current_org_id, b.flow_status AS current_flow_status,
                   b.risk_status AS current_risk_status, b.quantity, b.unit_code,
                   t.transfer_no, t.status AS transfer_status,
                   (SELECT x.id FROM alert_action x
                     WHERE x.alert_id = ab.alert_id AND x.action = 'RELEASE_BATCH' AND x.batch_id = ab.batch_id) AS release_action_id,
                   (SELECT x.risk_transition_id FROM alert_action x
                     WHERE x.alert_id = ab.alert_id AND x.action = 'RELEASE_BATCH' AND x.batch_id = ab.batch_id) AS release_transition_id,
                   (SELECT ir.conclusion FROM inspection_report ir
                     WHERE ir.alert_id = ab.alert_id AND ir.batch_id = ab.batch_id ORDER BY ir.id DESC LIMIT 1) AS latest_inspection_conclusion,
                   (SELECT COUNT(*) FROM inspection_report ir
                     WHERE ir.alert_id = ab.alert_id AND ir.batch_id = ab.batch_id) AS inspection_count
            FROM alert_batch ab
            JOIN batch b ON b.id = ab.batch_id
            JOIN transfer t ON t.id = ab.transfer_id
            WHERE ab.alert_id = #{alertId}
            ORDER BY ab.batch_id ASC
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<AlertBatch> selectByAlertId(@Param("alertId") Long alertId);

    /**
     * 批次是否属于告警的受影响批次快照。
     */
    @Select("SELECT COUNT(*) FROM alert_batch WHERE alert_id = #{alertId} AND batch_id = #{batchId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int countByAlertIdAndBatchId(@Param("alertId") Long alertId, @Param("batchId") Long batchId);
}
