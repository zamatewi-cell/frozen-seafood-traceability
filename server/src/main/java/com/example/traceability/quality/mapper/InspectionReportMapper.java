package com.example.traceability.quality.mapper;

import com.example.traceability.quality.domain.InspectionReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 批次检验报告 Mapper（追加式；Phase B PB4）。
 * <p>
 * 刻意不继承 MyBatis-Plus {@code BaseMapper}：报告只允许插入与查询，不提供任何修改或删除语句。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface InspectionReportMapper {

    /**
     * 追加一条检验报告；自增主键回填到实体。
     */
    @Insert("""
            INSERT INTO inspection_report
                (batch_id, org_id, submitter_role, transfer_id, alert_id, report_no, institution_name, inspected_at,
                 items_summary, conclusion, data_source, actor_user_id, idempotency_key, request_hash, recorded_at)
            VALUES
                (#{batchId}, #{orgId}, #{submitterRole}, #{transferId}, #{alertId}, #{reportNo}, #{institutionName}, #{inspectedAt},
                 #{itemsSummary}, #{conclusion}, #{dataSource}, #{actorUserId}, #{idempotencyKey}, #{requestHash}, #{recordedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(InspectionReport report);

    /**
     * 组织内幂等键查询（预读与持锁后复读）；每次真正查询数据库。
     */
    @Select("SELECT * FROM inspection_report WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    InspectionReport selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 当前锁定读：并发插入触发唯一键冲突后读取最新已提交的同组织同幂等键报告。
     */
    @Select("SELECT * FROM inspection_report WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    InspectionReport selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 批次全部检验报告（按登记顺序）。
     */
    @Select("SELECT * FROM inspection_report WHERE batch_id = #{batchId} ORDER BY id ASC")
    List<InspectionReport> selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 某组织在批次上提交的检验报告（按登记顺序）。
     */
    @Select("SELECT * FROM inspection_report WHERE batch_id = #{batchId} AND org_id = #{orgId} ORDER BY id ASC")
    List<InspectionReport> selectByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 统计某组织在批次上提交的检验报告数（历史提交组织只读判定）。
     */
    @Select("SELECT COUNT(*) FROM inspection_report WHERE batch_id = #{batchId} AND org_id = #{orgId}")
    int countByBatchIdAndOrgId(@Param("batchId") Long batchId, @Param("orgId") Long orgId);

    /**
     * 关联某告警的该批次最新检验报告（放行依据）。只在已持有批次行锁后调用：报告提交同样先锁批次行，
     * 因此 READ COMMITTED 下的这次读取看到的是放行时刻的最新结论。
     */
    @Select("SELECT * FROM inspection_report WHERE alert_id = #{alertId} AND batch_id = #{batchId} ORDER BY id DESC LIMIT 1")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    InspectionReport selectLatestByAlertIdAndBatchId(@Param("alertId") Long alertId, @Param("batchId") Long batchId);
}
