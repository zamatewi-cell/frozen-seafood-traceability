package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.AlertAction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警处置动作台账 Mapper（追加式；Phase B PB3）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：台账只允许插入与查询，不提供任何修改或删除语句。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface AlertActionMapper {

    /**
     * 追加一条处置动作；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO alert_action
                (alert_id, org_id, action, actor_user_id, note, idempotency_key, request_hash, occurred_at)
            VALUES
                (#{alertId}, #{orgId}, #{action}, #{actorUserId}, #{note}, #{idempotencyKey}, #{requestHash}, #{occurredAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AlertAction action);

    /**
     * 组织内幂等键查询（预读与持锁后复读）。每次真正查询数据库：同一事务内的会话级一级缓存不能让持锁后复读
     * 返回预读时缓存的 null。
     */
    @Select("SELECT * FROM alert_action WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    AlertAction selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 当前锁定读：并发插入触发唯一键冲突后读取最新已提交的同组织同幂等键动作。
     */
    @Select("SELECT * FROM alert_action WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    AlertAction selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 告警全部处置动作（按登记顺序）。
     */
    @Select("SELECT * FROM alert_action WHERE alert_id = #{alertId} ORDER BY id ASC")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<AlertAction> selectByAlertId(@Param("alertId") Long alertId);
}
