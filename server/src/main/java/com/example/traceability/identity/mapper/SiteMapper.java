package com.example.traceability.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.identity.domain.Site;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 场所数据访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface SiteMapper extends BaseMapper<Site> {

    /**
     * 忽略组织范围根据场所 ID 查询场所（用于安全区分 404 与跨组织 403 ORG_SCOPE_DENIED）。
     *
     * @param id 场所 ID
     * @return 场所实体；若不存在则返回 null
     */
    @Select("SELECT * FROM site WHERE id = #{id} AND is_deleted = 0")
    Site selectByIdIgnoreTenant(@Param("id") Long id);
}
