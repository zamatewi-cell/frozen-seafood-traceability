package com.example.traceability.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.identity.domain.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色数据访问接口。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查询指定用户所分配的全部有效角色（启用状态且未逻辑删除）。
     *
     * @param userId 用户 ID
     * @return 有效角色列表
     */
    @Select("""
        SELECT r.id, r.role_code, r.name, r.scope_type, r.status, r.version,
               r.is_deleted, r.created_at, r.created_by, r.updated_at, r.updated_by
        FROM role r
        INNER JOIN user_role ur ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
          AND r.is_deleted = 0
          AND r.status = 'ACTIVE'
    """)
    List<Role> findActiveRolesByUserId(@Param("userId") Long userId);
}
