package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.Recall;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟召回案件 Mapper（Phase B PB5）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：案件只允许插入（发起）与带状态 / 版本谓词的关闭，不提供删除或任意字段改写。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface RecallMapper {

    /**
     * 发起召回（状态 IN_PROGRESS）；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO recall
                (recall_no, owner_org_id, source_alert_id, reason, status, started_at, started_by,
                 idempotency_key, request_hash, version, created_at, updated_at)
            VALUES
                (#{recallNo}, #{ownerOrgId}, #{sourceAlertId}, #{reason}, 'IN_PROGRESS', #{startedAt}, #{startedBy},
                 #{idempotencyKey}, #{requestHash}, 0, #{startedAt}, #{startedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Recall recall);

    @Select("SELECT * FROM recall WHERE id = #{id}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Recall selectById(@Param("id") Long id);

    /**
     * 排他锁定召回行（关闭的串行化点）。
     */
    @Select("SELECT * FROM recall WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Recall selectByIdForUpdate(@Param("id") Long id);

    /**
     * 组织内发起幂等键查询（预读与持锁后复读）；每次真正查询数据库。
     */
    @Select("SELECT * FROM recall WHERE owner_org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Recall selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 组织内关闭幂等键查询（预读与持锁后复读）；每次真正查询数据库。
     */
    @Select("SELECT * FROM recall WHERE owner_org_id = #{orgId} AND close_idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Recall selectByOrgIdAndCloseIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 可见召回列表（按主键倒序，最多 200 条）：平台只读角色看全部；企业看本组织发起的召回、本组织<b>当前</b>负责其范围批次的召回
     * （召回通知随批次交接转给新的当前责任组织），以及本组织在召回发起时持有范围批次的召回（历史快照，只读）。
     * 同时计算查看关系 viewer_relation：OWNER / CURRENT_HOLDER / HISTORICAL_HOLDER / PLATFORM。
     */
    @Select("""
            <script>
            SELECT r.*,
            <choose>
              <when test="platform">'PLATFORM'</when>
              <otherwise>
                CASE WHEN r.owner_org_id = #{orgId} THEN 'OWNER'
                     WHEN EXISTS (SELECT 1 FROM recall_batch rb JOIN batch b ON b.id = rb.batch_id
                                  WHERE rb.recall_id = r.id AND b.org_id = #{orgId}) THEN 'CURRENT_HOLDER'
                     ELSE 'HISTORICAL_HOLDER' END
              </otherwise>
            </choose> AS viewer_relation
            FROM recall r
            WHERE 1 = 1
            <if test="!platform">
              AND (r.owner_org_id = #{orgId}
                   OR EXISTS (SELECT 1 FROM recall_batch rb WHERE rb.recall_id = r.id AND rb.holder_org_id = #{orgId})
                   OR EXISTS (SELECT 1 FROM recall_batch rb JOIN batch b ON b.id = rb.batch_id
                              WHERE rb.recall_id = r.id AND b.org_id = #{orgId}))
            </if>
            ORDER BY r.id DESC
            LIMIT 200
            </script>
            """)
    List<Recall> selectVisible(@Param("orgId") Long orgId, @Param("platform") boolean platform);

    /**
     * IN_PROGRESS → CLOSED；状态与版本谓词不满足时影响 0 行。
     */
    @Update("""
            UPDATE recall
            SET status = 'CLOSED',
                closed_at = #{nowUtc},
                closed_by = #{userId},
                public_disposition = #{publicDisposition},
                result_summary = #{resultSummary},
                close_idempotency_key = #{closeKey},
                close_request_hash = #{closeHash},
                version = version + 1,
                updated_at = #{nowUtc}
            WHERE id = #{id}
              AND status = 'IN_PROGRESS'
              AND version = #{expectedVersion}
            """)
    int close(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion, @Param("userId") Long userId,
              @Param("publicDisposition") String publicDisposition, @Param("resultSummary") String resultSummary,
              @Param("closeKey") String closeKey, @Param("closeHash") String closeHash, @Param("nowUtc") LocalDateTime nowUtc);

    /**
     * 使批次进入 RECALLED 的召回（该批次的 RECALL 风险转换所引用的召回；RECALLED 为终态，至多一条）：公开投影的模拟处置结论依据。
     */
    @Select("""
            SELECT r.* FROM recall r
            JOIN batch_risk_transition t ON t.source_recall_id = r.id
            WHERE t.batch_id = #{batchId} AND t.source_type = 'RECALL' AND t.to_status = 'RECALLED'
            ORDER BY t.id DESC
            LIMIT 1
            """)
    Recall selectRecallingCaseByBatchId(@Param("batchId") Long batchId);
}
