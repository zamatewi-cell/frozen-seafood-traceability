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
 * 严格落实组织数据范围隔离。写更新与状态流转单条 SQL 强制约束 id + org_id + status='DRAFT' + version，
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
     * 根据组织 ID 和业务批次号查询批次（验证同组织内批次号唯一性）。
     *
     * @param orgId   组织 ID
     * @param batchNo 业务批次号
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE org_id = #{orgId} AND batch_no = #{batchNo} AND is_deleted = 0")
    Batch selectByOrgIdAndBatchNo(@Param("orgId") Long orgId, @Param("batchNo") String batchNo);

    /**
     * 根据组织 ID 和创建防重幂等键查询批次。
     *
     * @param orgId                  组织 ID
     * @param creationIdempotencyKey 客户端幂等键
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE org_id = #{orgId} AND creation_idempotency_key = #{creationIdempotencyKey} AND is_deleted = 0")
    Batch selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("creationIdempotencyKey") String creationIdempotencyKey);

    /**
     * 根据组织 ID 和创建防重幂等键执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在 MySQL 默认 REPEATABLE READ 隔离级别下，事务内普通快照读无法看到其他并发事务后提交的行；
     * 当并发插入发生 DuplicateKeyException 竞态时，必须使用当前读穿透快照读 Read View 盲区，
     * 获取最新已提交批次记录，防止相同幂等请求被误判为批次号冲突 (BATCH_NO_CONFLICT)。
     * </p>
     *
     * @param orgId                  组织 ID
     * @param creationIdempotencyKey 客户端幂等键
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE org_id = #{orgId} AND creation_idempotency_key = #{creationIdempotencyKey} AND is_deleted = 0 FOR UPDATE")
    Batch selectByOrgIdAndIdempotencyKeyForUpdate(
            @Param("orgId") Long orgId,
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    /**
     * 根据组织 ID 和业务批次号执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在 MySQL 默认 REPEATABLE READ 隔离级别下，当并发插入发生 DuplicateKeyException 且非幂等键冲突时，
     * 使用当前读穿透快照读视图，读取最新已提交的同组织同批次号记录。
     * </p>
     *
     * @param orgId   组织 ID
     * @param batchNo 业务批次号
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE org_id = #{orgId} AND batch_no = #{batchNo} AND is_deleted = 0 FOR UPDATE")
    Batch selectByOrgIdAndBatchNoForUpdate(
            @Param("orgId") Long orgId,
            @Param("batchNo") String batchNo
    );

    /**
     * 忽略组织范围根据批次 ID 执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在 PATCH / SUBMIT 条件更新受影响行数 affectedRows 为 0 时使用。
     * 避免 REPEATABLE READ 默认快照读读到前置事务快照中的旧状态或旧版本号，
     * 从而将并发状态流转（如已转为 ACTIVE）误分类为版本冲突 (VERSION_CONFLICT)。
     * </p>
     *
     * @param id 批次 ID
     * @return 批次实体；若不存在则返回 null
     */
    @Select("SELECT * FROM batch WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Batch selectByIdIgnoreTenantForUpdate(@Param("id") Long id);

    /**
     * 单条 SQL 条件更新草稿批次。
     * <p>
     * 同时约束 id + org_id + status='DRAFT' + version，保证原子递增版本并隔离跨组织/状态冲突。
     * </p>
     *
     * @param batch           待更新属性实体
     * @param expectedVersion 请求期望的当前版本号
     * @return 影响行数（1 为成功，0 为发生冲突或不存在）
     */
    @Update("""
            UPDATE batch
            SET quantity = #{batch.quantity},
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
              AND status = 'DRAFT'
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int updateDraftBatch(@Param("batch") Batch batch, @Param("expectedVersion") Long expectedVersion);

    /**
     * 单条 SQL 条件流转提交草稿批次（DRAFT -> ACTIVE）。
     * <p>
     * 同时约束 id + org_id + status='DRAFT' + version，版本原子 +1。
     * </p>
     *
     * @param id              批次 ID
     * @param orgId           组织 ID
     * @param expectedVersion 请求期望的当前版本号
     * @param updatedAt       更新时间 UTC
     * @param updatedBy       更新人 ID
     * @return 影响行数（1 为成功，0 为状态非 DRAFT 或版本冲突或跨组织越权）
     */
    @Update("""
            UPDATE batch
            SET status = 'ACTIVE',
                version = version + 1,
                updated_at = #{updatedAt},
                updated_by = #{updatedBy}
            WHERE id = #{id}
              AND org_id = #{orgId}
              AND status = 'DRAFT'
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
     * 组织范围条件下的批次总数统计。
     *
     * @param orgId  组织 ID（为 null 时查询全平台）
     * @param status 状态筛选
     * @return 记录总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM batch WHERE is_deleted = 0 " +
            "<if test='orgId != null'> AND org_id = #{orgId} </if>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "</script>")
    long countBatches(@Param("orgId") Long orgId, @Param("status") String status);

    /**
     * 组织范围条件下的批次分页查询。
     *
     * @param orgId  组织 ID（为 null 时查询全平台）
     * @param status 状态筛选
     * @param offset 偏移量
     * @param size   每页记录数
     * @return 批次列表
     */
    @Select("<script>" +
            "SELECT * FROM batch WHERE is_deleted = 0 " +
            "<if test='orgId != null'> AND org_id = #{orgId} </if>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "ORDER BY id DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Batch> selectBatchesPage(
            @Param("orgId") Long orgId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("size") int size
    );
}
