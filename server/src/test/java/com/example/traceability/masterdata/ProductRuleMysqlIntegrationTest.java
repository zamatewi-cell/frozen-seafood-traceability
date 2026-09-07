package com.example.traceability.masterdata;

import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.masterdata.dto.ProductCreateRequest;
import com.example.traceability.masterdata.dto.ProductPatchRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleStageCreateRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的产品主数据与温控规则全流程端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 条件控制。
 * 运行时动态创建临时测试主体与产品数据，测试结束后执行严格物理清理，不植入任何静态演示数据。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class ProductRuleMysqlIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdRuleIds = new ArrayList<>();
    private final List<Long> createdStageIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long stageId : createdStageIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule_stage WHERE id = ?", stageId);
        }
        createdStageIds.clear();

        for (Long ruleId : createdRuleIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule_stage WHERE rule_id = ?", ruleId);
            jdbcTemplate.update("DELETE FROM temperature_rule WHERE id = ?", ruleId);
        }
        createdRuleIds.clear();

        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        createdProductIds.clear();

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
    @DisplayName("真实 MySQL 8.4 环境下产品主数据与分阶段温控规则全链路端到端闭环测试")
    void fullProductAndRuleLifecycleOnRealMysql() throws Exception {
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 1. 建立测试组织与角色
        Organization org = new Organization();
        org.setOrgNo("ORG_IT_" + randomSuffix);
        org.setName("集成测试企业_" + randomSuffix);
        org.setOrgType("SOURCE");
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        org.setVersion(0L);
        org.setCreatedAt(nowUtc);
        org.setUpdatedAt(nowUtc);
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());

        // 平台管理角色 (PLATFORM)
        Role adminRole = new Role();
        adminRole.setRoleCode("ADMIN_" + randomSuffix);
        adminRole.setName("平台管理员角色");
        adminRole.setScopeType("PLATFORM");
        adminRole.setStatus("ACTIVE");
        adminRole.setIsDeleted(0);
        adminRole.setVersion(0L);
        adminRole.setCreatedAt(nowUtc);
        adminRole.setUpdatedAt(nowUtc);
        roleMapper.insert(adminRole);
        createdRoleIds.add(adminRole.getId());

        // 普通企业操作员角色 (ORG_ONLY)
        Role opRole = new Role();
        opRole.setRoleCode("OPERATOR_" + randomSuffix);
        opRole.setName("企业操作员角色");
        opRole.setScopeType("ORG_ONLY");
        opRole.setStatus("ACTIVE");
        opRole.setIsDeleted(0);
        opRole.setVersion(0L);
        opRole.setCreatedAt(nowUtc);
        opRole.setUpdatedAt(nowUtc);
        roleMapper.insert(opRole);
        createdRoleIds.add(opRole.getId());

        // 平台管理员用户
        String adminUsername = "admin_" + randomSuffix;
        String rawPassword = "Password_" + randomSuffix + "!@#";
        AppUser adminUser = new AppUser();
        adminUser.setOrgId(org.getId());
        adminUser.setUsername(adminUsername);
        adminUser.setDisplayName("测试管理员");
        adminUser.setPasswordHash(passwordEncoder.encode(rawPassword));
        adminUser.setStatus("ACTIVE");
        adminUser.setIsDeleted(0);
        adminUser.setVersion(0L);
        adminUser.setCreatedAt(nowUtc);
        adminUser.setUpdatedAt(nowUtc);
        appUserMapper.insert(adminUser);
        createdUserIds.add(adminUser.getId());

        UserRole urAdmin = new UserRole();
        urAdmin.setUserId(adminUser.getId());
        urAdmin.setRoleId(adminRole.getId());
        urAdmin.setCreatedAt(nowUtc);
        userRoleMapper.insert(urAdmin);

        // 普通操作员用户
        String opUsername = "operator_" + randomSuffix;
        AppUser opUser = new AppUser();
        opUser.setOrgId(org.getId());
        opUser.setUsername(opUsername);
        opUser.setDisplayName("测试操作员");
        opUser.setPasswordHash(passwordEncoder.encode(rawPassword));
        opUser.setStatus("ACTIVE");
        opUser.setIsDeleted(0);
        opUser.setVersion(0L);
        opUser.setCreatedAt(nowUtc);
        opUser.setUpdatedAt(nowUtc);
        appUserMapper.insert(opUser);
        createdUserIds.add(opUser.getId());

        UserRole urOp = new UserRole();
        urOp.setUserId(opUser.getId());
        urOp.setRoleId(opRole.getId());
        urOp.setCreatedAt(nowUtc);
        userRoleMapper.insert(urOp);

        // 2. 真实登录建立会话
        MockHttpSession adminSession = login(adminUsername, rawPassword);
        MockHttpSession opSession = login(opUsername, rawPassword);

        // 3. 越权测试：普通操作员尝试创建产品 -> 403 ACCESS_DENIED
        String productCode = "PRD_IT_" + randomSuffix;
        ProductCreateRequest prodReq = new ProductCreateRequest(
                productCode, "野生大黄鱼_" + randomSuffix, "Larimichthys crocea",
                "FISH", "500g真空包装", "DOMESTIC_CAPTURE", "kg", "ACTIVE"
        );

        mockMvc.perform(post("/api/v1/products")
                        .session(opSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 4. 平台管理员创建产品 -> 201 CREATED
        MvcResult createProdResult = mockMvc.perform(post("/api/v1/products")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productCode").value(productCode))
                .andExpect(jsonPath("$.data.version").value(0L))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andReturn();

        JsonNode prodJson = objectMapper.readTree(createProdResult.getResponse().getContentAsString()).get("data");
        Long productId = prodJson.get("id").asLong();
        createdProductIds.add(productId);

        // 5. 编码冲突测试：再次使用相同 productCode 创建 -> 409 PRODUCT_CODE_CONFLICT
        mockMvc.perform(post("/api/v1/products")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_CONFLICT"));

        // 6. 乐观锁更新测试：
        // 6.1 使用正确版本 0 更新 -> 200 OK，版本变为 1
        ProductPatchRequest patchOk = new ProductPatchRequest(
                0L, null, "野生大黄鱼_改名_" + randomSuffix, null, null, null, null, null, null
        );
        mockMvc.perform(patch("/api/v1/products/" + productId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchOk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicName").value("野生大黄鱼_改名_" + randomSuffix))
                .andExpect(jsonPath("$.data.version").value(1L));

        // 6.2 再次携带旧版本 0 更新 -> 409 VERSION_CONFLICT
        ProductPatchRequest patchOld = new ProductPatchRequest(
                0L, null, "旧版本再次修改", null, null, null, null, null, null
        );
        mockMvc.perform(patch("/api/v1/products/" + productId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchOld)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 7. 温控规则草稿创建与版本号自增：
        // 7.1 创建规则草稿 v1: 生效区间 [2026-01-01, 2026-06-01)
        OffsetDateTime from1 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to1 = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        TemperatureRuleCreateRequest ruleReq1 = new TemperatureRuleCreateRequest(
                "上半年温控基准v1", from1, to1, "依据GB行业标准",
                List.of(
                        new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"), "CELSIUS", 0, 1),
                        new TemperatureRuleStageCreateRequest("TRANSPORT", new BigDecimal("-22.00"), new BigDecimal("-18.00"), "CELSIUS", 300, 2)
                )
        );

        MvcResult createRule1Result = mockMvc.perform(post("/api/v1/products/" + productId + "/temperature-rules")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ruleReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.stages.length()").value(2))
                .andReturn();

        Long ruleId1 = objectMapper.readTree(createRule1Result.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdRuleIds.add(ruleId1);

        // 7.2 再次创建规则草稿 v2，验证版本号自增为 2：生效区间 [2026-06-01, 2026-12-01)
        OffsetDateTime from2 = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to2 = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        TemperatureRuleCreateRequest ruleReq2 = new TemperatureRuleCreateRequest(
                "下半年温控基准v2", from2, to2, "夏秋季严控标准",
                List.of(
                        new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-28.00"), new BigDecimal("-20.00"), "CELSIUS", 0, 1)
                )
        );

        MvcResult createRule2Result = mockMvc.perform(post("/api/v1/products/" + productId + "/temperature-rules")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ruleReq2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();

        Long ruleId2 = objectMapper.readTree(createRule2Result.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdRuleIds.add(ruleId2);

        // 8. 发布规则 v1 (区间 [2026-01-01, 2026-06-01)) -> 200 OK，断言响应 version=1，数据库真实持久化 version=1
        mockMvc.perform(post("/api/v1/temperature-rules/" + ruleId1 + "/publish")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(1));

        Long dbVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM temperature_rule WHERE id = ?", Long.class, ruleId1);
        assertThat(dbVersion).isEqualTo(1L);

        // 9. 重复发布已是 ACTIVE 的规则 v1 -> 409 INVALID_STATE_TRANSITION
        mockMvc.perform(post("/api/v1/temperature-rules/" + ruleId1 + "/publish")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 10. 创建冲突草稿 v3: [2026-05-01, 2026-08-01) 与已生效的 v1 [2026-01-01, 2026-06-01) 重叠
        TemperatureRuleCreateRequest conflictRuleReq = new TemperatureRuleCreateRequest(
                "冲突规则v3",
                OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                "重叠测试",
                List.of(new TemperatureRuleStageCreateRequest("RETAIL", new BigDecimal("-20.00"), new BigDecimal("-15.00"), "CELSIUS", 0, 1))
        );

        MvcResult conflictResult = mockMvc.perform(post("/api/v1/products/" + productId + "/temperature-rules")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictRuleReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(3))
                .andReturn();

        Long ruleId3 = objectMapper.readTree(conflictResult.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdRuleIds.add(ruleId3);

        // 尝试发布冲突规则 v3 -> 422 TEMPERATURE_RULE_EFFECTIVE_CONFLICT
        mockMvc.perform(post("/api/v1/temperature-rules/" + ruleId3 + "/publish")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPERATURE_RULE_EFFECTIVE_CONFLICT"));

        // 11. 发布相邻规则 v2: [2026-06-01, 2026-12-01) 与 v1 [2026-01-01, 2026-06-01) 相邻，无重叠 -> 200 OK
        mockMvc.perform(post("/api/v1/temperature-rules/" + ruleId2 + "/publish")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 12. 查询产品规则列表（已登录用户），返回全部规则及其阶段，确保不泄漏 isDeleted
        mockMvc.perform(get("/api/v1/products/" + productId + "/temperature-rules")
                        .session(opSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data[0].stages[0].isDeleted").doesNotExist());

        // 13. 验证 Flyway V2 物理 CHECK 约束拦截能力 (绕过应用层直插数据库，断言抛出包含对应约束名的 DataAccessException)
        // 13.1 非法 product.category
        DataAccessException ex1 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO product (product_code, public_name, specification, category, source_type, base_unit_code, status, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, '500g', 'INVALID_CAT', 'IMPORT', 'kg', 'DRAFT', 0, 0, NOW(), 1, NOW(), 1)
                        """, "TEST-CK-" + UUID.randomUUID().toString().substring(0, 8), "非法品类测试")
        );
        assertThat(ex1.getMessage()).contains("chk_product_category");

        // 13.2 非法 product.base_unit_code (非 kg)
        DataAccessException ex2 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO product (product_code, public_name, specification, category, source_type, base_unit_code, status, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, '500g', 'FISH', 'IMPORT', 'g', 'DRAFT', 0, 0, NOW(), 1, NOW(), 1)
                        """, "TEST-CK-" + UUID.randomUUID().toString().substring(0, 8), "非法单位测试")
        );
        assertThat(ex2.getMessage()).contains("chk_product_base_unit_code");

        // 13.3 非法 temperature_rule_stage.lower_limit > upper_limit
        DataAccessException ex3 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO temperature_rule_stage (rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'STORAGE', -10.00, -20.00, 'CELSIUS', 0, 1, 0, 0, NOW(), 1, NOW(), 1)
                        """, ruleId1)
        );
        assertThat(ex3.getMessage()).contains("chk_stage_limits");

        // 13.4 非法 temperature_rule_stage.unit_code (非 CELSIUS)
        DataAccessException ex4 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO temperature_rule_stage (rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'STORAGE', -25.00, -18.00, 'FAHRENHEIT', 0, 1, 0, 0, NOW(), 1, NOW(), 1)
                        """, ruleId1)
        );
        assertThat(ex4.getMessage()).contains("chk_stage_unit_code");

        // 13.5 非法 temperature_rule_stage.allowed_duration_seconds < 0
        DataAccessException ex5 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO temperature_rule_stage (rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'STORAGE', -25.00, -18.00, 'CELSIUS', -1, 1, 0, 0, NOW(), 1, NOW(), 1)
                        """, ruleId1)
        );
        assertThat(ex5.getMessage()).containsAnyOf("chk_stage_duration", "allowed_duration_seconds");

        // 13.6 非法 temperature_rule_stage.sequence_no < 1
        DataAccessException ex6 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO temperature_rule_stage (rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no, version, is_deleted, created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'STORAGE', -25.00, -18.00, 'CELSIUS', 0, 0, 0, 0, NOW(), 1, NOW(), 1)
                        """, ruleId1)
        );
        assertThat(ex6.getMessage()).containsAnyOf("chk_stage_sequence", "sequence_no");
    }

    private MockHttpSession login(String username, String password) throws Exception {
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
