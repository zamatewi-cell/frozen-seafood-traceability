package com.example.traceability.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.masterdata.domain.TemperatureRuleStage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 温控规则环节明细持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TemperatureRuleStageMapper extends BaseMapper<TemperatureRuleStage> {
}
