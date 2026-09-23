package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.TraceEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 追溯事件持久层访问接口。
 * <p>
 * 严格落实组织数据范围隔离。列表查询必须在 Mapper SQL 显式带 org_id 约束。
 * 状态流转与更正锁定采用 SELECT ... FOR UPDATE 消除并发分叉竞态。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TraceEventMapper extends BaseMapper<TraceEvent> {

    /**
     * 根据批次 ID 和组织 ID 查询追溯事件列表（带组织隔离与稳定递增排序）。
     *
     * @param batchId 批次 ID
     * @param orgId   组织 ID
     * @return 稳定排序的追溯事件列表
     */
    @Select("SELECT * FROM trace_event WHERE batch_id = #{batchId} AND org_id = #{orgId} AND is_deleted = 0 ORDER BY occurred_at ASC, recorded_at ASC, id ASC")
    List<TraceEvent> selectByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 根据内部主键 ID 和组织 ID 查询事件。
     *
     * @param id    事件 ID
     * @param orgId 组织 ID
     * @return 追溯事件实体；若不存在或越权则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE id = #{id} AND org_id = #{orgId} AND is_deleted = 0")
    TraceEvent selectByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * 忽略组织范围根据主键 ID 查询事件（用于安全区分 404 与跨组织 403 ORG_SCOPE_DENIED）。
     *
     * @param id 事件 ID
     * @return 追溯事件实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE id = #{id} AND is_deleted = 0")
    TraceEvent selectByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 忽略组织范围根据主键 ID 执行排他行锁当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在更正流程中锁定待更正的原事件，防止并发双更正产生历史分叉。
     * </p>
     *
     * @param id 事件 ID
     * @return 追溯事件实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    TraceEvent selectByIdIgnoreTenantForUpdate(@Param("id") Long id);

    /**
     * 根据组织 ID 和防重幂等键查询事件记录。
     *
     * @param orgId          组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 追溯事件实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} AND is_deleted = 0")
    TraceEvent selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 根据组织 ID 和防重幂等键执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在 MySQL 默认 REPEATABLE READ 隔离级别下，事务内普通快照读无法看到其他并发事务后提交的行；
     * 当并发插入发生 DuplicateKeyException 竞态时，必须使用当前读穿透快照读 Read View 盲区，
     * 获取最新已提交事件记录以实现安全重放或冲突判定。
     * </p>
     *
     * @param orgId          组织 ID
     * @param idempotencyKey 客户端幂等键
     * @return 追溯事件实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} AND is_deleted = 0 FOR UPDATE")
    TraceEvent selectByOrgIdAndIdempotencyKeyForUpdate(
            @Param("orgId") Long orgId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 根据指向的原事件 ID 查询是否已存在更正记录。
     *
     * @param correctsEventId 被更正的目标事件 ID
     * @return 更正记录实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE corrects_event_id = #{correctsEventId} AND is_deleted = 0")
    TraceEvent selectByCorrectsEventId(@Param("correctsEventId") Long correctsEventId);

    /**
     * 根据指向的原事件 ID 执行当前锁定读 (SELECT ... FOR UPDATE)。
     * <p>
     * 在捕获 DuplicateKeyException 后的冲突诊断中，穿透 MySQL REPEATABLE READ 快照读盲区，
     * 准确获取其他并发事务已提交的更正记录，精准识别 409 EVENT_ALREADY_CORRECTED，杜绝误报 500。
     * </p>
     *
     * @param correctsEventId 被更正的目标事件 ID
     * @return 更正记录实体；若不存在则返回 null
     */
    @Select("SELECT * FROM trace_event WHERE corrects_event_id = #{correctsEventId} AND is_deleted = 0 FOR UPDATE")
    TraceEvent selectByCorrectsEventIdForUpdate(@Param("correctsEventId") Long correctsEventId);

    /**
     * 原子将原事件状态从 SUBMITTED 更新为 CORRECTED 并递增版本号。
     * <p>
     * 显式限定 id、batch_id 与 org_id 三重隔离，确保即使并发或异常情况下也绝不可能跨批次或跨企业误改事件状态。
     * </p>
     *
     * @param id        被更正的事件 ID
     * @param batchId   关联批次 ID
     * @param orgId     所属企业组织 ID
     * @param updatedBy 操作人用户 ID
     * @param updatedAt 更新时间 (UTC)
     * @return 受影响行数 (应为 1)
     */
    @Update("UPDATE trace_event SET status = 'CORRECTED', version = version + 1, updated_at = #{updatedAt}, updated_by = #{updatedBy} WHERE id = #{id} AND batch_id = #{batchId} AND org_id = #{orgId} AND status = 'SUBMITTED' AND is_deleted = 0")
    int updateStatusToCorrected(
            @Param("id") Long id,
            @Param("batchId") Long batchId,
            @Param("orgId") Long orgId,
            @Param("updatedBy") Long updatedBy,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * 消费者公开投影专用：批量读取一组批次（目标批次及其祖先）的有效公开事件。
     * <p>
     * 只返回生效版本（{@code status = 'SUBMITTED'}、未逻辑删除），更正前的 CORRECTED 历史版本不会出现；
     * 只取公开投影与服务端稳定排序所需的列（id、batch_id、event_type、occurred_at、recorded_at、data_source），
     * 从不装载 summary、details_json、操作人、组织、场所、幂等键或更正原因；事件类型限定为调用方传入的公开白名单。
     * 单条 SQL 覆盖整条谱系，避免逐个祖先查询。
     * </p>
     *
     * @param batchIds   目标批次及其祖先批次 ID（非空）
     * @param eventTypes 公开事件类型白名单（非空）
     * @return 有效公开事件（未排序语义由调用方决定）
     */
    @Select("<script>" +
            "SELECT id, batch_id, event_type, occurred_at, recorded_at, data_source FROM trace_event " +
            "WHERE status = 'SUBMITTED' AND is_deleted = 0 " +
            "AND batch_id IN <foreach collection='batchIds' item='bid' open='(' separator=',' close=')'>#{bid}</foreach> " +
            "AND event_type IN <foreach collection='eventTypes' item='t' open='(' separator=',' close=')'>#{t}</foreach> " +
            "ORDER BY occurred_at ASC, recorded_at ASC, id ASC" +
            "</script>")
    List<TraceEvent> selectEffectivePublicEventsByBatchIds(
            @Param("batchIds") Collection<Long> batchIds,
            @Param("eventTypes") Collection<String> eventTypes
    );

    /**
     * 查询指定批次下全部追溯事件（含已更正历史版本），供当前责任组织读取完整时间线。
     *
     * @param batchId 批次 ID
     * @return 稳定排序的追溯事件列表
     */
    @Select("SELECT * FROM trace_event WHERE batch_id = #{batchId} AND is_deleted = 0 ORDER BY occurred_at ASC, recorded_at ASC, id ASC")
    List<TraceEvent> selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 统计某组织在指定批次下记录的追溯事件数（历史参与组织只读判定）。
     *
     * @param batchId 批次 ID
     * @param orgId   组织 ID
     * @return 事件数
     */
    @Select("SELECT COUNT(*) FROM trace_event WHERE batch_id = #{batchId} AND org_id = #{orgId} AND is_deleted = 0")
    int countByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);
}
