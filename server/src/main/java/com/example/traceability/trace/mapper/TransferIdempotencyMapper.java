package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.TransferIdempotency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 企业间整批交接多动作统一幂等记录持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TransferIdempotencyMapper extends BaseMapper<TransferIdempotency> {

    /**
     * 根据组织 ID 和客户端防重幂等键查询幂等记录（普通快照读）。
     *
     * @param orgId          所属组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 幂等记录；未命中返回 null
     */
    @Select("SELECT * FROM transfer_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    TransferIdempotency selectByOrgIdAndKey(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 穿透 MySQL REPEATABLE READ 快照盲区的当前排他锁定读 (FOR UPDATE)。
     * 用于在并发竞态捕获 DuplicateKeyException 后安全恢复查询。
     *
     * @param orgId          所属组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 幂等记录
     */
    @Select("SELECT * FROM transfer_idempotency WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    TransferIdempotency selectByOrgIdAndKeyForUpdate(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
