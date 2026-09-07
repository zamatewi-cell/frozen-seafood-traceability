package com.example.traceability.identity.security;

import com.example.traceability.identity.dto.CurrentUserResponse;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 冷冻海产品溯源系统的安全认证主体。
 * <p>
 * 封装用户、所属组织、角色编码及数据范围作用域，作为服务端所有权限决策的唯一可信上下文。
 * </p>
 */
public class TraceSecurityPrincipal implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String username;
    private final String displayName;
    private String password;
    private final Long orgId;
    private final String orgNo;
    private final String orgName;
    private final String orgType;
    private final List<String> roles;
    private final List<String> scopes;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public TraceSecurityPrincipal(
            Long userId,
            String username,
            String displayName,
            String password,
            Long orgId,
            String orgNo,
            String orgName,
            String orgType,
            List<String> roles,
            List<String> scopes,
            boolean enabled,
            boolean accountNonLocked
    ) {
        this.userId = Objects.requireNonNull(userId, "userId 不能为 null");
        this.username = Objects.requireNonNull(username, "username 不能为 null");
        this.displayName = displayName != null ? displayName : username;
        this.password = password;
        this.orgId = Objects.requireNonNull(orgId, "orgId 不能为 null");
        this.orgNo = Objects.requireNonNull(orgNo, "orgNo 不能为 null");
        this.orgName = Objects.requireNonNull(orgName, "orgName 不能为 null");
        this.orgType = Objects.requireNonNull(orgType, "orgType 不能为 null");
        this.roles = roles != null ? List.copyOf(roles) : List.of();
        this.scopes = scopes != null ? List.copyOf(scopes) : List.of();
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;

        // authorities 包含带 "ROLE_" 前缀的角色名称和原始角色编码，便于 Spring Security @Secured / hasRole
        this.authorities = this.roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getOrgNo() {
        return orgNo;
    }

    public String getOrgName() {
        return orgName;
    }

    public String getOrgType() {
        return orgType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getScopes() {
        return scopes;
    }

    /**
     * 转换为对客户端安全的白名单 CurrentUserResponse 响应对象。
     *
     * @return 当前用户白名单响应 DTO
     */
    public CurrentUserResponse toCurrentUserResponse() {
        return new CurrentUserResponse(
                userId,
                username,
                displayName,
                orgId,
                orgNo,
                orgName,
                orgType,
                roles,
                scopes
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.password = null;
    }
}
