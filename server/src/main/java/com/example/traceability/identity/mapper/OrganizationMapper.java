package com.example.traceability.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.identity.domain.Organization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 组织数据访问接口。
 */
@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {

    /**
     * 查询全部启用组织，可按组织类型筛选（按组织编号稳定排序），用于交接接收方与承运方选择。
     *
     * @param orgType 组织类型筛选；null 表示全部类型
     * @return 启用组织列表
     */
    @Select("<script>SELECT * FROM organization WHERE status = 'ACTIVE' AND is_deleted = 0 " +
            "<if test='orgType != null'> AND org_type = #{orgType} </if>" +
            "ORDER BY org_no ASC, id ASC</script>")
    List<Organization> selectActive(@Param("orgType") String orgType);

    /**
     * 按主键批量读取组织（仅供已完成权限校验的调用方补全展示名称）。
     *
     * @param ids 组织 ID 集合
     * @return 组织列表
     */
    @Select("<script>SELECT * FROM organization WHERE is_deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Organization> selectByIdsIgnoreTenant(@Param("ids") Collection<Long> ids);
}
