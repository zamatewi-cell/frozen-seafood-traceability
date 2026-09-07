package com.example.traceability.identity.web;

import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, CurrentUserController.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
class AuthControllerTest {

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.ProductMapper productMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleMapper temperatureRuleMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper temperatureRuleStageMapper;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TraceSecurityPrincipal activePrincipal;
    private AppUser activeDbUser;
    private Organization activeDbOrg;
    private Role activeDbRole;

    @BeforeEach
    void setUp() {
        activePrincipal = new TraceSecurityPrincipal(
                1L,
                "alice",
                "爱丽丝",
                "{noop}Secret123456",
                100L,
                "ORG_001",
                "深蓝捕捞集团",
                "SOURCE",
                List.of("OPERATOR"),
                List.of("ORG_ONLY"),
                true,
                true
        );

        activeDbUser = new AppUser();
        activeDbUser.setId(1L);
        activeDbUser.setUsername("alice");
        activeDbUser.setOrgId(100L);
        activeDbUser.setStatus("ACTIVE");
        activeDbUser.setIsDeleted(0);

        activeDbOrg = new Organization();
        activeDbOrg.setId(100L);
        activeDbOrg.setOrgNo("ORG_001");
        activeDbOrg.setName("深蓝捕捞集团");
        activeDbOrg.setOrgType("SOURCE");
        activeDbOrg.setStatus("ACTIVE");
        activeDbOrg.setIsDeleted(0);

        activeDbRole = new Role();
        activeDbRole.setId(10L);
        activeDbRole.setRoleCode("OPERATOR");
        activeDbRole.setName("操作员");
        activeDbRole.setScopeType("ORG_ONLY");
        activeDbRole.setStatus("ACTIVE");
        activeDbRole.setIsDeleted(0);

        // 默认活性复核通过
        when(appUserMapper.selectById(1L)).thenReturn(activeDbUser);
        when(organizationMapper.selectById(100L)).thenReturn(activeDbOrg);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(activeDbRole));
    }

    // =========================================================================
    // 1. GET /api/v1/auth/csrf 测试
    // =========================================================================

    @Test
    @DisplayName("匿名用户可获取 CSRF Token 并建立会话")
    void getCsrfTokenSuccessfully() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
    }

    // =========================================================================
    // 2. POST /api/v1/auth/login 测试
    // =========================================================================

    @Test
    @DisplayName("登录写请求未提供 CSRF Token 时被拒绝并返回 403 ACCESS_DENIED")
    void loginWithoutCsrfReturnsForbidden() throws Exception {
        LoginRequest req = new LoginRequest("alice", "Secret123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("有效凭据与 CSRF 登录成功，防御会话固定且返回白名单主体")
    void loginSuccessWithSessionFixationProtection() throws Exception {
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(activePrincipal);

        MockHttpSession initialSession = new MockHttpSession();
        String initialSessionId = initialSession.getId();

        LoginRequest req = new LoginRequest("alice", "Secret123456");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .session(initialSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1L))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.displayName").value("爱丽丝"))
                .andExpect(jsonPath("$.data.orgId").value(100L))
                .andExpect(jsonPath("$.data.orgNo").value("ORG_001"))
                .andExpect(jsonPath("$.data.orgName").value("深蓝捕捞集团"))
                .andExpect(jsonPath("$.data.orgType").value("SOURCE"))
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.data.scopes[0]").value("ORG_ONLY"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();

        HttpSession postLoginSession = result.getRequest().getSession(false);
        assertThat(postLoginSession).isNotNull();
        // 验证调用了 SessionAuthenticationStrategy 导致 Session ID 轮换（防会话固定）
        assertThat(postLoginSession.getId()).isNotEqualTo(initialSessionId);
        // 验证 SecurityContext 持久化在 Session 中
        assertThat(postLoginSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNotNull();
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 密码错误")
    void loginFailedWithWrongPasswordReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(activePrincipal);

        LoginRequest req = new LoginRequest("alice", "WrongPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.title").value("用户名或密码错误"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 用户不存在")
    void loginFailedWithUnknownUserReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("nonexistent"))
                .thenThrow(new UsernameNotFoundException("用户名或密码错误"));

        LoginRequest req = new LoginRequest("nonexistent", "AnyPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 用户被锁定")
    void loginFailedWithLockedUserReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("locked_user"))
                .thenThrow(new LockedException("用户账户已被锁定"));

        LoginRequest req = new LoginRequest("locked_user", "AnyPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 用户被停用")
    void loginFailedWithDisabledUserReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("disabled_user"))
                .thenThrow(new DisabledException("用户账户不可用"));

        LoginRequest req = new LoginRequest("disabled_user", "AnyPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 组织停用")
    void loginFailedWithDisabledOrgReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("org_disabled_user"))
                .thenThrow(new DisabledException("所属组织已被停用"));

        LoginRequest req = new LoginRequest("org_disabled_user", "AnyPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录失败统一返回 401 AUTH_CREDENTIALS_INVALID: 用户无有效角色")
    void loginFailedWithNoActiveRoleReturnsGenericError() throws Exception {
        when(userDetailsService.loadUserByUsername("no_role_user"))
                .thenThrow(new BadCredentialsException("用户未分配任何有效角色"));

        LoginRequest req = new LoginRequest("no_role_user", "AnyPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录请求参数校验不通过时返回 400 INVALID_REQUEST: 空白字段")
    void loginWithBlankFieldsReturnsBadRequest() throws Exception {
        LoginRequest req = new LoginRequest("   ", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("登录参数边界校验: 用户名小于 3 字符返回 400")
    void loginWithTooShortUsernameReturnsBadRequest() throws Exception {
        LoginRequest req = new LoginRequest("ab", "Secret123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("username"));
    }

    @Test
    @DisplayName("登录参数边界校验: 用户名超过 64 字符返回 400")
    void loginWithTooLongUsernameReturnsBadRequest() throws Exception {
        LoginRequest req = new LoginRequest("a".repeat(65), "Secret123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("username"));
    }

    @Test
    @DisplayName("登录参数边界校验: 密码小于 8 字符返回 400")
    void loginWithTooShortPasswordReturnsBadRequest() throws Exception {
        LoginRequest req = new LoginRequest("alice", "1234567");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    @DisplayName("登录参数边界校验: 密码超过 128 字符返回 400")
    void loginWithTooLongPasswordReturnsBadRequest() throws Exception {
        LoginRequest req = new LoginRequest("alice", "p".repeat(129));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    @DisplayName("登录参数边界校验: 边界长度 (3 字符用户名, 8 字符密码) 校验通过")
    void loginWithBoundaryValidLengthPassesValidation() throws Exception {
        when(userDetailsService.loadUserByUsername("ali")).thenReturn(activePrincipal);

        LoginRequest req = new LoginRequest("ali", "12345678");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized()) // 密码不匹配业务错误，而非参数校验 400
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"));
    }

    // =========================================================================
    // 3. GET /api/v1/me 与已认证上下文测试
    // =========================================================================

    @Test
    @DisplayName("未登录访问受保护端点 /me 返回 401 AUTH_REQUIRED")
    void unauthenticatedAccessToMeReturnsAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("已登录会话访问 /me 返回白名单主体信息")
    void authenticatedAccessToMeReturnsWhitelistData() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1L))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.displayName").value("爱丽丝"))
                .andExpect(jsonPath("$.data.orgId").value(100L))
                .andExpect(jsonPath("$.data.orgNo").value("ORG_001"))
                .andExpect(jsonPath("$.data.orgName").value("深蓝捕捞集团"))
                .andExpect(jsonPath("$.data.orgType").value("SOURCE"))
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.data.scopes[0]").value("ORG_ONLY"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.creditCode").doesNotExist());
    }

    // =========================================================================
    // 4. POST /api/v1/auth/logout 测试
    // =========================================================================

    @Test
    @DisplayName("未登录直接访问注销接口返回 401 AUTH_REQUIRED")
    void unauthenticatedLogoutReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("已登录注销请求缺少 CSRF 返回 403 ACCESS_DENIED")
    void logoutWithoutCsrfReturnsForbidden() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("已登录且携带 CSRF 成功注销返回 204 并使会话失效")
    void logoutSuccessfullyInvalidatesSession() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();

        // 再次访问受保护资源将返回 401
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // 5. 动态活性复核 (ActivePrincipalVerificationFilter) 测试
    // =========================================================================

    @Test
    @DisplayName("已登录用户被管理员停用后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserDeactivatedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台将该用户停用
        AppUser deactivatedUser = new AppUser();
        deactivatedUser.setId(1L);
        deactivatedUser.setStatus("INACTIVE");
        when(appUserMapper.selectById(1L)).thenReturn(deactivatedUser);

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("认证失效"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户所属组织被停用后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserOrgDeactivatedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台将该用户所属组织停用
        Organization deactivatedOrg = new Organization();
        deactivatedOrg.setId(100L);
        deactivatedOrg.setStatus("INACTIVE");
        when(organizationMapper.selectById(100L)).thenReturn(deactivatedOrg);

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("组织已被停用"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户的全部角色被撤销后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserRolesRevokedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台撤销用户全部有效角色
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("角色失效"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户被逻辑删除后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserDeletedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台逻辑删除该用户（selectById 查无此人）
        when(appUserMapper.selectById(1L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户组织归属被管理员调整后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserOrgReassignedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台将该用户分配到了新组织 (orgId: 200L，原 principal 中为 100L)
        AppUser reassignedUser = new AppUser();
        reassignedUser.setId(1L);
        reassignedUser.setUsername("alice");
        reassignedUser.setOrgId(200L);
        reassignedUser.setStatus("ACTIVE");
        reassignedUser.setIsDeleted(0);
        when(appUserMapper.selectById(1L)).thenReturn(reassignedUser);

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("安全上下文已变更"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户的角色集合发生变更后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserRolesChangedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台调整了用户角色 (原角色为 OPERATOR，新角色变为 AUDITOR)
        Role newRole = new Role();
        newRole.setId(20L);
        newRole.setRoleCode("AUDITOR");
        newRole.setName("审计员");
        newRole.setScopeType("ORG_ONLY");
        newRole.setStatus("ACTIVE");
        newRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(newRole));

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("角色或权限已变更"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录角色的数据权限作用域发生变更后，旧会话再次访问立即 401 AUTH_REQUIRED 并失效")
    void loggedInUserScopeChangedCausesSessionTermination() throws Exception {
        MockHttpSession session = establishLoginSession("alice", "Secret123456");

        // 模拟后台将角色作用域从 ORG_ONLY 修改为 ALL
        Role modifiedScopeRole = new Role();
        modifiedScopeRole.setId(10L);
        modifiedScopeRole.setRoleCode("OPERATOR");
        modifiedScopeRole.setName("操作员");
        modifiedScopeRole.setScopeType("ALL");
        modifiedScopeRole.setStatus("ACTIVE");
        modifiedScopeRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(modifiedScopeRole));

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("角色或权限已变更"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("已登录用户的角色查询返回顺序变化但集合相同时，复核通过正常返回 200")
    void loggedInUserRolesOrderChangeDoesNotInvalidateSession() throws Exception {
        // 创建具有多角色的用户主体
        TraceSecurityPrincipal multiRolePrincipal = new TraceSecurityPrincipal(
                1L, "alice", "爱丽丝", "{noop}Secret123456",
                100L, "ORG_001", "深蓝捕捞集团", "SOURCE",
                List.of("OPERATOR", "WAREHOUSE_KEEPER"),
                List.of("ORG_ONLY", "SITE_ONLY"),
                true, true
        );
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(multiRolePrincipal);

        LoginRequest req = new LoginRequest("alice", "Secret123456");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 数据库返回相反顺序的角色列表
        Role role1 = new Role();
        role1.setId(11L);
        role1.setRoleCode("WAREHOUSE_KEEPER");
        role1.setScopeType("SITE_ONLY");
        role1.setStatus("ACTIVE");
        role1.setIsDeleted(0);

        Role role2 = new Role();
        role2.setId(10L);
        role2.setRoleCode("OPERATOR");
        role2.setScopeType("ORG_ONLY");
        role2.setStatus("ACTIVE");
        role2.setIsDeleted(0);

        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(role1, role2));

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1L));

        assertThat(session.isInvalid()).isFalse();
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private MockHttpSession establishLoginSession(String username, String password) throws Exception {
        when(userDetailsService.loadUserByUsername(username)).thenReturn(activePrincipal);

        LoginRequest req = new LoginRequest(username, password);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }
}
