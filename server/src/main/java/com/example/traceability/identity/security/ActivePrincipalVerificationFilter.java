package com.example.traceability.identity.security;

import com.example.traceability.identity.config.SecurityProblemWriter;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 已登录受保护请求的主体活性复核过滤器 (ActivePrincipalVerificationFilter)。
 * <p>
 * 解决会话建立后，用户或组织被管理端停用或逻辑删除时的即时下线问题。
 * 若复核失败，立即清除 SecurityContext、使 HTTP Session 失效，并返回统一 401 Problem。
 * 避开对匿名端点、健康检查端点及登录接口的重复拦截。
 * </p>
 */
public class ActivePrincipalVerificationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActivePrincipalVerificationFilter.class);

    private final AppUserMapper appUserMapper;
    private final OrganizationMapper organizationMapper;
    private final RoleMapper roleMapper;
    private final SecurityProblemWriter problemWriter;

    public ActivePrincipalVerificationFilter(
            AppUserMapper appUserMapper,
            OrganizationMapper organizationMapper,
            RoleMapper roleMapper,
            SecurityProblemWriter problemWriter
    ) {
        this.appUserMapper = appUserMapper;
        this.organizationMapper = organizationMapper;
        this.roleMapper = roleMapper;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/login")
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.startsWith("/api/v1/samples/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object principalObj = auth.getPrincipal();
            if (principalObj instanceof TraceSecurityPrincipal principal) {
                // 1. 复核用户活性
                AppUser user = appUserMapper.selectById(principal.getUserId());
                if (user == null || (user.getIsDeleted() != null && user.getIsDeleted() != 0) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
                    log.warn("已登录用户活性复核失败: userId={}, username={}, 状态异常或已停用", principal.getUserId(), principal.getUsername());
                    invalidateSessionAndClearContext(request);
                    problemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                            "认证失效", "当前用户已被停用或锁定，会话已终止");
                    return;
                }

                // 2. 复核组织归属一致性（防止管理员变更用户组织后旧会话跨组织越权）
                if (!java.util.Objects.equals(user.getOrgId(), principal.getOrgId())) {
                    log.warn("已登录用户组织归属变更: userId={}, 原orgId={}, 新orgId={}",
                            principal.getUserId(), principal.getOrgId(), user.getOrgId());
                    invalidateSessionAndClearContext(request);
                    problemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                            "安全上下文已变更", "用户所属组织已变更，请重新登录");
                    return;
                }

                // 3. 复核组织活性
                Organization org = organizationMapper.selectById(user.getOrgId());
                if (org == null || (org.getIsDeleted() != null && org.getIsDeleted() != 0) || !"ACTIVE".equalsIgnoreCase(org.getStatus())) {
                    log.warn("已登录用户所属组织活性复核失败: orgId={}, username={}, 组织已停用或删除", user.getOrgId(), principal.getUsername());
                    invalidateSessionAndClearContext(request);
                    problemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                            "组织已被停用", "当前用户所属组织已被停用，会话已终止");
                    return;
                }

                // 4. 复核有效角色与作用域集合一致性（无序 Set 比较，防止角色撤销/替换后旧权限残留）
                List<Role> activeRoles = roleMapper.findActiveRolesByUserId(principal.getUserId());
                if (activeRoles == null || activeRoles.isEmpty()) {
                    log.warn("已登录用户有效角色复核失败: userId={}, 无有效启用角色", principal.getUserId());
                    invalidateSessionAndClearContext(request);
                    problemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                            "角色失效", "当前用户未分配任何有效启用角色，会话已终止");
                    return;
                }

                java.util.Set<String> currentRoleCodes = activeRoles.stream()
                        .map(Role::getRoleCode)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                java.util.Set<String> currentScopes = activeRoles.stream()
                        .map(Role::getScopeType)
                        .filter(s -> s != null && !s.isBlank())
                        .collect(java.util.stream.Collectors.toSet());

                java.util.Set<String> principalRoles = principal.getRoles() != null
                        ? java.util.Set.copyOf(principal.getRoles())
                        : java.util.Set.of();
                java.util.Set<String> principalScopes = principal.getScopes() != null
                        ? java.util.Set.copyOf(principal.getScopes())
                        : java.util.Set.of();

                if (!currentRoleCodes.equals(principalRoles) || !currentScopes.equals(principalScopes)) {
                    log.warn("已登录用户角色或作用域集合发生变更: userId={}, 原roles={}, 新roles={}, 原scopes={}, 新scopes={}",
                            principal.getUserId(), principalRoles, currentRoleCodes, principalScopes, currentScopes);
                    invalidateSessionAndClearContext(request);
                    problemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                            "角色或权限已变更", "用户角色或数据权限作用域已变更，请重新登录");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void invalidateSessionAndClearContext(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 会话可能已被并发失效
            }
        }
    }
}
