package com.example.traceability.batch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的批次操作、物料平衡与谱系边端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * 验证包含：
 * 1. 标准 PROCESS 操作（600kg + 420kg -> 480kg + 520kg + 20kg损耗）草稿创建与提交、4条笛卡尔积边生成、两套幂等重放；
 * 2. 物料超差（0.002kg > 0.001kg 容差）事务回滚、零边遗留；
 * 3. 自环（422 BATCH_RELATION_SELF_LOOP）与 MySQL 8.4 递归 CTE 环检测（422 BATCH_RELATION_CYCLE）；
 * 4. 业务约束：输出量不匹配、输出已有上游、输入累计超限、非 ACTIVE/跨组织引用拦截；
 * 5. 并发提交幂等防重；
 * 6. Flyway V4 物理 CHECK 约束与组织级唯一索引底层拦截。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class BatchOperationMysqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private BatchMapper batchMapper;

    private final List<Long> createdOperationIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 1. 物理清理所有关联的谱系边 batch_relation
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE parent_batch_id IN (SELECT id FROM batch WHERE org_id = ?) OR child_batch_id IN (SELECT id FROM batch WHERE org_id = ?)", orgId, orgId);
        }
        for (Long opId : createdOperationIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE operation_id = ?", opId);
        }

        // 2. 清理批次操作明细与批次操作主表
        for (Long opId : createdOperationIds) {
            jdbcTemplate.update("DELETE FROM batch_operation_item WHERE operation_id = ?", opId);
            jdbcTemplate.update("DELETE FROM batch_operation WHERE id = ?", opId);
        }
        createdOperationIds.clear();

        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch_operation_item WHERE operation_id IN (SELECT id FROM batch_operation WHERE org_id = ?)", orgId);
            jdbcTemplate.update("DELETE FROM batch_operation WHERE org_id = ?", orgId);
        }

        // 3. 清理测试批次
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();

        // 4. 清理产品主数据
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM temperature_rule_stage WHERE rule_id IN (SELECT id FROM temperature_rule WHERE product_id = ?)", productId);
            jdbcTemplate.update("DELETE FROM temperature_rule WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        createdProductIds.clear();

        // 5. 清理用户与角色关联
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();

        // 6. 清理测试角色
        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", roleId);
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        createdRoleIds.clear();

        // 7. 清理组织
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("端到端验证 - 标准 PROCESS 流程（600kg+420kg -> 480kg+520kg+20kg损耗）、生成4条谱系边、两套幂等当前读重放")
    void testStandardProcessOperation_SuccessWithGenealogyEdgesAndIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_OP_" + suffix, "测试加工企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "Password123!";
        AppUser user = createUser(org.getId(), "oper_" + suffix, rawPassword);
        bindUserRole(user.getId(), opRole.getId());
        Product product = createProduct("PRD_OP_" + suffix, "白蕉海鲈", "ACTIVE");

        HttpSession session = loginAndGetSession(user.getUsername(), rawPassword);

        // 准备 2 个输入批次与 2 个输出批次
        Batch bIn1 = createActiveBatch(org.getId(), product.getId(), "B-IN1-" + suffix, new BigDecimal("600.000"));
        Batch bIn2 = createActiveBatch(org.getId(), product.getId(), "B-IN2-" + suffix, new BigDecimal("420.000"));
        Batch bOut1 = createActiveBatch(org.getId(), product.getId(), "B-OUT1-" + suffix, new BigDecimal("480.000"));
        Batch bOut2 = createActiveBatch(org.getId(), product.getId(), "B-OUT2-" + suffix, new BigDecimal("520.000"));

        String createIdempotencyKey = "op-create-" + UUID.randomUUID();

        // 1. 创建批次操作草稿
        BatchOperationCreateRequest createReq = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "标准深加工测试",
                List.of(
                        new BatchOperationItemRequest("INPUT", bIn1.getId(), new BigDecimal("600.000")),
                        new BatchOperationItemRequest("INPUT", bIn2.getId(), new BigDecimal("420.000")),
                        new BatchOperationItemRequest("OUTPUT", bOut1.getId(), new BigDecimal("480.000")),
                        new BatchOperationItemRequest("OUTPUT", bOut2.getId(), new BigDecimal("520.000")),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("20.000"))
                )
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", createIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.operationType").value("PROCESS"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        Long operationId = createdJson.path("data").path("id").asLong();
        createdOperationIds.add(operationId);

        // 2. 验证创建幂等重放
        mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", createIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(operationId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 3. 提交批次操作
        String submitIdempotencyKey = "op-submit-" + UUID.randomUUID();
        BatchOperationSubmitRequest submitReq = new BatchOperationSubmitRequest(0L);

        MvcResult submitResult = mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", operationId)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", submitIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(operationId))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.relations.length()").value(4))
                .andReturn();

        // 4. 验证数据库物理记录：生成了 4 条谱系边
        Integer relationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_relation WHERE operation_id = ?",
                Integer.class, operationId
        );
        assertThat(relationCount).isEqualTo(4);

        // 5. 验证提交幂等重放
        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", operationId)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", submitIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(operationId))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.relations.length()").value(4));
    }

    @Test
    @DisplayName("物料平衡校验 - 投入产出超差（差值 0.002kg > 0.001kg 容差）事务回滚，状态保持 DRAFT 且零边遗留")
    void testMassBalanceViolation_RollbackWithoutRelations() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_MB_" + suffix, "测试平衡企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        AppUser user = createUser(org.getId(), "op_mb_" + suffix, "Password123!");
        bindUserRole(user.getId(), opRole.getId());
        Product product = createProduct("PRD_MB_" + suffix, "冷冻金枪鱼", "ACTIVE");
        HttpSession session = loginAndGetSession(user.getUsername(), "Password123!");

        // 投入 100.000kg，产出批次声明为 99.998kg，无损耗（差值 0.002kg > 0.001kg）
        Batch bIn = createActiveBatch(org.getId(), product.getId(), "B-MB-IN-" + suffix, new BigDecimal("100.000"));
        Batch bOut = createActiveBatch(org.getId(), product.getId(), "B-MB-OUT-" + suffix, new BigDecimal("99.998"));

        BatchOperationCreateRequest createReq = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "超差测试",
                List.of(
                        new BatchOperationItemRequest("INPUT", bIn.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bOut.getId(), new BigDecimal("99.998"))
                )
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-mb-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        Long opId = objectMapper.readTree(createRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdOperationIds.add(opId);

        // 提交 -> 422 BATCH_MASS_BALANCE_VIOLATION
        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opId)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-mb-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_MASS_BALANCE_VIOLATION"));

        // 物理检查：事务回滚，状态仍为 DRAFT，关系边为 0
        String statusInDb = jdbcTemplate.queryForObject(
                "SELECT status FROM batch_operation WHERE id = ?",
                String.class, opId
        );
        assertThat(statusInDb).isEqualTo("DRAFT");

        Integer relations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_relation WHERE operation_id = ?",
                Integer.class, opId
        );
        assertThat(relations).isEqualTo(0);
    }

    @Test
    @DisplayName("谱系拓扑校验 - 自环（BATCH_RELATION_SELF_LOOP）与 MySQL 8.4 递归 CTE 环检测（BATCH_RELATION_CYCLE）")
    void testGenealogyValidation_SelfLoopAndCycleDetection() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_TOP_" + suffix, "测试拓扑企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        AppUser user = createUser(org.getId(), "op_top_" + suffix, "Password123!");
        bindUserRole(user.getId(), opRole.getId());
        Product product = createProduct("PRD_TOP_" + suffix, "波士顿龙虾", "ACTIVE");
        HttpSession session = loginAndGetSession(user.getUsername(), "Password123!");

        // 1. 自环测试：Batch A 既作为 INPUT 又作为 OUTPUT
        Batch bSelf = createActiveBatch(org.getId(), product.getId(), "B-SELF-" + suffix, new BigDecimal("50.000"));
        BatchOperationCreateRequest selfReq = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "自环测试",
                List.of(
                        new BatchOperationItemRequest("INPUT", bSelf.getId(), new BigDecimal("50.000")),
                        new BatchOperationItemRequest("OUTPUT", bSelf.getId(), new BigDecimal("50.000"))
                )
        );

        // 1.1 自环前置防护：草稿创建阶段禁止同一操作内 batchId 重复引用 -> 400 BAD_REQUEST
        mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-self-create-fail-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selfReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 1.2 自环核心拓扑校验：直接插入包含自环引用的操作草稿数据，提交阶段进行深度防御拦截 -> 422 BATCH_RELATION_SELF_LOOP
        String selfOpNo = "OP-SELF-" + suffix;
        String selfIdemKey = "idem-self-raw-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'REPACK', NOW(), 'DRAFT', ?, 0, 0, NOW(), NOW())",
                org.getId(), selfOpNo, selfIdemKey
        );
        Long selfOpId = jdbcTemplate.queryForObject(
                "SELECT id FROM batch_operation WHERE org_id = ? AND operation_no = ?",
                Long.class, org.getId(), selfOpNo
        );
        createdOperationIds.add(selfOpId);

        jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, normalized_quantity, unit_code, created_at) " +
                        "VALUES (?, ?, 'INPUT', 50.000, 50.000, 'kg', NOW()), (?, ?, 'OUTPUT', 50.000, 50.000, 'kg', NOW())",
                selfOpId, bSelf.getId(), selfOpId, bSelf.getId()
        );

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", selfOpId)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-self-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_RELATION_SELF_LOOP"));

        // 2. 递归 CTE 环检测测试：A -> B, B -> C, 提交 C -> A 应被拦截
        Batch bA = createActiveBatch(org.getId(), product.getId(), "B-CYCLE-A-" + suffix, new BigDecimal("100.000"));
        Batch bB = createActiveBatch(org.getId(), product.getId(), "B-CYCLE-B-" + suffix, new BigDecimal("100.000"));
        Batch bC = createActiveBatch(org.getId(), product.getId(), "B-CYCLE-C-" + suffix, new BigDecimal("100.000"));

        // 步骤 2.1: 操作 1: A -> B 提交
        Long op1Id = createAndSubmitOperation(session, "REPACK", bA.getId(), bB.getId(), new BigDecimal("100.000"));
        createdOperationIds.add(op1Id);

        // 步骤 2.2: 操作 2: B -> C 提交
        Long op2Id = createAndSubmitOperation(session, "REPACK", bB.getId(), bC.getId(), new BigDecimal("100.000"));
        createdOperationIds.add(op2Id);

        // 步骤 2.3: 操作 3: 尝试创建并提交 C -> A
        BatchOperationCreateRequest cycleReq = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "成环测试 C->A",
                List.of(
                        new BatchOperationItemRequest("INPUT", bC.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bA.getId(), new BigDecimal("100.000"))
                )
        );

        MvcResult cycleRes = mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-cycle-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cycleReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long op3Id = objectMapper.readTree(cycleRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdOperationIds.add(op3Id);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", op3Id)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-cycle-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_RELATION_CYCLE"));

        // 验证数据库：操作3 状态仍为 DRAFT，无边写入
        String op3Status = jdbcTemplate.queryForObject("SELECT status FROM batch_operation WHERE id = ?", String.class, op3Id);
        assertThat(op3Status).isEqualTo("DRAFT");
        Integer op3Relations = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_relation WHERE operation_id = ?", Integer.class, op3Id);
        assertThat(op3Relations).isEqualTo(0);
    }

    @Test
    @DisplayName("业务规则校验 - 输出量不匹配、输出已有上游、输入累计超限、非 ACTIVE 与跨组织拦截")
    void testOutputAndInputConstraints() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization orgA = createOrg("ORG_RUL_A_" + suffix, "测试规则企业A");
        Organization orgB = createOrg("ORG_RUL_B_" + suffix, "测试规则企业B");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        AppUser userA = createUser(orgA.getId(), "op_rul_" + suffix, "Password123!");
        bindUserRole(userA.getId(), opRole.getId());
        Product productA = createProduct("PRD_RUL_A_" + suffix, "三文鱼", "ACTIVE");
        HttpSession sessionA = loginAndGetSession(userA.getUsername(), "Password123!");

        // 1. 输出批次声明量与 OUTPUT 数量不匹配拦截
        // bOut 声明 100.000kg，OUTPUT item 请求为 90.000kg
        Batch bIn1 = createActiveBatch(orgA.getId(), productA.getId(), "B-IN-MIS-" + suffix, new BigDecimal("100.000"));
        Batch bOutMismatch = createActiveBatch(orgA.getId(), productA.getId(), "B-OUT-MIS-" + suffix, new BigDecimal("100.000"));

        BatchOperationCreateRequest reqMismatch = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "输出量不符",
                List.of(
                        new BatchOperationItemRequest("INPUT", bIn1.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bOutMismatch.getId(), new BigDecimal("90.000")),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("10.000"))
                )
        );
        Long opMismatchId = createDraftOperation(sessionA, reqMismatch);
        createdOperationIds.add(opMismatchId);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opMismatchId)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-mis-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_OUTPUT_QUANTITY_MISMATCH"));

        // 2. 输出批次已有既有上游拦截
        // 先成功执行并提交 In1 -> OutAlready (100kg -> 100kg)
        Batch bOutAlready = createActiveBatch(orgA.getId(), productA.getId(), "B-OUT-ALR-" + suffix, new BigDecimal("100.000"));
        Long opSuccessId = createAndSubmitOperation(sessionA, "REPACK", bIn1.getId(), bOutAlready.getId(), new BigDecimal("100.000"));
        createdOperationIds.add(opSuccessId);

        // 创建另一个输入批次 In2，试图再次将 bOutAlready 作为输出
        Batch bIn2 = createActiveBatch(orgA.getId(), productA.getId(), "B-IN2-ALR-" + suffix, new BigDecimal("100.000"));
        BatchOperationCreateRequest reqAlreadyUpstream = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "再次作为输出",
                List.of(
                        new BatchOperationItemRequest("INPUT", bIn2.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bOutAlready.getId(), new BigDecimal("100.000"))
                )
        );
        Long opAlreadyId = createDraftOperation(sessionA, reqAlreadyUpstream);
        createdOperationIds.add(opAlreadyId);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opAlreadyId)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-alr-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_OUTPUT_ALREADY_PRODUCED"));

        // 3. 输入批次累计消耗超过声明数量拦截
        // bInLimited 声明 100.000kg。操作 A: 消耗 60kg 成功提交；操作 B: 试图消耗 50kg (60+50 = 110 > 100) -> 拦截
        Batch bInLimited = createActiveBatch(orgA.getId(), productA.getId(), "B-IN-LMT-" + suffix, new BigDecimal("100.000"));
        Batch bOutA = createActiveBatch(orgA.getId(), productA.getId(), "B-OUT-LMT-A-" + suffix, new BigDecimal("60.000"));
        Batch bOutB = createActiveBatch(orgA.getId(), productA.getId(), "B-OUT-LMT-B-" + suffix, new BigDecimal("50.000"));

        Long opAId = createAndSubmitOperation(sessionA, "PROCESS", bInLimited.getId(), bOutA.getId(), new BigDecimal("60.000"));
        createdOperationIds.add(opAId);

        // 创建操作 B 试图消耗 50kg
        BatchOperationCreateRequest reqExcess = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "超额消耗",
                List.of(
                        new BatchOperationItemRequest("INPUT", bInLimited.getId(), new BigDecimal("50.000")),
                        new BatchOperationItemRequest("OUTPUT", bOutB.getId(), new BigDecimal("50.000"))
                )
        );
        Long opBId = createDraftOperation(sessionA, reqExcess);
        createdOperationIds.add(opBId);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opBId)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-exc-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_QUANTITY_EXCEEDED"));

        // 4. 跨组织或非 ACTIVE 批次引用拦截
        Product productB = createProduct("PRD_RUL_B_" + suffix, "大黄鱼", "ACTIVE");
        Batch bOrgB = createActiveBatch(orgB.getId(), productB.getId(), "B-ORGB-" + suffix, new BigDecimal("100.000"));
        Batch bDraft = createBatchWithStatus(orgA.getId(), productA.getId(), "B-DRAFT-" + suffix, new BigDecimal("100.000"), "DRAFT");
        Batch bValidOut = createActiveBatch(orgA.getId(), productA.getId(), "B-OUT-VAL-" + suffix, new BigDecimal("100.000"));

        // 试图引用 Org B 批次
        BatchOperationCreateRequest reqCrossOrg = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "跨组织批次引用",
                List.of(
                        new BatchOperationItemRequest("INPUT", bOrgB.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bValidOut.getId(), new BigDecimal("100.000"))
                )
        );
        Long opCrossOrgId = createDraftOperation(sessionA, reqCrossOrg);
        createdOperationIds.add(opCrossOrgId);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opCrossOrgId)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-cross-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 试图引用处于 DRAFT 状态批次
        BatchOperationCreateRequest reqDraftBatch = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "DRAFT批次引用",
                List.of(
                        new BatchOperationItemRequest("INPUT", bDraft.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bValidOut.getId(), new BigDecimal("100.000"))
                )
        );
        Long opDraftBatchId = createDraftOperation(sessionA, reqDraftBatch);
        createdOperationIds.add(opDraftBatchId);

        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opDraftBatchId)
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-draft-sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));
    }

    @Test
    @DisplayName("并发提交幂等性 - 双并发线程同一 submission_idempotency_key 均成功且谱系边仅生成一次")
    void testConcurrentSubmitIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CONC_" + suffix, "测试并发企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        AppUser user = createUser(org.getId(), "op_conc_" + suffix, "Password123!");
        bindUserRole(user.getId(), opRole.getId());
        Product product = createProduct("PRD_CONC_" + suffix, "南美白对虾", "ACTIVE");
        HttpSession session = loginAndGetSession(user.getUsername(), "Password123!");

        Batch bIn = createActiveBatch(org.getId(), product.getId(), "B-CONC-IN-" + suffix, new BigDecimal("100.000"));
        Batch bOut = createActiveBatch(org.getId(), product.getId(), "B-CONC-OUT-" + suffix, new BigDecimal("100.000"));

        BatchOperationCreateRequest createReq = new BatchOperationCreateRequest(
                "REPACK",
                OffsetDateTime.now(ZoneOffset.UTC),
                "并发提交测试",
                List.of(
                        new BatchOperationItemRequest("INPUT", bIn.getId(), new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", bOut.getId(), new BigDecimal("100.000"))
                )
        );
        Long opId = createDraftOperation(session, createReq);
        createdOperationIds.add(opId);

        String sameSubmitIdempotencyKey = "concurrent-sub-" + UUID.randomUUID();
        BatchOperationSubmitRequest submitReq = new BatchOperationSubmitRequest(0L);

        CountDownLatch startLatch = new CountDownLatch(1);
        CompletableFuture<MvcResult> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opId)
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", sameSubmitIdempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitReq)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opId)
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", sameSubmitIdempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitReq)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.countDown();
        CompletableFuture.allOf(future1, future2).join();

        MvcResult res1 = future1.get();
        MvcResult res2 = future2.get();

        assertThat(res1.getResponse().getStatus()).isEqualTo(200);
        assertThat(res2.getResponse().getStatus()).isEqualTo(200);

        JsonNode json1 = objectMapper.readTree(res1.getResponse().getContentAsString());
        JsonNode json2 = objectMapper.readTree(res2.getResponse().getContentAsString());

        assertThat(json1.path("data").path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(json2.path("data").path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(json1.path("data").path("version").asLong()).isEqualTo(1L);
        assertThat(json2.path("data").path("version").asLong()).isEqualTo(1L);

        // 数据库物理验证：关系边数量恰好为 1，无重复边生成
        Integer relationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_relation WHERE operation_id = ?",
                Integer.class, opId
        );
        assertThat(relationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("底层约束核查 - Flyway V4 物理 CHECK 约束与组织级唯一索引拦截")
    void testFlywayV4PhysicalCheckConstraintsAndIndexes() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CHK_" + suffix, "测试V4物理约束组织");

        // 1. 验证 chk_bo_status：非法状态 'INVALID_STATUS'
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'PROCESS', NOW(), 'INVALID_STATUS', ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-ERR-1-" + suffix, "idem-err-1-" + suffix
        ));

        // 2. 验证 chk_bo_type：非法操作类型 'INVALID_TYPE'
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'INVALID_TYPE', NOW(), 'DRAFT', ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-ERR-2-" + suffix, "idem-err-2-" + suffix
        ));

        // 3. 验证 chk_boi_role：插入非法 item_role
        // 先插入一条合法操作记录用于关联明细
        jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'PROCESS', NOW(), 'DRAFT', ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-CHK-" + suffix, "idem-chk-valid-" + suffix
        );
        Long validOpId = jdbcTemplate.queryForObject(
                "SELECT id FROM batch_operation WHERE org_id = ? AND operation_no = ?",
                Long.class, org.getId(), "OP-CHK-" + suffix
        );
        createdOperationIds.add(validOpId);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, role, quantity, normalized_quantity, unit_code, created_at) " +
                        "VALUES (?, 'INVALID_ROLE', 10.000, 10.000, 'kg', NOW())",
                validOpId
        ));

        // 4. 验证 uk_op_org_idempotency：同组织相同 creation_idempotency_key
        String sameIdemKey = "idem-chk-test-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'PROCESS', NOW(), 'DRAFT', ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-IDEM-1-" + suffix, sameIdemKey
        );
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'PROCESS', NOW(), 'DRAFT', ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-IDEM-2-" + suffix, sameIdemKey
        ));

        // 5. 验证 uk_op_org_submission_idempotency：同组织相同 submission_idempotency_key
        String sameSubKey = "sub-chk-test-" + suffix;
        jdbcTemplate.update(
                "UPDATE batch_operation SET submission_idempotency_key = ? WHERE id = ?",
                sameSubKey, validOpId
        );
        // 新建另一个操作并试图使用相同的 submission_idempotency_key
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, submission_idempotency_key, version, is_deleted, created_at, updated_at) " +
                        "VALUES (?, ?, 'PROCESS', NOW(), 'SUBMITTED', ?, ?, 0, 0, NOW(), NOW())",
                org.getId(), "OP-SUB-2-" + suffix, "idem-sub-2-" + suffix, sameSubKey
        ));
    }

    private Long createDraftOperation(HttpSession session, BatchOperationCreateRequest req) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/batch-operations")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long createAndSubmitOperation(HttpSession session, String operationType, Long inBatchId, Long outBatchId, BigDecimal quantity) throws Exception {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                operationType,
                OffsetDateTime.now(ZoneOffset.UTC),
                "辅助提交操作",
                List.of(
                        new BatchOperationItemRequest("INPUT", inBatchId, quantity),
                        new BatchOperationItemRequest("OUTPUT", outBatchId, quantity)
                )
        );
        Long opId = createDraftOperation(session, req);
        mockMvc.perform(post("/api/v1/batch-operations/{operationId}/submit", opId)
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "sub-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchOperationSubmitRequest(0L))))
                .andExpect(status().isOk());
        return opId;
    }

    private Batch createActiveBatch(Long orgId, Long productId, String batchNo, BigDecimal quantity) {
        return createBatchWithStatus(orgId, productId, batchNo, quantity, "ACTIVE");
    }

    private Batch createBatchWithStatus(Long orgId, Long productId, String batchNo, BigDecimal quantity, String status) {
        Batch batch = new Batch();
        batch.setOrgId(orgId);
        batch.setProductId(productId);
        batch.setBatchNo(batchNo);
        batch.setBatchType("PROCESSING");
        batch.setQuantity(quantity);
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("东海近海");
        batch.setProductionDate(LocalDate.now());
        batch.setShelfLifeDays(180);
        batch.setStatus(status);
        batch.setCreationIdempotencyKey("idem-batch-" + UUID.randomUUID());
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());
        return batch;
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
        org.setOrgType("PROCESS");
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
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, roleCode)
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
