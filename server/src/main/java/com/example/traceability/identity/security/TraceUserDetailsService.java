package com.example.traceability.identity.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于数据库用户、组织与角色的 UserDetailsService 实现。
 * <p>
 * 严格执行多维度安全校验：用户状态、逻辑删除、所属组织状态以及有效角色关联。
 * 内部记录详细告警日志（严禁包含密码），对外由认证框架统一收敛为凭据错误，防止账号探测。
 * </p>
 */
@Service
public class TraceUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(TraceUserDetailsService.class);

    private final AppUserMapper appUserMapper;
    private final OrganizationMapper organizationMapper;
    private final RoleMapper roleMapper;

    public TraceUserDetailsService(
            AppUserMapper appUserMapper,
            OrganizationMapper organizationMapper,
            RoleMapper roleMapper
    ) {
        this.appUserMapper = appUserMapper;
        this.organizationMapper = organizationMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            log.warn("用户认证失败: 用户名为空");
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        // 1. 查询用户（带未删除约束）
        AppUser user = appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>()
                        .eq(AppUser::getUsername, username.trim())
                        .eq(AppUser::getIsDeleted, 0)
        );

        if (user == null) {
            log.warn("用户认证失败: 用户不存在或已被删除, username={}", username);
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        // 2. 检查用户自身状态
        if ("LOCKED".equalsIgnoreCase(user.getStatus())) {
            log.warn("用户认证失败: 用户账户已被锁定, username={}, userId={}", username, user.getId());
            throw new LockedException("用户账户已被锁定");
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            log.warn("用户认证失败: 用户状态非活跃, username={}, userId={}, status={}", username, user.getId(), user.getStatus());
            throw new DisabledException("用户账户不可用");
        }

        // 3. 检查所属组织状态
        Organization org = organizationMapper.selectOne(
                new LambdaQueryWrapper<Organization>()
                        .eq(Organization::getId, user.getOrgId())
                        .eq(Organization::getIsDeleted, 0)
        );

        if (org == null) {
            log.warn("用户认证失败: 所属组织不存在或已被删除, username={}, orgId={}", username, user.getOrgId());
            throw new DisabledException("所属组织不可用");
        }
        if (!"ACTIVE".equalsIgnoreCase(org.getStatus())) {
            log.warn("用户认证失败: 所属组织状态非活跃, username={}, orgId={}, orgStatus={}", username, org.getId(), org.getStatus());
            throw new DisabledException("所属组织已被停用");
        }

        // 4. 查询用户分配的有效角色
        List<Role> activeRoles = roleMapper.findActiveRolesByUserId(user.getId());
        if (activeRoles == null || activeRoles.isEmpty()) {
            log.warn("用户认证失败: 用户无任何有效角色, username={}, userId={}", username, user.getId());
            throw new BadCredentialsException("用户未分配任何有效角色");
        }

        List<String> roleCodes = activeRoles.stream()
                .map(Role::getRoleCode)
                .toList();
        List<String> scopes = activeRoles.stream()
                .map(Role::getScopeType)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        return new TraceSecurityPrincipal(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getPasswordHash(),
                org.getId(),
                org.getOrgNo(),
                org.getName(),
                org.getOrgType(),
                roleCodes,
                scopes,
                true,
                true
        );
    }
}
