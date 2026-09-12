package com.example.traceability.batch;

import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchPatchRequest;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的追溯批次全流程端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * 运行时动态创建临时测试主体（双组织）、用户角色与产品数据，
 * 验证同批号跨组织复用、列表与详情组织隔离、创建/更新/提交生命周期、幂等防重与 Flyway V3 物理 CHECK 约束。
 * 测试结束后彻底物理清理，不污染静态数据。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class BatchMysqlIntegrationTest {

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
    private ProductMapper productMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 1. 优先按 org_id 彻底物理删除测试组织下的所有 batch（包括接口生成或直接 SQL 插入如 CHK-7-1）
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();

        // 2. 清理产品主数据与关联规则
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule_stage WHERE rule_id IN (SELECT id FROM temperature_rule WHERE product_id = ?)", productId);
            jdbcTemplate.update("DELETE FROM temperature_rule WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        createdProductIds.clear();

        // 3. 清理用户与角色关联、用户
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();

        // 4. 清理测试创建的角色（先清残留 user_role 再删 role）
        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", roleId);
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        createdRoleIds.clear();

        // 5. 最后清理组织
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("端到端验证 - 双组织隔离、批号复用、幂等防重、草稿增量更新、ACTIVE提交流转全流程")
    void testBatchDraftLifecycle_EndToEnd_WithOrgIsolationAndIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. 动态创建组织 A 与 组织 B
        Organization orgA = createOrg("ORG_A_" + suffix, "测试远洋渔业A");
        Organization orgB = createOrg("ORG_B_" + suffix, "测试冷链分销B");

        // 2. 动态获取或创建角色 OPERATOR (role_code 必须恰为 OPERATOR)
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");

        // 3. 动态创建用户 User A (Org A, OPERATOR) 与 User B (Org B, OPERATOR)
        String rawPassword = "TestPassword123!";
        AppUser userA = createUser(orgA.getId(), "usera_" + suffix, rawPassword);
        bindUserRole(userA.getId(), opRole.getId());

        AppUser userB = createUser(orgB.getId(), "userb_" + suffix, rawPassword);
        bindUserRole(userB.getId(), opRole.getId());

        // 4. 动态创建 ACTIVE 产品与 INACTIVE 产品
        Product activeProduct = createProduct("PRD_ACT_" + suffix, "东海带鱼", "ACTIVE");
        Product inactiveProduct = createProduct("PRD_INA_" + suffix, "冷冻黄鱼(停用)", "INACTIVE");

        // 5. 登录 User A 与 User B 获取真实 Session
        HttpSession sessionA = loginAndGetSession(userA.getUsername(), rawPassword);
        HttpSession sessionB = loginAndGetSession(userB.getUsername(), rawPassword);

        String commonBatchNo = "BATCH-SN-" + suffix;
        String idempotencyKeyA = "idem-key-A-" + UUID.randomUUID();

        // 6. User A 尝试关联 INACTIVE 产品创建批次 -> 422 PRODUCT_NOT_ACTIVE
        BatchCreateRequest reqInactive = new BatchCreateRequest(
                commonBatchNo, inactiveProduct.getId(), "SOURCE",
                new BigDecimal("100.000"), "kg", "DOMESTIC_CAPTURE", "舟山海域",
                LocalDate.now(), null, null, 180
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqInactive)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"));

        // 7. User A 关联 ACTIVE 产品创建批次草稿 -> 201 Created
        BatchCreateRequest reqValid = new BatchCreateRequest(
                commonBatchNo, activeProduct.getId(), "SOURCE",
                new BigDecimal("100.000"), "kg", "DOMESTIC_CAPTURE", "舟山海域",
                LocalDate.now(), null, null, 180
        );
        MvcResult createResultA = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.batchNo").value(commonBatchNo))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.creationIdempotencyKey").doesNotExist())
                .andReturn();

        JsonNode jsonA = objectMapper.readTree(createResultA.getResponse().getContentAsString());
        Long batchIdA = jsonA.path("data").path("id").asLong();
        createdBatchIds.add(batchIdA);

        // 7.1 覆盖关键幂等重放语义：将关联产品修改为 INACTIVE，验证相同幂等键 + 相同载荷依然成功重放原批次并返回原 ID (201 Created)
        jdbcTemplate.update("UPDATE product SET status = 'INACTIVE' WHERE id = ?", activeProduct.getId());
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(batchIdA));

        // 恢复产品状态为 ACTIVE，保证后续流程顺利进行
        jdbcTemplate.update("UPDATE product SET status = 'ACTIVE' WHERE id = ?", activeProduct.getId());

        // 8. User A 相同批号重复创建 -> 409 BATCH_NO_CONFLICT
        String diffIdempotencyKey = "idem-key-diff-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", diffIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NO_CONFLICT"));

        // 9. User A 相同 Idempotency-Key 相同语义重试 -> 返回原批次 (ID 相同)
        MvcResult retryResultA = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(batchIdA))
                .andReturn();

        // 10. User A 相同 Idempotency-Key 不同语义 -> 409 IDEMPOTENCY_CONFLICT
        BatchCreateRequest reqConflictPayload = new BatchCreateRequest(
                commonBatchNo, activeProduct.getId(), "SOURCE",
                new BigDecimal("999.000"), "kg", "DOMESTIC_CAPTURE", "舟山海域",
                LocalDate.now(), null, null, 180
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqConflictPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 11. User B (不同组织) 使用相同批次号创建批次 -> 201 Created (验证同批号跨组织复用)
        BatchCreateRequest reqOrgB = new BatchCreateRequest(
                commonBatchNo, activeProduct.getId(), "DISTRIBUTION",
                new BigDecimal("50.000"), "kg", "DOMESTIC_FARMED", "分销仓储中心",
                LocalDate.now(), null, null, 365
        );
        MvcResult createResultB = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionB)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA) // 复用相同 key 亦允许跨组织独立
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOrgB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.batchNo").value(commonBatchNo))
                .andExpect(jsonPath("$.data.orgId").value(orgB.getId()))
                .andReturn();

        JsonNode jsonB = objectMapper.readTree(createResultB.getResponse().getContentAsString());
        Long batchIdB = jsonB.path("data").path("id").asLong();
        createdBatchIds.add(batchIdB);

        // 12. 列表数据隔离验证：User A 列表只能看到 Org A 的批次，绝看不到 Org B 的批次
        mockMvc.perform(get("/api/v1/batches")
                        .session((MockHttpSession) sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].orgId").value(orgA.getId()));

        // 13. 详情跨组织访问验证：User B 访问 Org A 的批次返回 403 ORG_SCOPE_DENIED
        mockMvc.perform(get("/api/v1/batches/" + batchIdA)
                        .session((MockHttpSession) sessionB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 14. 草稿增量更新：User A 更新批次 A
        // 错误 version -> 409 VERSION_CONFLICT
        BatchPatchRequest patchWrongVersion = new BatchPatchRequest(999L, new BigDecimal("120.000"), "修改后产地", null, null, null, null);
        mockMvc.perform(patch("/api/v1/batches/" + batchIdA)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchWrongVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确 version=0 -> 200 OK, version 递增为 1
        BatchPatchRequest patchCorrect = new BatchPatchRequest(0L, new BigDecimal("150.000"), "更新产地文本", null, null, null, 200);
        mockMvc.perform(patch("/api/v1/batches/" + batchIdA)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchCorrect)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.quantity").value(150.000))
                .andExpect(jsonPath("$.data.originText").value("更新产地文本"));

        // 15. 提交流转：User A 提交草稿批次 A (DRAFT -> ACTIVE)
        BatchSubmitRequest submitReq = new BatchSubmitRequest(1L);
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/submit")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(2));

        // 16. 重复提交已激活批次 -> 409 INVALID_STATE_TRANSITION
        BatchSubmitRequest reSubmitReq = new BatchSubmitRequest(2L);
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/submit")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reSubmitReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 17. 再次 PATCH 已激活批次 -> 409 INVALID_STATE_TRANSITION
        BatchPatchRequest patchActive = new BatchPatchRequest(2L, new BigDecimal("160.000"), null, null, null, null, null);
        mockMvc.perform(patch("/api/v1/batches/" + batchIdA)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchActive)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("数据库底层物理约束保障 - Flyway V3 MySQL 8.4 CHECK 约束拦截非法脏数据")
    void testFlywayV3_PhysicalCheckConstraints_EnforcedByDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CHK_" + suffix, "约束测试企业");
        Product product = createProduct("PRD_CHK_" + suffix, "约束测试产品", "ACTIVE");

        Long orgId = org.getId();
        Long productId = product.getId();

        // 1. 非法 batch_type 触发 chk_batch_batch_type
        DataAccessException exType = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, status)
                        VALUES (?, ?, 'CHK-1', 'INVALID_TYPE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exType.getMessage()).containsIgnoringCase("chk_batch_batch_type");

        // 2. 非法 origin_type 触发 chk_batch_origin_type
        DataAccessException exOrigin = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, status)
                        VALUES (?, ?, 'CHK-2', 'SOURCE', 10.000, 'kg', 'ILLEGAL_ORIGIN', '产地', 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exOrigin.getMessage()).containsIgnoringCase("chk_batch_origin_type");

        // 3. 非法 status 触发 chk_batch_status
        DataAccessException exStatus = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, status)
                        VALUES (?, ?, 'CHK-3', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'ILLEGAL_STATUS')
                        """, orgId, productId)
        );
        assertThat(exStatus.getMessage()).containsIgnoringCase("chk_batch_status");

        // 4. 非法 quantity <= 0 触发 chk_batch_quantity
        DataAccessException exQty = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, status)
                        VALUES (?, ?, 'CHK-4', 'SOURCE', 0.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exQty.getMessage()).containsIgnoringCase("chk_batch_quantity");

        // 5. 非法 unit_code != 'kg' 触发 chk_batch_unit_code
        DataAccessException exUnit = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, status)
                        VALUES (?, ?, 'CHK-5', 'SOURCE', 10.000, 'ton', 'DOMESTIC_CAPTURE', '产地', 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exUnit.getMessage()).containsIgnoringCase("chk_batch_unit_code");

        // 6. 非法 shelf_life_days <= 0 触发 chk_batch_shelf_life_days
        DataAccessException exShelfLife = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, shelf_life_days, status)
                        VALUES (?, ?, 'CHK-6', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 0, 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exShelfLife.getMessage()).containsIgnoringCase("chk_batch_shelf_life_days");

        // 7. 同组织重复幂等键触发 uk_batch_org_idempotency
        jdbcTemplate.update("""
                INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, status)
                VALUES (?, ?, 'CHK-7-1', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'idem-chk-duplicate', 'DRAFT')
                """, orgId, productId);

        // 登记直插的 batch ID，双重确保 tearDown 清理彻底
        List<Long> insertedIds = jdbcTemplate.queryForList(
                "SELECT id FROM batch WHERE org_id = ? AND batch_no = 'CHK-7-1'",
                Long.class, orgId
        );
        createdBatchIds.addAll(insertedIds);

        DataAccessException exIdem = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, status)
                        VALUES (?, ?, 'CHK-7-2', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'idem-chk-duplicate', 'DRAFT')
                        """, orgId, productId)
        );
        assertThat(exIdem.getMessage()).containsIgnoringCase("uk_batch_org_idempotency");
    }

    @Test
    @DisplayName("真实 MySQL: PLATFORM scope 用户跨组织读列表，普通企业用户读隔离，平台非 OPERATOR 用户写操作被拒绝 (403)")
    void testPlatformUser_CrossOrgRead_And_OperatorWriteAccessDenied() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. 创建两个独立企业组织
        Organization org1 = createOrg("ORG_PL_1_" + suffix, "平台验证企业1");
        Organization org2 = createOrg("ORG_PL_2_" + suffix, "平台验证企业2");

        // 2. 获取或创建角色 (企业操作员 role_code 为 OPERATOR，平台管理员为 SYS_ADMIN 具备 PLATFORM scope)
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        Role sysAdminRole = getOrCreateRole("SYS_ADMIN", "系统管理员", "PLATFORM");

        // 3. 创建企业操作员用户与平台管理员用户
        String rawPassword = "TestPassword123!";
        AppUser opUser1 = createUser(org1.getId(), "op1_" + suffix, rawPassword);
        bindUserRole(opUser1.getId(), opRole.getId());

        AppUser opUser2 = createUser(org2.getId(), "op2_" + suffix, rawPassword);
        bindUserRole(opUser2.getId(), opRole.getId());

        AppUser platformUser = createUser(org1.getId(), "sysadmin_" + suffix, rawPassword);
        bindUserRole(platformUser.getId(), sysAdminRole.getId());

        // 4. 创建 ACTIVE 产品
        Product product1 = createProduct("PRD_PL1_" + suffix, "大黄鱼1", "ACTIVE");
        Product product2 = createProduct("PRD_PL2_" + suffix, "大黄鱼2", "ACTIVE");

        // 5. 登录获取各用户的 Session
        HttpSession sessionOp1 = loginAndGetSession(opUser1.getUsername(), rawPassword);
        HttpSession sessionOp2 = loginAndGetSession(opUser2.getUsername(), rawPassword);
        HttpSession sessionPlatform = loginAndGetSession(platformUser.getUsername(), rawPassword);

        // 6. 企业用户 1 创建组织 1 批次
        BatchCreateRequest req1 = new BatchCreateRequest(
                "BATCH-PL1-" + suffix, product1.getId(), "SOURCE",
                new BigDecimal("10.000"), "kg", "DOMESTIC_CAPTURE", "舟山1区",
                LocalDate.now(), null, null, 100
        );
        MvcResult res1 = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionOp1)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-pl-1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();
        Long batchId1 = objectMapper.readTree(res1.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdBatchIds.add(batchId1);

        // 7. 企业用户 2 创建组织 2 批次
        BatchCreateRequest req2 = new BatchCreateRequest(
                "BATCH-PL2-" + suffix, product2.getId(), "DISTRIBUTION",
                new BigDecimal("20.000"), "kg", "DOMESTIC_FARMED", "分销2区",
                LocalDate.now(), null, null, 200
        );
        MvcResult res2 = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionOp2)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-pl-2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        Long batchId2 = objectMapper.readTree(res2.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdBatchIds.add(batchId2);

        // 8. 验证列表读隔离：
        // opUser1 只能看到 org1 的数据，看不到 org2 的数据
        mockMvc.perform(get("/api/v1/batches")
                        .session((MockHttpSession) sessionOp1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId1 + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId2 + ")]").doesNotExist());

        // opUser2 只能看到 org2 的数据，看不到 org1 的数据
        mockMvc.perform(get("/api/v1/batches")
                        .session((MockHttpSession) sessionOp2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId2 + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId1 + ")]").doesNotExist());

        // platformUser (PLATFORM scope) 能够同时看到两个组织的数据
        mockMvc.perform(get("/api/v1/batches")
                        .session((MockHttpSession) sessionPlatform))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId1 + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId2 + ")]").exists());

        // 9. 验证写操作拦截：非 OPERATOR 的平台用户不能执行企业写操作（403 ACCESS_DENIED）
        // 9.1 平台用户创建批次草稿 -> 403
        BatchCreateRequest platformCreateReq = new BatchCreateRequest(
                "BATCH-PL-ADMIN-" + suffix, product1.getId(), "SOURCE",
                new BigDecimal("30.000"), "kg", "DOMESTIC_CAPTURE", "产地说明",
                LocalDate.now(), null, null, 100
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionPlatform)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-pl-admin-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(platformCreateReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 9.2 平台用户更新批次草稿 -> 403
        BatchPatchRequest platformPatchReq = new BatchPatchRequest(0L, new BigDecimal("35.000"), "更新产地", null, null, null, null);
        mockMvc.perform(patch("/api/v1/batches/" + batchId1)
                        .session((MockHttpSession) sessionPlatform)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(platformPatchReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 9.3 平台用户提交激活批次草稿 -> 403
        BatchSubmitRequest platformSubmitReq = new BatchSubmitRequest(0L);
        mockMvc.perform(post("/api/v1/batches/" + batchId1 + "/submit")
                        .session((MockHttpSession) sessionPlatform)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(platformSubmitReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("真实 MySQL 事务可见性证据: 在同一 REPEATABLE READ 事务中，普通快照读看不到并发提交，当前锁定读 (FOR UPDATE) 穿透快照读读取最新数据")
    void testRepeatableRead_SnapshotIsolationBlindSpot_And_LockingReadBreakthrough() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_RR_" + suffix, "隔离级别证据企业");
        Product product = createProduct("PRD_RR_" + suffix, "隔离级别证据产品", "ACTIVE");

        Long orgId = org.getId();
        Long productId = product.getId();
        String testBatchNo = "BATCH-RR-" + suffix;
        String testIdemKey = "idem-rr-key-" + suffix;

        // 打开两个物理连接：connA 模拟当前长事务 (REPEATABLE READ)，connB 模拟并发外部事务
        try (Connection connA = jdbcTemplate.getDataSource().getConnection();
             Connection connB = jdbcTemplate.getDataSource().getConnection()) {

            connA.setAutoCommit(false);
            connA.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            // 1. connA 执行初始普通快照读，建立该事务的 Read View (快照读视图)
            try (PreparedStatement stmtA1 = connA.prepareStatement(
                    "SELECT COUNT(*) FROM batch WHERE org_id = ? AND batch_no = ? AND is_deleted = 0")) {
                stmtA1.setLong(1, orgId);
                stmtA1.setString(2, testBatchNo);
                try (ResultSet rsA1 = stmtA1.executeQuery()) {
                    assertThat(rsA1.next()).isTrue();
                    assertThat(rsA1.getInt(1)).isEqualTo(0);
                }
            }

            // 2. connB 并发插入一条批次记录并立即提交
            connB.setAutoCommit(true);
            try (PreparedStatement stmtB = connB.prepareStatement("""
                    INSERT INTO batch (org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, status)
                    VALUES (?, ?, ?, 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '舟山海域', ?, 'DRAFT')
                    """)) {
                stmtB.setLong(1, orgId);
                stmtB.setLong(2, productId);
                stmtB.setString(3, testBatchNo);
                stmtB.setString(4, testIdemKey);
                int inserted = stmtB.executeUpdate();
                assertThat(inserted).isEqualTo(1);
            }

            // 登记此批次以便 tearDown 清理
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM batch WHERE org_id = ? AND batch_no = ?", Long.class, orgId, testBatchNo);
            createdBatchIds.addAll(ids);

            // 3. connA 在同一事务中再次执行普通快照读
            // 证据 1: REPEATABLE READ 快照读无法看到外部后提交的记录，计数依然为 0 (快照读盲区)
            try (PreparedStatement stmtA2 = connA.prepareStatement(
                    "SELECT COUNT(*) FROM batch WHERE org_id = ? AND batch_no = ? AND is_deleted = 0")) {
                stmtA2.setLong(1, orgId);
                stmtA2.setString(2, testBatchNo);
                try (ResultSet rsA2 = stmtA2.executeQuery()) {
                    assertThat(rsA2.next()).isTrue();
                    assertThat(rsA2.getInt(1)).isEqualTo(0);
                }
            }

            // 4. connA 执行当前锁定读 (SELECT ... FOR UPDATE)
            // 证据 2: 当前读直接读取存储引擎最新已提交行，穿透快照读盲区，成功读取到并发提交的数据！
            try (PreparedStatement stmtLock = connA.prepareStatement(
                    "SELECT id, status, creation_idempotency_key FROM batch WHERE org_id = ? AND creation_idempotency_key = ? AND is_deleted = 0 FOR UPDATE")) {
                stmtLock.setLong(1, orgId);
                stmtLock.setString(2, testIdemKey);
                try (ResultSet rsLock = stmtLock.executeQuery()) {
                    assertThat(rsLock.next()).isTrue();
                    assertThat(rsLock.getString("status")).isEqualTo("DRAFT");
                    assertThat(rsLock.getString("creation_idempotency_key")).isEqualTo(testIdemKey);
                }
            }

            // 5. connA 执行业务批次号的当前锁定读
            try (PreparedStatement stmtLockBatchNo = connA.prepareStatement(
                    "SELECT id, batch_no FROM batch WHERE org_id = ? AND batch_no = ? AND is_deleted = 0 FOR UPDATE")) {
                stmtLockBatchNo.setLong(1, orgId);
                stmtLockBatchNo.setString(2, testBatchNo);
                try (ResultSet rsLock = stmtLockBatchNo.executeQuery()) {
                    assertThat(rsLock.next()).isTrue();
                    assertThat(rsLock.getString("batch_no")).isEqualTo(testBatchNo);
                }
            }

            connA.commit();
        }
    }

    @Test
    @DisplayName("真实 MySQL 并发幂等双请求: 两个并发请求携带相同幂等键同时提交，只生成一行记录且返回相同批次 ID")
    void testConcurrentIdempotentCreate_DoubleRequests_GeneratesExactlyOneRowAndSameId() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CONC_" + suffix, "并发幂等测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser opUser = createUser(org.getId(), "op_conc_" + suffix, rawPassword);
        bindUserRole(opUser.getId(), opRole.getId());
        Product product = createProduct("PRD_CONC_" + suffix, "并发测试带鱼", "ACTIVE");

        HttpSession session = loginAndGetSession(opUser.getUsername(), rawPassword);

        String commonBatchNo = "BATCH-CONC-" + suffix;
        String idempotencyKey = "idem-conc-key-" + UUID.randomUUID();

        BatchCreateRequest req = new BatchCreateRequest(
                commonBatchNo, product.getId(), "SOURCE",
                new BigDecimal("50.000"), "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180
        );

        CountDownLatch startLatch = new CountDownLatch(1);

        CompletableFuture<MvcResult> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 同时放行两个并发线程
        startLatch.countDown();
        CompletableFuture.allOf(future1, future2).join();

        MvcResult result1 = future1.get();
        MvcResult result2 = future2.get();

        assertThat(result1.getResponse().getStatus()).isEqualTo(201);
        assertThat(result2.getResponse().getStatus()).isEqualTo(201);

        JsonNode json1 = objectMapper.readTree(result1.getResponse().getContentAsString());
        JsonNode json2 = objectMapper.readTree(result2.getResponse().getContentAsString());

        Long batchId1 = json1.path("data").path("id").asLong();
        Long batchId2 = json2.path("data").path("id").asLong();

        assertThat(batchId1).isNotNull();
        assertThat(batchId2).isNotNull();
        // 关键断言: 两个并发请求必须返回同一个批次 ID
        assertThat(batchId1).isEqualTo(batchId2);
        createdBatchIds.add(batchId1);

        // 数据库物理核查: 数据库中该组织和幂等键下恰好只有 1 条记录
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE org_id = ? AND creation_idempotency_key = ?",
                Integer.class, org.getId(), idempotencyKey
        );
        assertThat(count).isEqualTo(1);
    }

    private HttpSession loginAndGetSession(String username, String rawPassword) throws Exception {
        LoginRequest loginRequest = new LoginRequest(username, rawPassword);
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();
        return mvcResult.getRequest().getSession(false);
    }

    private Organization createOrg(String orgNo, String name) {
        Organization org = new Organization();
        org.setOrgNo(orgNo);
        org.setName(name);
        org.setOrgType("SOURCE");
        org.setStatus("ACTIVE");
        org.setVersion(0L);
        org.setIsDeleted(0);
        org.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());
        return org;
    }

    private Role getOrCreateRole(String roleCode, String name, String scopeType) {
        Role existing = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                        .eq(Role::getRoleCode, roleCode)
        );
        if (existing != null) {
            return existing;
        }
        Role role = new Role();
        role.setRoleCode(roleCode);
        role.setName(name);
        role.setScopeType(scopeType);
        role.setStatus("ACTIVE");
        role.setVersion(0L);
        role.setIsDeleted(0);
        role.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        role.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        roleMapper.insert(role);
        createdRoleIds.add(role.getId());
        return role;
    }

    private AppUser createUser(Long orgId, String username, String rawPassword) {
        AppUser user = new AppUser();
        user.setOrgId(orgId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus("ACTIVE");
        user.setVersion(0L);
        user.setIsDeleted(0);
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        appUserMapper.insert(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private void bindUserRole(Long userId, Long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        ur.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRoleMapper.insert(ur);
    }

    private Product createProduct(String productCode, String publicName, String status) {
        Product p = new Product();
        p.setProductCode(productCode);
        p.setPublicName(publicName);
        p.setCategory("FISH");
        p.setSpecification("500g/条");
        p.setSourceType("DOMESTIC_CAPTURE");
        p.setBaseUnitCode("kg");
        p.setStatus(status);
        p.setVersion(0L);
        p.setIsDeleted(0);
        p.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        p.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        productMapper.insert(p);
        createdProductIds.add(p.getId());
        return p;
    }
}
