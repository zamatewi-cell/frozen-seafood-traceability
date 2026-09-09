package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 批次操作与物料平衡持久层访问接口。
 * <p>
 * 支持基于同组织的创建防重幂等查询、提交防重幂等查询、排他行锁查询以及单条原子提交流转更新。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchOperationMapper extends BaseMapper<BatchOperation> {

    @Select("SELECT * FROM batch_operation WHERE org_id = #{orgId} AND idempotency_key = #{key} AND is_deleted = 0")
    BatchOperation selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("key") String key);

    @Select("SELECT * FROM batch_operation WHERE org_id = #{orgId} AND idempotency_key = #{key} AND is_deleted = 0 FOR UPDATE")
    BatchOperation selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("key") String key);

    @Select("SELECT * FROM batch_operation WHERE org_id = #{orgId} AND submission_idempotency_key = #{key} AND is_deleted = 0")
    BatchOperation selectByOrgIdAndSubmissionKey(@Param("orgId") Long orgId, @Param("key") String key);

    @Select("SELECT * FROM batch_operation WHERE org_id = #{orgId} AND submission_idempotency_key = #{key} AND is_deleted = 0 FOR UPDATE")
    BatchOperation selectByOrgIdAndSubmissionKeyForUpdate(@Param("orgId") Long orgId, @Param("key") String key);

    @Select("SELECT * FROM batch_operation WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    BatchOperation selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM batch_operation WHERE id = #{id} AND is_deleted = 0")
    BatchOperation selectByIdIgnoreTenant(@Param("id") Long id);

    @Select("SELECT * FROM batch_operation WHERE id = #{id} AND org_id = #{orgId} AND is_deleted = 0")
    BatchOperation selectByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    @Update("UPDATE batch_operation SET status = 'SUBMITTED', submission_idempotency_key = #{submissionKey}, " +
            "version = version + 1, updated_at = #{nowUtc}, updated_by = #{updatedBy} " +
            "WHERE id = #{id} AND org_id = #{orgId} AND status = 'DRAFT' AND version = #{expectedVersion} AND is_deleted = 0")
    int submitOperation(
            @Param("id") Long id,
            @Param("orgId") Long orgId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("submissionKey") String submissionKey,
            @Param("nowUtc") LocalDateTime nowUtc,
            @Param("updatedBy") Long updatedBy
    );
}
