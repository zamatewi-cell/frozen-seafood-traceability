package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.Batch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 追溯批次持久层访问接口。
 * <p>
 * 严格落实组织数据范围隔离。写更新与状态流转单条 SQL 强制约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version，
 * 从底层保证多租户隔离与乐观并发安全。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchMapper extends BaseMapper<Batch> {

    /**
     * 根据批次 ID 和组织 ID 查询批次（强制本组织数据隔离）。
     *
     * @param id    批次 ID
     * @param orgId 组织 ID
     * @return 批次实体；若不存在或不属于该组织则返回 null
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND org_id = #{orgId} AND is_deleted = 0")
    Batch selectByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * 根据批次 ID 和组织 ID 执行当前排他锁定读 (SELECT ... FOR UPDATE)（强制组织数据隔离）。
     *
     * @param id    批次 ID
     * @param orgId 组织 ID
     * @return 批次实体；若不存在或不属于该组织则返回 null
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND org_id = #{orgId} AND is_deleted = 0 FOR UPDATE")
    Batch selectByIdAndOrgIdForUpdate(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * 仅查询批次所属的组织 ID 标量（用于在租户批次未命中时，安全区分全局 404 RESOURCE_NOT_FOUND 与跨组织越权 403 ORG_SCOPE_DENIED，严禁读取批次实体内容）。
     *
     * @param id 批次 ID
     * @return 所属组织 ID；若批次不存在或已逻辑删除则返回 null
     */
    @Select("SELECT org_id FROM batch WHERE id = #{id} AND is_deleted = 0")
    Long selectOrgIdByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 忽略组织范围根据批次 ID 查询批次（用于安全区分 404 与跨组织 403 ORG_SCOPE_DENIED）。
     *
     * @param id 批次 ID
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND is_deleted = 0")
    Batch selectByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 根据全局唯一追溯批次号查询批次。
     *
     * @param traceBatchNo 全局唯一追溯批次号
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE trace_batch_no = #{traceBatchNo} AND is_deleted = 0")
    Batch selectByTraceBatchNo(@Param("traceBatchNo") String traceBatchNo);

    /**
     * 根据全局唯一追溯批次号执行当前排他锁定读 (SELECT ... FOR UPDATE)。
     *
     * @param traceBatchNo 全局唯一追溯批次号
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE trace_batch_no = #{traceBatchNo} AND is_deleted = 0 FOR UPDATE")
    Batch selectByTraceBatchNoForUpdate(@Param("traceBatchNo") String traceBatchNo);

    /**
     * 根据<b>创建组织</b> ID 和创建防重幂等键查询批次。
     * <p>
     * 创建幂等的作用域必须是不可变的 {@code creation_org_id}，而不是会在 Transfer ACCEPTED 时
     * 转移给接收方的 {@code org_id}；否则批次一旦转出，原创建方重放原始创建请求将查不到原批次而重复建批，
     * 且接收方若持有同名幂等键会导致交接永久唯一键冲突。
     * </p>
     *
     * @param creationOrgId          创建组织 ID
     * @param creationIdempotencyKey 客户端幂等键
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE creation_org_id = #{creationOrgId} AND creation_idempotency_key = #{creationIdempotencyKey} AND is_deleted = 0")
    Batch selectByCreationOrgIdAndIdempotencyKey(
            @Param("creationOrgId") Long creationOrgId,
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    /**
     * 根据<b>创建组织</b> ID 和创建防重幂等键执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 用于在并发插入触发 DuplicateKeyException 后穿透 REPEATABLE READ 快照盲区，
     * 读取最新已提交的同创建组织同幂等键批次。
     * </p>
     *
     * @param creationOrgId          创建组织 ID
     * @param creationIdempotencyKey 客户端幂等键
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE creation_org_id = #{creationOrgId} AND creation_idempotency_key = #{creationIdempotencyKey} AND is_deleted = 0 FOR UPDATE")
    Batch selectByCreationOrgIdAndIdempotencyKeyForUpdate(
            @Param("creationOrgId") Long creationOrgId,
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    /**
     * 忽略组织范围根据批次 ID 执行当前锁定读 (SELECT ... FOR UPDATE)。
     *
     * @param id 批次 ID
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Batch selectByIdIgnoreTenantForUpdate(@Param("id") Long id);

    /**
     * 单条 SQL 条件更新草稿批次。
     * <p>
     * 同时约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version，
     * 保证原子递增版本并隔离跨组织/状态冲突。
     * </p>
     *
     * @param batch           待更新属性实体
     * @param expectedVersion 请求期望的当前版本号
     * @return 影响行数（1 为成功，0 为发生冲突或不存在）
     */
    @Update("""
            UPDATE batch
            SET quantity = #{batch.quantity},
                external_batch_no = #{batch.externalBatchNo},
                origin_text = #{batch.originText},
                production_date = #{batch.productionDate},
                capture_date = #{batch.captureDate},
                freeze_date = #{batch.freezeDate},
                shelf_life_days = #{batch.shelfLifeDays},
                updated_at = #{batch.updatedAt},
                updated_by = #{batch.updatedBy},
                version = version + 1
            WHERE id = #{batch.id}
              AND org_id = #{batch.orgId}
              AND flow_status = 'DRAFT'
              AND risk_status = 'NORMAL'
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int updateDraftBatch(@Param("batch") Batch batch, @Param("expectedVersion") Long expectedVersion);

    /**
     * 单条 SQL 条件流转提交草稿批次（DRAFT+NORMAL -> ACTIVE+NORMAL）。
     * <p>
     * 同时约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version，版本原子 +1。
     * </p>
     *
     * @param id              批次 ID
     * @param orgId           组织 ID
     * @param expectedVersion 请求期望的当前版本号
     * @param updatedAt       更新时间 UTC
     * @param updatedBy       更新人 ID
     * @return 影响行数（1 为成功，0 为状态非 DRAFT+NORMAL 或版本冲突或跨组织越权）
     */
    @Update("""
            UPDATE batch
            SET flow_status = 'ACTIVE',
                version = version + 1,
                updated_at = #{updatedAt},
                updated_by = #{updatedBy}
            WHERE id = #{id}
              AND org_id = #{orgId}
              AND flow_status = 'DRAFT'
              AND risk_status = 'NORMAL'
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int submitDraftBatch(
            @Param("id") Long id,
            @Param("orgId") Long orgId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("updatedBy") Long updatedBy
    );

    /**
     * 组织范围与双编号双状态条件下的批次总数统计。
     *
     * @param orgId           组织 ID（为 null 时查询全平台）
     * @param traceBatchNo    追溯批次号（可选）
     * @param externalBatchNo 外部业务批次号（可选）
     * @param flowStatus      流转状态筛选
     * @param riskStatus      风险状态筛选
     * @return 记录总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM batch WHERE is_deleted = 0 " +
            "<if test='orgId != null'> AND org_id = #{orgId} </if>" +
            "<if test='traceBatchNo != null and traceBatchNo != \"\"'> AND trace_batch_no = #{traceBatchNo} </if>" +
            "<if test='externalBatchNo != null and externalBatchNo != \"\"'> AND external_batch_no = #{externalBatchNo} </if>" +
            "<if test='flowStatus != null and flowStatus != \"\"'> AND flow_status = #{flowStatus} </if>" +
            "<if test='riskStatus != null and riskStatus != \"\"'> AND risk_status = #{riskStatus} </if>" +
            "</script>")
    long countBatches(
            @Param("orgId") Long orgId,
            @Param("traceBatchNo") String traceBatchNo,
            @Param("externalBatchNo") String externalBatchNo,
            @Param("flowStatus") String flowStatus,
            @Param("riskStatus") String riskStatus
    );

    /**
     * 组织范围与双编号双状态条件下的批次分页查询。
     *
     * @param orgId           组织 ID（为 null 时查询全平台）
     * @param traceBatchNo    追溯批次号（可选）
     * @param externalBatchNo 外部业务批次号（可选）
     * @param flowStatus      流转状态筛选
     * @param riskStatus      风险状态筛选
     * @param offset          偏移量
     * @param size            每页记录数
     * @return 批次列表
     */
    @Select("<script>" +
            "SELECT * FROM batch WHERE is_deleted = 0 " +
            "<if test='orgId != null'> AND org_id = #{orgId} </if>" +
            "<if test='traceBatchNo != null and traceBatchNo != \"\"'> AND trace_batch_no = #{traceBatchNo} </if>" +
            "<if test='externalBatchNo != null and externalBatchNo != \"\"'> AND external_batch_no = #{externalBatchNo} </if>" +
            "<if test='flowStatus != null and flowStatus != \"\"'> AND flow_status = #{flowStatus} </if>" +
            "<if test='riskStatus != null and riskStatus != \"\"'> AND risk_status = #{riskStatus} </if>" +
            "ORDER BY id DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Batch> selectBatchesPage(
            @Param("orgId") Long orgId,
            @Param("traceBatchNo") String traceBatchNo,
            @Param("externalBatchNo") String externalBatchNo,
            @Param("flowStatus") String flowStatus,
            @Param("riskStatus") String riskStatus,
            @Param("offset") long offset,
            @Param("size") int size
    );

    /**
     * 根据批次 ID 排他锁定查询批次实体 (FOR UPDATE)。
     *
     * @param id 批次 ID
     * @return 批次实体
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Batch selectByIdForUpdate(@Param("id") Long id);

    /**
     * 更新批次<b>当前责任</b>持有企业组织 ID 并递增版本号 (强制限定旧持有组织与乐观锁版本号)。
     * <p>
     * 只改写 {@code org_id}；{@code creation_org_id} 是创建组织的不可变事实，
     * 交接绝不得修改它，否则创建幂等域会随责任转移而漂移。
     * </p>
     *
     * @param id                  批次 ID
     * @param expectedSenderOrgId 预期旧持有企业组织 ID (发货企业)
     * @param newOrgId            新持有企业组织 ID (接收企业)
     * @param expectedVersion     期望乐观锁版本号
     * @param updatedBy           更新人 ID
     * @return 影响行数 (1: 成功, 0: 组织谓词不匹配/版本冲突/未命中)
     */
    @Update("""
            UPDATE batch
            SET org_id = #{newOrgId},
                updated_by = #{updatedBy},
                version = version + 1,
                updated_at = NOW(6)
            WHERE id = #{id}
              AND org_id = #{expectedSenderOrgId}
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int updateOrgIdByIdAndVersion(
            @Param("id") Long id,
            @Param("expectedSenderOrgId") Long expectedSenderOrgId,
            @Param("newOrgId") Long newOrgId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("updatedBy") Long updatedBy
    );
}
