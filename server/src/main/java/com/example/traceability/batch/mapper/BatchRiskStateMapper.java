package com.example.traceability.batch.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 批次风险状态唯一写入口（持久层）。
 * <p>
 * 生产代码中写 {@code batch.risk_status} 的语句只允许出现在这里（插入时由实体写入 NORMAL 除外）；
 * 刻意<b>不</b>继承 {@code BaseMapper}，且只允许 {@code BatchRiskService} 注入
 * （{@code RiskStatusWriteContainmentTest} 扫描 src/main 校验）。{@code Batch.riskStatus} 的 MyBatis-Plus
 * 更新策略为 NEVER，通用实体更新无法写入该列。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchRiskStateMapper {

    /**
     * 在批次行锁下原子转换风险状态：只改 risk_status、版本号与更新审计字段，数量、责任组织与流转状态不变。
     * <p>
     * 谓词同时约束 id、当前责任组织、期望的原风险状态、流转状态快照（只允许 ACTIVE / CLOSED）与未删除；
     * 影响行数不为 1 时调用方必须回滚整个转换（含已写入的台账行）。
     * </p>
     *
     * @return 影响行数（1 为成功）
     */
    @Update("""
            UPDATE batch
            SET risk_status = #{toStatus},
                version = version + 1,
                updated_at = #{nowUtc},
                updated_by = #{updatedBy}
            WHERE id = #{id}
              AND org_id = #{orgId}
              AND risk_status = #{fromStatus}
              AND flow_status = #{flowStatus}
              AND flow_status IN ('ACTIVE', 'CLOSED')
              AND is_deleted = 0
            """)
    int transitionRiskStatus(
            @Param("id") Long id,
            @Param("orgId") Long orgId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("flowStatus") String flowStatus,
            @Param("nowUtc") LocalDateTime nowUtc,
            @Param("updatedBy") Long updatedBy
    );
}
