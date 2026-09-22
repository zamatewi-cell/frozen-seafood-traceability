package com.example.traceability.identity;

import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的端到端认证与会话集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 条件控制。
 * 运行时动态生成随机密码及临时组织、角色、用户数据，测试完成后自动物理清理，不植入任何硬编码演示账号。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class AuthMysqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private OrganizationMapper organizationMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();

        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", roleId);
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        createdRoleIds.clear();

        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("真实 MySQL: 动态用户全流程登录、会话获取、/me 查询与退出注销")
    void fullAuthenticationLifecycleWithRealDatabase() throws Exception {
        // 1. 生成随机凭据与创建临时测试数据
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        // 2. 匿名获取 CSRF Token 与初始会话
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        JsonNode csrfJson = objectMapper.readTree(csrfResult.getResponse().getContentAsString());
        String csrfToken = csrfJson.get("data").get("token").asText();
        String csrfHeader = csrfJson.get("data").get("headerName").asText();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String initialSessionId = session.getId();

        // 3. 错误密码登录，验证统一返回 401 AUTH_CREDENTIALS_INVALID
        LoginRequest badRequest = new LoginRequest(fixture.user().getUsername(), "IncorrectPassword999!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.status").value(401));

        // 4. 正确密码登录，验证防会话固定及白名单主体数据
        LoginRequest validRequest = new LoginRequest(fixture.user().getUsername(), rawPassword);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(fixture.user().getId()))
                .andExpect(jsonPath("$.data.username").value(fixture.user().getUsername()))
                .andExpect(jsonPath("$.data.orgId").value(fixture.org().getId()))
                .andExpect(jsonPath("$.data.orgNo").value(fixture.org().getOrgNo()))
                .andExpect(jsonPath("$.data.roles[0]").value(fixture.role().getRoleCode()))
                .andExpect(jsonPath("$.data.scopes[0]").value(fixture.role().getScopeType()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();

        MockHttpSession loggedInSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(loggedInSession).isNotNull();
        assertThat(loggedInSession.getId()).isNotEqualTo(initialSessionId);

        // 5. 携带登录会话请求 /me
        mockMvc.perform(get("/api/v1/me").session(loggedInSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(fixture.user().getId()))
                .andExpect(jsonPath("$.data.username").value(fixture.user().getUsername()))
                .andExpect(jsonPath("$.data.orgId").value(fixture.org().getId()))
                .andExpect(jsonPath("$.data.roles[0]").value(fixture.role().getRoleCode()));

        // 6. 成功注销
        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(loggedInSession)
                        .header(csrfHeader, csrfToken))
                .andExpect(status().isNoContent());

        assertThat(loggedInSession.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("真实 MySQL: 用户状态变为 INACTIVE 后，已建立会话再次访问立即失效并返回 401")
    void userDeactivationTerminatesSessionInRealDatabase() throws Exception {
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(fixture.user().getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 正常访问受保护端点
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk());

        // 模拟数据库中管理员停用该用户
        AppUser toDeactivate = new AppUser();
        toDeactivate.setId(fixture.user().getId());
        toDeactivate.setStatus("INACTIVE");
        appUserMapper.updateById(toDeactivate);

        // 再次访问，验证活性复核即刻拦截、会话作废
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("认证失效"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("真实 MySQL: 组织状态变为 INACTIVE 后，已建立会话再次访问立即失效并返回 401")
    void orgDeactivationTerminatesSessionInRealDatabase() throws Exception {
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(fixture.user().getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 正常访问受保护端点
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk());

        // 模拟数据库中管理员停用该组织
        Organization toDeactivate = new Organization();
        toDeactivate.setId(fixture.org().getId());
        toDeactivate.setStatus("INACTIVE");
        organizationMapper.updateById(toDeactivate);

        // 再次访问，验证活性复核即刻拦截、会话作废
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("组织已被停用"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("真实 MySQL: 用户所属组织被管理员调整后，旧会话再次访问立即失效并返回 401")
    void userOrgReassignmentTerminatesSessionInRealDatabase() throws Exception {
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(fixture.user().getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 正常访问受保护端点
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk());

        // 创建第二个合法组织，并将用户改属新组织
        String newOrgSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Organization newOrg = new Organization();
        newOrg.setOrgNo("IT_ORG2_" + newOrgSuffix);
        newOrg.setName("集成测试第二企业_" + newOrgSuffix);
        newOrg.setOrgType("DISTRIBUTOR");
        newOrg.setStatus("ACTIVE");
        newOrg.setIsDeleted(0);
        newOrg.setCreatedAt(LocalDateTime.now());
        newOrg.setUpdatedAt(LocalDateTime.now());
        organizationMapper.insert(newOrg);
        createdOrgIds.add(newOrg.getId());

        AppUser toUpdateOrg = new AppUser();
        toUpdateOrg.setId(fixture.user().getId());
        toUpdateOrg.setOrgId(newOrg.getId());
        appUserMapper.updateById(toUpdateOrg);

        // 再次访问，验证活性复核即刻拦截跨组织越权、旧会话失效并返回 401
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("安全上下文已变更"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("真实 MySQL: 用户有效角色变更后，旧会话再次访问立即失效并返回 401")
    void userRolesChangedTerminatesSessionInRealDatabase() throws Exception {
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(fixture.user().getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk());

        // 从 user_role 中删除关联，模拟权限被管理员调整
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ? AND role_id = ?",
                fixture.user().getId(), fixture.role().getId());

        // 再次访问，验证角色失效拦截
        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("真实 MySQL: 组织目录只返回白名单摘要；Slice 2 起交易对手组织同样只读返回白名单摘要（不含信用代码等字段）")
    void organizationDirectoryReadReturnsWhitelistOnly() throws Exception {
        String rawPassword = generateRandomPassword();
        TestFixture fixture = createTestFixture(rawPassword, "ACTIVE", "ACTIVE");

        String otherSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Organization otherOrg = new Organization();
        otherOrg.setOrgNo("IT_ORG_OTHER_" + otherSuffix);
        otherOrg.setName("集成测试其他企业_" + otherSuffix);
        otherOrg.setOrgType("RETAILER");
        otherOrg.setCreditCode("IT_CREDIT_" + otherSuffix);
        otherOrg.setStatus("ACTIVE");
        otherOrg.setIsDeleted(0);
        otherOrg.setCreatedAt(LocalDateTime.now());
        otherOrg.setUpdatedAt(LocalDateTime.now());
        organizationMapper.insert(otherOrg);
        createdOrgIds.add(otherOrg.getId());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(fixture.user().getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/organizations/" + fixture.org().getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fixture.org().getId()))
                .andExpect(jsonPath("$.data.orgNo").value(fixture.org().getOrgNo()))
                .andExpect(jsonPath("$.data.name").value(fixture.org().getName()))
                .andExpect(jsonPath("$.data.orgType").value("PROCESSOR"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.creditCode").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());

        mockMvc.perform(get("/api/v1/organizations/" + otherOrg.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(otherOrg.getName()))
                .andExpect(jsonPath("$.data.orgType").value("RETAILER"))
                .andExpect(jsonPath("$.data.creditCode").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());

        mockMvc.perform(get("/api/v1/organizations/" + fixture.org().getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private TestFixture createTestFixture(String rawPassword, String userStatus, String orgStatus) {
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. 临时组织
        Organization org = new Organization();
        org.setOrgNo("IT_ORG_" + randomSuffix);
        org.setName("集成测试企业_" + randomSuffix);
        org.setOrgType("PROCESSOR");
        org.setStatus(orgStatus);
        org.setIsDeleted(0);
        org.setCreatedAt(LocalDateTime.now());
        org.setUpdatedAt(LocalDateTime.now());
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());

        // 2. 临时角色
        Role role = new Role();
        role.setRoleCode("IT_ROLE_" + randomSuffix);
        role.setName("集成测试角色_" + randomSuffix);
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        createdRoleIds.add(role.getId());

        // 3. 临时用户
        AppUser user = new AppUser();
        user.setOrgId(org.getId());
        user.setUsername("it_user_" + randomSuffix);
        user.setDisplayName("集成测试用户_" + randomSuffix);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(userStatus);
        user.setIsDeleted(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        appUserMapper.insert(user);
        createdUserIds.add(user.getId());

        // 4. 用户角色关联
        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRole.setCreatedAt(LocalDateTime.now());
        userRoleMapper.insert(userRole);

        return new TestFixture(org, role, user);
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return "Pass#" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record TestFixture(Organization org, Role role, AppUser user) {}
}
