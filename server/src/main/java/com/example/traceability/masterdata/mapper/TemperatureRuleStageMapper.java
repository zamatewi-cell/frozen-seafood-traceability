package com.example.traceability.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.masterdata.domain.TemperatureRuleStage;
import com.example.traceability.masterdata.domain.TemperatureRuleStageMatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 温控规则环节明细持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TemperatureRuleStageMapper extends BaseMapper<TemperatureRuleStage> {

    /**
     * 按产品、环节与测量业务时间匹配已发布（ACTIVE、未删除）规则的环节明细（契约 v1.1 §10.1）。
     * <p>
     * 生效区间为左闭右开 {@code [effective_from, effective_to)}，{@code effective_to} 为空表示长期有效。
     * 规则发布时已在产品行锁下拒绝同一产品 ACTIVE 规则区间重叠，因此正常情况下至多返回一行；
     * 调用方必须把多于一行视为数据完整性错误而不是任选其一。非锁定读，不对规则行加锁。
     * </p>
     */
    @Select("""
            SELECT s.id AS rule_stage_id, r.id AS rule_id, r.name AS rule_name, r.version_no AS rule_version_no,
                   s.stage_code, s.lower_limit, s.upper_limit, s.allowed_duration_seconds
            FROM temperature_rule r
            JOIN temperature_rule_stage s ON s.rule_id = r.id AND s.stage_code = #{stageCode} AND s.is_deleted = 0
            WHERE r.product_id = #{productId}
              AND r.status = 'ACTIVE'
              AND r.is_deleted = 0
              AND r.effective_from <= #{measuredAt}
              AND (r.effective_to IS NULL OR r.effective_to > #{measuredAt})
            ORDER BY r.version_no ASC, s.id ASC
            """)
    List<TemperatureRuleStageMatch> selectApplicableStages(
            @Param("productId") Long productId,
            @Param("stageCode") String stageCode,
            @Param("measuredAt") LocalDateTime measuredAt
    );
}
