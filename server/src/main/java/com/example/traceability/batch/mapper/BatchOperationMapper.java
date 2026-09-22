package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 同组织创建幂等键查询（包含已逻辑删除的操作），用于识别"幂等键已被已删除草稿占用"。
     */
    @Select("SELECT * FROM batch_operation WHERE org_id = #{orgId} AND idempotency_key = #{key}")
    BatchOperation selectByOrgIdAndIdempotencyKeyIncludingDeleted(@Param("orgId") Long orgId, @Param("key") String key);

    /**
     * 逻辑删除批次操作草稿（强约束组织、DRAFT 与乐观锁版本号）。
     *
     * @return 影响行数（1 为成功）
     */
    @Update("UPDATE batch_operation SET is_deleted = 1, version = version + 1, updated_at = #{nowUtc}, updated_by = #{updatedBy} " +
            "WHERE id = #{id} AND org_id = #{orgId} AND status = 'DRAFT' AND version = #{expectedVersion} AND is_deleted = 0")
    int softDeleteDraft(
            @Param("id") Long id,
            @Param("orgId") Long orgId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("nowUtc") LocalDateTime nowUtc,
            @Param("updatedBy") Long updatedBy
    );

    /**
     * 统计引用指定批次（作为 INPUT 或 OUTPUT）的未删除批次操作数量；orgId 为 null 时不限组织（平台只读）。
     */
    @Select("<script>" +
            "SELECT COUNT(DISTINCT op.id) FROM batch_operation op " +
            "JOIN batch_operation_item i ON i.operation_id = op.id AND i.is_deleted = 0 " +
            "WHERE op.is_deleted = 0 AND i.batch_id = #{batchId} " +
            "<if test='orgId != null'> AND op.org_id = #{orgId} </if>" +
            "</script>")
    long countByBatchId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 分页查询引用指定批次（作为 INPUT 或 OUTPUT）的未删除批次操作；orgId 为 null 时不限组织（平台只读）。
     */
    @Select("<script>" +
            "SELECT DISTINCT op.* FROM batch_operation op " +
            "JOIN batch_operation_item i ON i.operation_id = op.id AND i.is_deleted = 0 " +
            "WHERE op.is_deleted = 0 AND i.batch_id = #{batchId} " +
            "<if test='orgId != null'> AND op.org_id = #{orgId} </if>" +
            "ORDER BY op.id DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<BatchOperation> selectPageByBatchId(
            @Param("batchId") Long batchId,
            @Param("orgId") Long orgId,
            @Param("offset") long offset,
            @Param("size") int size
    );
}
