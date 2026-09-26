package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.TemperatureRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Shipment 在途温度记录持久层访问接口（Phase B PB2）。
 * <p>
 * 温度记录追加式不可修改：刻意<b>不</b>继承 {@code BaseMapper}，只提供插入与查询，不存在任何 UPDATE / DELETE 方法
 * （{@code TemperatureRecordAppendOnlyContainmentTest} 校验）。查询语句以非锁定读关联规则环节与规则主表，
 * 只补全规则 ID、名称与版本（来源追溯展示）；判定依据（上下限与允许越界时长）一律读取本表快照列，从不读取当前规则环节。
 * 唯一的锁定读只锁本表行（{@code FOR UPDATE OF tr}），从不锁规则表。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TemperatureRecordMapper {

    /** 关联规则信息的统一查询列（温度记录列与判定依据快照 + 规则来源关联字段）。 */
    String SELECT_WITH_RULE = """
            SELECT tr.id, tr.shipment_id, tr.org_id, tr.actor_user_id, tr.stage_code, tr.measured_at, tr.recorded_at,
                   tr.temperature, tr.unit_code, tr.data_source, tr.device_no, tr.rule_stage_id, tr.rule_lower_limit,
                   tr.rule_upper_limit, tr.rule_allowed_duration_seconds, tr.evaluation, tr.idempotency_key, tr.request_hash,
                   s.rule_id AS rule_id, r.name AS rule_name, r.version_no AS rule_version_no
            FROM temperature_record tr
            LEFT JOIN temperature_rule_stage s ON s.id = tr.rule_stage_id
            LEFT JOIN temperature_rule r ON r.id = s.rule_id
            """;

    /**
     * 追加一条温度记录；自增主键回填到实体。审计列 created_by / updated_by 取登记操作人。
     */
    @Insert("""
            INSERT INTO temperature_record
                (shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, temperature, unit_code,
                 data_source, device_no, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds,
                 evaluation, idempotency_key, request_hash, created_at, created_by, updated_at, updated_by)
            VALUES
                (#{shipmentId}, #{orgId}, #{actorUserId}, #{stageCode}, #{measuredAt}, #{recordedAt}, #{temperature}, #{unitCode},
                 #{dataSource}, #{deviceNo}, #{ruleStageId}, #{ruleLowerLimit}, #{ruleUpperLimit}, #{ruleAllowedDurationSeconds},
                 #{evaluation}, #{idempotencyKey}, #{requestHash}, #{recordedAt}, #{actorUserId}, #{recordedAt}, #{actorUserId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TemperatureRecord record);

    /**
     * 组织内幂等键查询（预读与持锁后复读）。
     * <p>
     * 必须每次真正查询数据库：同一事务内 MyBatis 会话级一级缓存会让持锁后复读直接返回预读时缓存的 null，
     * 从而看不到等待运输任务行锁期间已提交的同键记录，因此执行前清空本地缓存。
     * </p>
     */
    @Select(SELECT_WITH_RULE + " WHERE tr.org_id = #{orgId} AND tr.idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    TemperatureRecord selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 当前锁定读：并发插入触发唯一键冲突后读取最新已提交的同组织同幂等键记录（只锁温度记录行，不锁规则表）。
     */
    @Select(SELECT_WITH_RULE + " WHERE tr.org_id = #{orgId} AND tr.idempotency_key = #{idempotencyKey} FOR UPDATE OF tr")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    TemperatureRecord selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 运输任务全部温度记录，按测量时间、主键稳定排序（同一测量时间允许多条记录）。
     */
    @Select(SELECT_WITH_RULE + " WHERE tr.shipment_id = #{shipmentId} AND tr.is_deleted = 0 ORDER BY tr.measured_at ASC, tr.id ASC")
    List<TemperatureRecord> selectByShipmentId(@Param("shipmentId") Long shipmentId);
}
