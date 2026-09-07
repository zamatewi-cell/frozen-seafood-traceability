package com.example.traceability.identity.security;

import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceUserDetailsServiceTest {

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private OrganizationMapper organizationMapper;

    @Mock
    private RoleMapper roleMapper;

    private TraceUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new TraceUserDetailsService(appUserMapper, organizationMapper, roleMapper);
    }

    @Test
    @DisplayName("用户名为空或空白时抛出 UsernameNotFoundException")
    void blankUsernameThrowsException() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("   "))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("用户在数据库中不存在或已逻辑删除时抛出 UsernameNotFoundException")
    void userNotFoundThrowsException() {
        when(appUserMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nonexistent"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("用户状态为 LOCKED 时抛出 LockedException")
    void lockedUserThrowsException() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("locked_user");
        user.setStatus("LOCKED");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("locked_user"))
                .isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("用户状态为 INACTIVE 时抛出 DisabledException")
    void inactiveUserThrowsException() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("inactive_user");
        user.setStatus("INACTIVE");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("inactive_user"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("所属组织不存在时抛出 DisabledException")
    void organizationNotFoundThrowsException() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOrgId(100L);
        user.setUsername("user_without_org");
        user.setStatus("ACTIVE");
        when(appUserMapper.selectOne(any())).thenReturn(user);
        when(organizationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user_without_org"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("所属组织为停用状态时抛出 DisabledException")
    void organizationInactiveThrowsException() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOrgId(100L);
        user.setUsername("user_org_inactive");
        user.setStatus("ACTIVE");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        Organization org = new Organization();
        org.setId(100L);
        org.setStatus("INACTIVE");
        when(organizationMapper.selectOne(any())).thenReturn(org);

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user_org_inactive"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("用户未分配任何有效角色时抛出 BadCredentialsException")
    void userWithNoActiveRolesThrowsException() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOrgId(100L);
        user.setUsername("no_role_user");
        user.setStatus("ACTIVE");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        Organization org = new Organization();
        org.setId(100L);
        org.setStatus("ACTIVE");
        when(organizationMapper.selectOne(any())).thenReturn(org);

        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("no_role_user"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("正常活跃用户成功加载为 TraceSecurityPrincipal")
    void validUserLoadsSuccessfully() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOrgId(100L);
        user.setUsername("normal_user");
        user.setDisplayName("张三");
        user.setPasswordHash("{noop}Secret123");
        user.setStatus("ACTIVE");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        Organization org = new Organization();
        org.setId(100L);
        org.setOrgNo("ORG_001");
        org.setName("深蓝捕捞集团");
        org.setOrgType("SOURCE");
        org.setStatus("ACTIVE");
        when(organizationMapper.selectOne(any())).thenReturn(org);

        Role role = new Role();
        role.setId(10L);
        role.setRoleCode("OPERATOR");
        role.setName("操作员");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(role));

        UserDetails details = userDetailsService.loadUserByUsername("normal_user");

        assertThat(details).isInstanceOf(TraceSecurityPrincipal.class);
        TraceSecurityPrincipal principal = (TraceSecurityPrincipal) details;
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("normal_user");
        assertThat(principal.getDisplayName()).isEqualTo("张三");
        assertThat(principal.getOrgId()).isEqualTo(100L);
        assertThat(principal.getOrgNo()).isEqualTo("ORG_001");
        assertThat(principal.getOrgName()).isEqualTo("深蓝捕捞集团");
        assertThat(principal.getOrgType()).isEqualTo("SOURCE");
        assertThat(principal.getRoles()).containsExactly("OPERATOR");
        assertThat(principal.getScopes()).containsExactly("ORG_ONLY");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
    }
}
