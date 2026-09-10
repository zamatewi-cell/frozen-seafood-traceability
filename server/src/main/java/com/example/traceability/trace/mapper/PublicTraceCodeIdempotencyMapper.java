package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.PublicTraceCodeIdempotency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 公开追溯码统一操作幂等记录持久层接口。
 * <p>
 * 统一根据 (org_id, idempotency_key) 提供幂等预检与锁定当前读查询。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface PublicTraceCodeIdempotencyMapper extends BaseMapper<PublicTraceCodeIdempotency> {

    /**
     * 根据组织 ID 和幂等键查询幂等记录（快照读）。
     *
     * @param orgId          组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 幂等记录；若不存在则返回 null
     */
    @Select("SELECT * FROM public_trace_code_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    PublicTraceCodeIdempotency selectByOrgIdAndKey(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 根据组织 ID 和幂等键执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在并发插入发生唯一键 DuplicateKey 竞态时，穿透 MySQL REPEATABLE READ 快照盲区，
     * 获取最新已提交的幂等记录，判断是否为相同语义重放还是冲突。
     * </p>
     *
     * @param orgId          组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 幂等记录；若不存在则返回 null
     */
    @Select("SELECT * FROM public_trace_code_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    PublicTraceCodeIdempotency selectByOrgIdAndKeyForUpdate(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
