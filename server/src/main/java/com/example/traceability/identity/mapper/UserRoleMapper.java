package com.example.traceability.identity.mapper;

import com.example.traceability.identity.domain.UserRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户与角色关联数据访问接口（复合主键明确 SQL 映射器，不继承 BaseMapper）。
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 显式插入用户与角色关联记录。
     *
     * @param userRole 用户角色关联实体
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO user_role (user_id, role_id, created_at)
        VALUES (#{userRole.userId}, #{userRole.roleId}, #{userRole.createdAt})
    """)
    int insert(@Param("userRole") UserRole userRole);
}
