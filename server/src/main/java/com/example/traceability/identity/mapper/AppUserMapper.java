package com.example.traceability.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.identity.domain.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问接口。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
