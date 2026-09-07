package com.example.traceability.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.masterdata.domain.TemperatureRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 温控规则主表持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TemperatureRuleMapper extends BaseMapper<TemperatureRule> {

    /**
     * 查询指定产品当前已存在的最大规则版本号。
     * 若尚无规则，则返回 0。
     *
     * @param productId 产品ID
     * @return 当前最大规则版本序号
     */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM temperature_rule WHERE product_id = #{productId}")
    Integer findMaxVersionNoByProductId(@Param("productId") Long productId);

    /**
     * 条件更新规则状态为指定状态（仅当当前为 DRAFT 且未逻辑删除时更新，防止并发发布伪装成功）。
     *
     * @param id        规则ID
     * @param newStatus 目标状态
     * @param updatedBy 更新人ID
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    @Update("UPDATE temperature_rule SET status = #{newStatus}, version = version + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE id = #{id} AND status = 'DRAFT' AND is_deleted = 0")
    int updateStatusIfDraft(@Param("id") Long id, @Param("newStatus") String newStatus, @Param("updatedBy") Long updatedBy, @Param("updatedAt") LocalDateTime updatedAt);
}
