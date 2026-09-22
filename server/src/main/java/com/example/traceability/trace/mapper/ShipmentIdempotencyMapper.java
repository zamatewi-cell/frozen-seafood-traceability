package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.ShipmentIdempotency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 冷链运输任务多动作统一幂等记录持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Mapper
public interface ShipmentIdempotencyMapper extends BaseMapper<ShipmentIdempotency> {

    /**
     * 根据组织 ID 和客户端幂等键查询幂等记录（普通快照读）。
     */
    @Select("SELECT * FROM shipment_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    ShipmentIdempotency selectByOrgIdAndKey(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 穿透 REPEATABLE READ 快照盲区的当前锁定读 (FOR UPDATE)。
     */
    @Select("SELECT * FROM shipment_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    ShipmentIdempotency selectByOrgIdAndKeyForUpdate(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
