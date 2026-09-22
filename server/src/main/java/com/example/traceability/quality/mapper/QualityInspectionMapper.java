package com.example.traceability.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.quality.domain.QualityInspection;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质检单持久层访问接口。
 */
@Mapper
public interface QualityInspectionMapper extends BaseMapper<QualityInspection> {
}