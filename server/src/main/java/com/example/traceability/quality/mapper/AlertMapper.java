package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.Alert;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警 Mapper（Phase B PB3）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：告警的片段、判定依据快照与归属组织创建后不可改写，只允许下列显式语句：
 * 插入（温度登记事务内的系统路径）与带状态 / 版本谓词的生命周期推进。不提供删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface AlertMapper {

    /** 告警列与运输任务参与方、规则来源展示字段（规则名称与版本只经规则环节关联用于展示，判定依据来自快照列）。 */
    String SELECT_ALERT = """
            SELECT a.id, a.alert_no, a.org_id, a.shipment_id, a.alert_type, a.severity, a.status, a.reason, a.stage_code,
                   a.episode_start_record_id, a.sustained_record_id, a.episode_started_at, a.sustained_at, a.duration_seconds,
                   a.rule_stage_id, a.rule_lower_limit, a.rule_upper_limit, a.rule_allowed_duration_seconds, a.triggered_at,
                   a.acknowledged_at, a.acknowledged_by, a.resolved_at, a.resolved_by, a.resolution, a.version,
                   a.created_at, a.updated_at,
                   s.shipment_no, s.receiver_org_id, s.carrier_org_id,
                   rs.rule_id AS rule_id, r.name AS rule_name, r.version_no AS rule_version_no
            FROM alert a
            JOIN shipment s ON s.id = a.shipment_id
            LEFT JOIN temperature_rule_stage rs ON rs.id = a.rule_stage_id
            LEFT JOIN temperature_rule r ON r.id = rs.rule_id
            """;

    /**
     * 系统创建告警（状态 OPEN）；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO alert
                (alert_no, org_id, shipment_id, alert_type, severity, status, reason, stage_code,
                 episode_start_record_id, sustained_record_id, episode_started_at, sustained_at, duration_seconds,
                 rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, triggered_at,
                 version, created_at, updated_at)
            VALUES
                (#{alertNo}, #{orgId}, #{shipmentId}, #{alertType}, #{severity}, #{status}, #{reason}, #{stageCode},
                 #{episodeStartRecordId}, #{sustainedRecordId}, #{episodeStartedAt}, #{sustainedAt}, #{durationSeconds},
                 #{ruleStageId}, #{ruleLowerLimit}, #{ruleUpperLimit}, #{ruleAllowedDurationSeconds}, #{triggeredAt},
                 0, #{triggeredAt}, #{triggeredAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Alert alert);

    /**
     * 按主键查询（非锁定读）。
     */
    @Select(SELECT_ALERT + " WHERE a.id = #{id}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Alert selectById(@Param("id") Long id);

    /**
     * 排他锁定告警行（只锁 alert，不锁关联的运输任务与规则表），处置动作的串行化点。
     */
    @Select(SELECT_ALERT + " WHERE a.id = #{id} FOR UPDATE OF a")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Alert selectByIdForUpdate(@Param("id") Long id);

    /**
     * 运输任务已有告警的片段标识（去重依据）。只在已持有该运输任务行锁（FOR UPDATE）时调用：告警只在温度登记事务内
     * 创建，而温度登记同样先锁运输任务行，因此持锁后的普通读（READ COMMITTED）稳定且完整。
     */
    @Select("SELECT id, shipment_id, episode_start_record_id, sustained_record_id FROM alert "
            + "WHERE shipment_id = #{shipmentId} ORDER BY id ASC")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Alert> selectEpisodeKeysByShipmentId(@Param("shipmentId") Long shipmentId);

    /**
     * 可见告警列表（按主键倒序，最多 200 条）：平台只读角色看全部；企业看本组织归属的告警，以及本组织作为运输任务
     * 接收方或承运方的告警（契约 §10.3：接收方可以查看相关 Alert）。
     */
    @Select("""
            <script>
            """ + SELECT_ALERT + """
            WHERE 1 = 1
            <if test="!platform">
              AND (a.org_id = #{orgId} OR s.receiver_org_id = #{orgId} OR s.carrier_org_id = #{orgId})
            </if>
            <if test="status != null">
              AND a.status = #{status}
            </if>
            <if test="shipmentId != null">
              AND a.shipment_id = #{shipmentId}
            </if>
            ORDER BY a.id DESC
            LIMIT 200
            </script>
            """)
    List<Alert> selectVisible(@Param("orgId") Long orgId, @Param("platform") boolean platform,
                              @Param("status") String status, @Param("shipmentId") Long shipmentId);

    /**
     * OPEN → ACKNOWLEDGED（确认人即处置负责人）；状态与版本谓词不满足时影响 0 行。
     */
    @Update("""
            UPDATE alert
            SET status = 'ACKNOWLEDGED',
                acknowledged_at = #{nowUtc},
                acknowledged_by = #{userId},
                version = version + 1,
                updated_at = #{nowUtc},
                updated_by = #{userId}
            WHERE id = #{id}
              AND status = 'OPEN'
              AND version = #{expectedVersion}
            """)
    int acknowledge(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion, @Param("userId") Long userId,
                    @Param("nowUtc") LocalDateTime nowUtc);
}
