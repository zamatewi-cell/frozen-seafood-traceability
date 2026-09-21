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
 * 基于真实 MySQL 8.4 的追溯批次全流程端到端集成测试 (双编号与双状态契约)。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
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
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();

        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule_stage WHERE rule_id IN (SELECT id FROM temperature_rule WHERE product_id = ?)", productId);
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
    @DisplayName("端到端验证 - 双状态双编号、服务端trace生成、external重复不冲突、幂等重试/冲突、生命周期提交流转")
    void testBatchDraftLifecycle_EndToEnd_WithOrgIsolationAndIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Organization orgA = createOrg("ORG_A_" + suffix, "测试远洋渔业A");
        Organization orgB = createOrg("ORG_B_" + suffix, "测试冷链分销B");

        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");

        String rawPassword = "TestPassword123!";
        AppUser userA = createUser(orgA.getId(), "usera_" + suffix, rawPassword);
        bindUserRole(userA.getId(), opRole.getId());

        AppUser userB = createUser(orgB.getId(), "userb_" + suffix, rawPassword);
        bindUserRole(userB.getId(), opRole.getId());

        Product activeProduct = createProduct("PRD_ACT_" + suffix, "东海带鱼", "ACTIVE");
        Product inactiveProduct = createProduct("PRD_INA_" + suffix, "冷冻黄鱼(停用)", "INACTIVE");

        HttpSession sessionA = loginAndGetSession(userA.getUsername(), rawPassword);
        HttpSession sessionB = loginAndGetSession(userB.getUsername(), rawPassword);

        String commonExternalBatchNo = "EXT-BATCH-" + suffix;
        String idempotencyKeyA = "idem-key-A-" + UUID.randomUUID();

        // 1. 尝试关联 INACTIVE 产品创建批次 -> 422 PRODUCT_NOT_ACTIVE
        BatchCreateRequest reqInactive = new BatchCreateRequest(
                commonExternalBatchNo, inactiveProduct.getId(),
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

        // 2. User A 关联 ACTIVE 产品创建批次草稿 -> 201 Created
        BatchCreateRequest reqValid = new BatchCreateRequest(
                commonExternalBatchNo, activeProduct.getId(),
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
                .andExpect(jsonPath("$.data.traceBatchNo").exists())
                .andExpect(jsonPath("$.data.externalBatchNo").value(commonExternalBatchNo))
                .andExpect(jsonPath("$.data.flowStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.batchNo").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();

        JsonNode jsonA = objectMapper.readTree(createResultA.getResponse().getContentAsString());
        Long batchIdA = jsonA.path("data").path("id").asLong();
        String traceBatchNoA = jsonA.path("data").path("traceBatchNo").asText();
        assertThat(traceBatchNoA).startsWith("TB-").hasSize(29);
        createdBatchIds.add(batchIdA);

        // 3. 同组织相同 externalBatchNo 再次创建（不同幂等键）-> 允许成功（不建立唯一冲突）
        String diffIdempotencyKeySameOrg = "idem-key-same-org-" + UUID.randomUUID();
        MvcResult sameOrgSameExternalResult = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", diffIdempotencyKeySameOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.externalBatchNo").value(commonExternalBatchNo))
                .andReturn();
        Long sameOrgBatchId = objectMapper.readTree(sameOrgSameExternalResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(sameOrgBatchId).isNotEqualTo(batchIdA);
        createdBatchIds.add(sameOrgBatchId);

        // 4. 幂等重放：相同幂等键相同载荷 -> 返回原批次
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqValid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(batchIdA))
                .andExpect(jsonPath("$.data.traceBatchNo").value(traceBatchNoA));

        // 5. 幂等冲突：相同幂等键不同 externalBatchNo -> 409 IDEMPOTENCY_KEY_REUSED
        BatchCreateRequest reqDiffExternal = new BatchCreateRequest(
                "EXT-DIFF-" + suffix, activeProduct.getId(),
                new BigDecimal("100.000"), "kg", "DOMESTIC_CAPTURE", "舟山海域",
                LocalDate.now(), null, null, 180
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqDiffExternal)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 6. 跨组织使用相同 externalBatchNo 创建 -> 成功（跨组织不冲突）
        BatchCreateRequest reqOrgB = new BatchCreateRequest(
                commonExternalBatchNo, activeProduct.getId(),
                new BigDecimal("50.000"), "kg", "DOMESTIC_FARMED", "分销仓储中心",
                LocalDate.now(), null, null, 365
        );
        MvcResult createResultB = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionB)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOrgB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.externalBatchNo").value(commonExternalBatchNo))
                .andExpect(jsonPath("$.data.orgId").value(orgB.getId()))
                .andReturn();
        Long batchIdB = objectMapper.readTree(createResultB.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdBatchIds.add(batchIdB);

        // 7. 草稿更新：User A 更新批次 A
        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("150.000"), "EXT-PATCHED-" + suffix, "更新产地文本", null, null, null, 200);
        mockMvc.perform(patch("/api/v1/batches/" + batchIdA)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.externalBatchNo").value("EXT-PATCHED-" + suffix))
                .andExpect(jsonPath("$.data.quantity").value(150.000));

        // 8. 提交流转：User A 提交草稿批次 A (DRAFT+NORMAL -> ACTIVE+NORMAL)
        BatchSubmitRequest submitReq = new BatchSubmitRequest(1L);
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/submit")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.version").value(2));

        // 9. 重复提交已激活批次 -> 409 INVALID_STATE_TRANSITION
        BatchSubmitRequest reSubmitReq = new BatchSubmitRequest(2L);
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/submit")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reSubmitReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("数据库底层物理约束保障 - Flyway V3 既有约束与 V8 双状态/双编号新增约束物理拦截")
    void testFlywayPhysicalCheckConstraints_V3AndV8_EnforcedByDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CHK_" + suffix, "约束测试企业");
        Product product = createProduct("PRD_CHK_" + suffix, "约束测试产品", "ACTIVE");

        Long orgId = org.getId();
        Long productId = product.getId();

        // ------------------ V3 既有物理约束验证 ------------------
        // 1. 非法 batch_type 触发 chk_batch_batch_type
        DataAccessException exType = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-TYPE-1234567890123', 'EXT-1', 'INVALID_TYPE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exType.getMessage()).containsIgnoringCase("chk_batch_batch_type");

        // 2. 非法 origin_type 触发 chk_batch_origin_type
        DataAccessException exOrigin = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-ORIG-1234567890123', 'EXT-2', 'SOURCE', 10.000, 'kg', 'ILLEGAL_ORIGIN', '产地', 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exOrigin.getMessage()).containsIgnoringCase("chk_batch_origin_type");

        // 3. 非法 quantity <= 0 触发 chk_batch_quantity
        DataAccessException exQty = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-QTY-12345678901234', 'EXT-3', 'SOURCE', 0.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exQty.getMessage()).containsIgnoringCase("chk_batch_quantity");

        // 4. 非法 unit_code != 'kg' 触发 chk_batch_unit_code
        DataAccessException exUnit = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-UNIT-1234567890123', 'EXT-4', 'SOURCE', 10.000, 'ton', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exUnit.getMessage()).containsIgnoringCase("chk_batch_unit_code");

        // 5. 非法 shelf_life_days <= 0 触发 chk_batch_shelf_life_days
        DataAccessException exShelfLife = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, shelf_life_days, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-SHELF-123456789012', 'EXT-5', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 0, 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exShelfLife.getMessage()).containsIgnoringCase("chk_batch_shelf_life_days");

        // 6. 同创建组织重复幂等键触发 uk_batch_creation_org_idempotency
        String idempotencyKey = "idem-chk-duplicate-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, flow_status, risk_status)
                VALUES (?, ?, ?, 'TB-CHK-IDEM1-123456789012', 'EXT-6-1', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', ?, 'DRAFT', 'NORMAL')
                """, orgId, orgId, productId, idempotencyKey);
        List<Long> insertedIdemIds = jdbcTemplate.queryForList(
                "SELECT id FROM batch WHERE trace_batch_no = 'TB-CHK-IDEM1-123456789012'", Long.class);
        createdBatchIds.addAll(insertedIdemIds);

        DataAccessException exIdemUnique = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-CHK-IDEM2-123456789012', 'EXT-6-2', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', ?, 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId, idempotencyKey)
        );
        assertThat(exIdemUnique.getMessage()).containsIgnoringCase("uk_batch_creation_org_idempotency");

        // ------------------ V8 新增双状态与双编号物理约束验证 ------------------
        // 7. 非法 flow_status 触发 chk_batch_flow_status
        DataAccessException exFlow = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-FLOW-INVALID-12345678901', 'EXT-7', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'ILLEGAL_FLOW', 'NORMAL')
                        """, orgId, orgId, productId)
        );
        assertThat(exFlow.getMessage()).containsIgnoringCase("chk_batch_flow_status");

        // 8. 非法 risk_status 触发 chk_batch_risk_status
        DataAccessException exRisk = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-RISK-INVALID-12345678901', 'EXT-8', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'ILLEGAL_RISK')
                        """, orgId, orgId, productId)
        );
        assertThat(exRisk.getMessage()).containsIgnoringCase("chk_batch_risk_status");

        // 9. 非法组合 DRAFT + FROZEN 触发 chk_batch_status_combination
        DataAccessException exComboFrozen = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-COMBO-FROZEN-1234567890', 'EXT-9', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'FROZEN')
                        """, orgId, orgId, productId)
        );
        assertThat(exComboFrozen.getMessage()).containsIgnoringCase("chk_batch_status_combination");

        // 10. 非法组合 DRAFT + RECALLED 触发 chk_batch_status_combination
        DataAccessException exComboRecalled = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, 'TB-COMBO-RECALLED-123456789', 'EXT-10', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'RECALLED')
                        """, orgId, orgId, productId)
        );
        assertThat(exComboRecalled.getMessage()).containsIgnoringCase("chk_batch_status_combination");

        // 11. 重复 trace_batch_no 触发唯一键约束 uk_batch_trace_batch_no
        String duplicateTraceNo = "TB-UNIQUE-123456789012345678";
        jdbcTemplate.update("""
                INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                VALUES (?, ?, ?, ?, 'EXT-DUP-1', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                """, orgId, orgId, productId, duplicateTraceNo);

        List<Long> insertedTraceIds = jdbcTemplate.queryForList(
                "SELECT id FROM batch WHERE trace_batch_no = ?", Long.class, duplicateTraceNo);
        createdBatchIds.addAll(insertedTraceIds);

        DataAccessException exTraceUnique = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                        VALUES (?, ?, ?, ?, 'EXT-DUP-2', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                        """, orgId, orgId, productId, duplicateTraceNo)
        );
        assertThat(exTraceUnique.getMessage()).containsIgnoringCase("uk_batch_trace_batch_no");

        // 12. external_batch_no 允许为空 (NULL) 且允许同组织重复
        String commonExtNo = "EXT-REPEATABLE-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, flow_status, risk_status)
                VALUES (?, ?, ?, 'TB-EXT-REP-1-123456789012', ?, 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL'),
                       (?, ?, ?, 'TB-EXT-REP-2-123456789012', ?, 'SOURCE', 15.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL'),
                       (?, ?, ?, 'TB-EXT-NULL-1-123456789012', NULL, 'SOURCE', 20.000, 'kg', 'DOMESTIC_CAPTURE', '产地', 'DRAFT', 'NORMAL')
                """, orgId, orgId, productId, commonExtNo, orgId, orgId, productId, commonExtNo, orgId, orgId, productId);

        List<Long> extBatchIds = jdbcTemplate.queryForList(
                "SELECT id FROM batch WHERE org_id = ? AND (external_batch_no = ? OR external_batch_no IS NULL)",
                Long.class, orgId, commonExtNo);
        createdBatchIds.addAll(extBatchIds);
        assertThat(extBatchIds).hasSize(3);
    }

    @Test
    @DisplayName("平台管理员与企业操作员跨组织权限与读写隔离保障")
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
                "EXT-PL1-" + suffix, product1.getId(),
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
                "EXT-PL2-" + suffix, product2.getId(),
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

        // platformUser (PLATFORM scope) 能够跨组织同时看到两个组织的数据
        mockMvc.perform(get("/api/v1/batches")
                        .session((MockHttpSession) sessionPlatform))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId1 + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.id == " + batchId2 + ")]").exists());

        // 9. 验证写操作拦截：非 OPERATOR 的平台用户不能执行企业写操作（403 ACCESS_DENIED）
        // 9.1 平台用户创建批次草稿 -> 403
        BatchCreateRequest platformCreateReq = new BatchCreateRequest(
                "EXT-PL-ADMIN-" + suffix, product1.getId(),
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
        BatchPatchRequest platformPatchReq = new BatchPatchRequest(0L, new BigDecimal("35.000"), "EXT-PL-UPDATED", "更新产地", null, null, null, null);
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
    @DisplayName("真实 MySQL 事务可见性证据: 在同一 REPEATABLE READ 事务中，当前锁定读 (FOR UPDATE) 穿透快照读")
    void testRepeatableRead_SnapshotIsolationBlindSpot_And_LockingReadBreakthrough() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_RR_" + suffix, "隔离级别证据企业");
        Product product = createProduct("PRD_RR_" + suffix, "隔离级别证据产品", "ACTIVE");

        Long orgId = org.getId();
        Long productId = product.getId();
        String testTraceBatchNo = "TB-RR-" + suffix + "-123456789012";
        String testIdemKey = "idem-rr-key-" + suffix;

        try (Connection connA = jdbcTemplate.getDataSource().getConnection();
             Connection connB = jdbcTemplate.getDataSource().getConnection()) {

            connA.setAutoCommit(false);
            connA.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            try (PreparedStatement stmtA1 = connA.prepareStatement(
                    "SELECT COUNT(*) FROM batch WHERE org_id = ? AND trace_batch_no = ? AND is_deleted = 0")) {
                stmtA1.setLong(1, orgId);
                stmtA1.setString(2, testTraceBatchNo);
                try (ResultSet rsA1 = stmtA1.executeQuery()) {
                    assertThat(rsA1.next()).isTrue();
                    assertThat(rsA1.getInt(1)).isEqualTo(0);
                }
            }

            connB.setAutoCommit(true);
            try (PreparedStatement stmtB = connB.prepareStatement("""
                    INSERT INTO batch (org_id, creation_org_id, product_id, trace_batch_no, external_batch_no, batch_type, quantity, unit_code, origin_type, origin_text, creation_idempotency_key, flow_status, risk_status)
                    VALUES (?, ?, ?, ?, 'EXT-RR', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '舟山海域', ?, 'DRAFT', 'NORMAL')
                    """)) {
                stmtB.setLong(1, orgId);
                stmtB.setLong(2, orgId);
                stmtB.setLong(3, productId);
                stmtB.setString(4, testTraceBatchNo);
                stmtB.setString(5, testIdemKey);
                int inserted = stmtB.executeUpdate();
                assertThat(inserted).isEqualTo(1);
            }

            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM batch WHERE org_id = ? AND trace_batch_no = ?", Long.class, orgId, testTraceBatchNo);
            createdBatchIds.addAll(ids);

            // connA 快照读盲区证据
            try (PreparedStatement stmtA2 = connA.prepareStatement(
                    "SELECT COUNT(*) FROM batch WHERE org_id = ? AND trace_batch_no = ? AND is_deleted = 0")) {
                stmtA2.setLong(1, orgId);
                stmtA2.setString(2, testTraceBatchNo);
                try (ResultSet rsA2 = stmtA2.executeQuery()) {
                    assertThat(rsA2.next()).isTrue();
                    assertThat(rsA2.getInt(1)).isEqualTo(0);
                }
            }

            // connA 当前锁定读穿透盲区
            try (PreparedStatement stmtLock = connA.prepareStatement(
                    "SELECT id, flow_status, risk_status, creation_idempotency_key FROM batch WHERE org_id = ? AND creation_idempotency_key = ? AND is_deleted = 0 FOR UPDATE")) {
                stmtLock.setLong(1, orgId);
                stmtLock.setString(2, testIdemKey);
                try (ResultSet rsLock = stmtLock.executeQuery()) {
                    assertThat(rsLock.next()).isTrue();
                    assertThat(rsLock.getString("flow_status")).isEqualTo("DRAFT");
                    assertThat(rsLock.getString("risk_status")).isEqualTo("NORMAL");
                    assertThat(rsLock.getString("creation_idempotency_key")).isEqualTo(testIdemKey);
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

        String commonExternalBatchNo = "EXT-CONC-" + suffix;
        String idempotencyKey = "idem-conc-key-" + UUID.randomUUID();

        BatchCreateRequest req = new BatchCreateRequest(
                commonExternalBatchNo, product.getId(),
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
        assertThat(batchId1).isEqualTo(batchId2);
        createdBatchIds.add(batchId1);

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
