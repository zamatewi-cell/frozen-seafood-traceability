package com.example.traceability.identity.dto;

import java.util.List;

/**
 * 当前登录用户信息响应体（严格白名单投影）。
 * <p>
 * 仅暴露业务上下文所需安全字段，绝不泄露 passwordHash、creditCode、内部备注等敏感信息。
 * </p>
 *
 * @param userId      用户内部主键
 * @param username    登录用户名
 * @param displayName 用户显示名称
 * @param orgId       所属组织 ID
 * @param orgNo       组织业务编号
 * @param orgName     组织名称
 * @param orgType     组织类型
 * @param roles       用户分配的有效角色编码列表
 * @param scopes      角色所对应的数据权限作用域列表
 */
public record CurrentUserResponse(
        Long userId,
        String username,
        String displayName,
        Long orgId,
        String orgNo,
        String orgName,
        String orgType,
        List<String> roles,
        List<String> scopes
) {
}
