package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.PublicTraceCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 对外公开追溯码持久层访问接口。
 * <p>
 * 严格落实组织数据范围隔离与锁定读。包含公开标识唯一查询、批次绑定查询、
 * 以及原子停用条件更新。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface PublicTraceCodeMapper extends BaseMapper<PublicTraceCode> {

    /**
     * 根据消费者公开标识 public_id 查询公开追溯码记录（消费者匿名查询，无需组织隔离）。
     *
     * @param publicId 公开追溯标识 (26位 Base32)
     * @return 追溯码实体；若不存在或已逻辑删除则返回 null
     */
    @Select("SELECT * FROM public_trace_code WHERE public_id = #{publicId} AND is_deleted = 0")
    PublicTraceCode selectByPublicId(@Param("publicId") String publicId);

    /**
     * 根据内部批次 ID 和组织 ID 查询关联的公开追溯码记录（企业端租户隔离读取）。
     *
     * @param batchId 批次 ID
     * @param orgId   组织 ID
     * @return 追溯码实体；若不存在则返回 null
     */
    @Select("SELECT * FROM public_trace_code WHERE batch_id = #{batchId} AND org_id = #{orgId} AND is_deleted = 0")
    PublicTraceCode selectByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 根据内部批次 ID 和组织 ID 执行当前排他锁定读 (SELECT ... FOR UPDATE)（企业端租户隔离排他锁定）。
     * <p>
     * 在停用事务及激活 DuplicateKeyException 恢复路径中锁定批次对应的公开追溯码，
     * 穿透 MySQL REPEATABLE READ 快照读盲区并读取并发事务已提交的当前版本。
     * </p>
     *
     * @param batchId 批次 ID
     * @param orgId   组织 ID
     * @return 追溯码实体；若不存在则返回 null
     */
    @Select("SELECT * FROM public_trace_code WHERE batch_id = #{batchId} AND org_id = #{orgId} AND is_deleted = 0 FOR UPDATE")
    PublicTraceCode selectByBatchIdAndOrgIdForUpdate(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 单条 SQL 原子条件流转停用公开追溯码（ACTIVE -> DISABLED）。
     * <p>
     * 强约束 id + org_id + status='ACTIVE' + is_deleted=0，并原子写入 disabled_at 及版本自增。
     * 幂等绑定统一由独立幂等表持久化保证。
     * </p>
     *
     * @param id         主键 ID
     * @param orgId      所属组织 ID
     * @param disabledAt 停用时间 (UTC)
     * @param updatedAt  更新时间 (UTC)
     * @param updatedBy  更新人 ID
     * @return 受影响行数 (1 为成功，0 为状态非 ACTIVE 或跨组织冲突)
     */
    @Update("""
            UPDATE public_trace_code
            SET status = 'DISABLED',
                disabled_at = #{disabledAt},
                updated_at = #{updatedAt},
                updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{id}
              AND org_id = #{orgId}
              AND status = 'ACTIVE'
              AND is_deleted = 0
            """)
    int disableTraceCode(
            @Param("id") Long id,
            @Param("orgId") Long orgId,
            @Param("disabledAt") LocalDateTime disabledAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("updatedBy") Long updatedBy
    );
}
