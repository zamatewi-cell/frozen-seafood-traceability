package com.example.traceability.admin.application;

import com.example.traceability.admin.dto.AdminOverviewResponse;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 系统管理应用服务（仅系统管理员 ROLE_SYS_ADMIN 可访问，只读概览）。
 */
@Service
public class AdminApplicationService {

    private static final String ADMIN_ROLE = "ROLE_SYS_ADMIN";

    private final OrganizationMapper organizationMapper;
    private final AppUserMapper appUserMapper;
    private final RoleMapper roleMapper;

    public AdminApplicationService(
            OrganizationMapper organizationMapper,
            AppUserMapper appUserMapper,
            RoleMapper roleMapper) {
        this.organizationMapper = organizationMapper;
        this.appUserMapper = appUserMapper;
        this.roleMapper = roleMapper;
    }

    public AdminOverviewResponse overview(TraceSecurityPrincipal principal) {
        requireAdmin(principal);

        List<Organization> orgs = organizationMapper.selectList(null);
        List<AppUser> users = appUserMapper.selectList(null);
        List<Role> roles = roleMapper.selectList(null);

        List<AdminOverviewResponse.AdminOrg> orgViews = orgs.stream()
                .sorted(Comparator.comparing(Organization::getId))
                .map(o -> new AdminOverviewResponse.AdminOrg(
                        o.getId(), o.getOrgNo(), o.getName(), o.getOrgType(), o.getStatus()))
                .toList();
        List<AdminOverviewResponse.AdminUser> userViews = users.stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(u -> new AdminOverviewResponse.AdminUser(
                        u.getId(), u.getOrgId(), u.getUsername(), u.getDisplayName(), u.getJobType(), u.getStatus()))
                .toList();
        List<AdminOverviewResponse.AdminRole> roleViews = roles.stream()
                .sorted(Comparator.comparing(Role::getId))
                .map(r -> new AdminOverviewResponse.AdminRole(
                        r.getId(), r.getRoleCode(), r.getName(), r.getScopeType(), r.getStatus()))
                .toList();

        return new AdminOverviewResponse(
                orgViews.size(), userViews.size(), roleViews.size(), orgViews, userViews, roleViews);
    }

    private void requireAdmin(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null || !principal.getRoles().contains(ADMIN_ROLE)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_ONLY",
                    "权限不足", "仅系统管理员可访问管理端接口");
        }
    }
}