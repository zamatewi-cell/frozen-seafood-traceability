package com.example.traceability.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.identity.domain.Organization;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组织数据访问接口。
 */
@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {
}
