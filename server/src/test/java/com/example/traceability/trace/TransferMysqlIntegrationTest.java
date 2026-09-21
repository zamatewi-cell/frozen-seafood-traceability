package com.example.traceability.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchSubmitRequest;
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
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import com.example.traceability.trace.dto.TransferSubmitRequest;
import com.example.traceability.trace.mapper.TransferMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的企业间整批交接端到端集成测试 (FR-TRANSFER-001)。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * 覆盖 Issue #21 验收场景 A~E：
 * <ul>
 *   <li>场景 A：跨企业接收方成功流转（包含批次所有权变更、public_trace_code 组织归属同步转移、双时间留痕、ARRIVAL 追溯事件与审计日志）；</li>
 *   <li>场景 B：交接拒收流转（确认归属不转移、公开追溯码归属不变更且无追溯事件生成）；</li>
 *   <li>场景 C：负向校验（非持有者不可发起、非目标接收方不可决断、终态不可重复决断）；</li>
 *   <li>场景 D：并发与幂等（同 key 重试返回同结果、不同语义 409、触发 uk_transfer_open_batch 唯一约束转换为 409 不报错 500）；</li>
 *   <li>场景 E：批次操作互斥（在途 PENDING 批次被批次操作引用为 INPUT 时被拒绝 409 BATCH_TRANSFER_PENDING）；</li>
 *   <li>场景 F：批次双状态矩阵（仅 flowStatus=ACTIVE 且 riskStatus=NORMAL 可交接）；</li>
 *   <li>同名 externalBatchNo 回归：接收方已存在相同外部批号时 ACCEPT 依然成功，
 *       traceBatchNo 与 externalBatchNo 均保持不变，仅责任组织转移（业务契约 v1.1 §3.2）。</li>
 * </ul>
 * 测试结束后执行严格容错物理清理，保证不留任何脏数据残留。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("企业间整批交接 MySQL 8.4 端到端集成测试")
class TransferMysqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrganizationMapper organizationMapper;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private BatchMapper batchMapper;

    @Autowired
    private TransferMapper transferMapper;

    private final List<Long> createdTransferIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Long> createdOperationIds = new ArrayList<>();

    private Organization senderOrg;
    private Organization receiverOrg;
    private AppUser senderUser;
    private AppUser receiverUser;
    private Product testProduct;
    private MockHttpSession senderSession;
    private MockHttpSession receiverSession;
    private static final String DEFAULT_PASSWORD = "Password123!";

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. 创建发货方与接收方组织
        senderOrg = createOrg("ORG_S_" + suffix, "发货捕捞企业-" + suffix);
        receiverOrg = createOrg("ORG_R_" + suffix, "收货冷链企业-" + suffix);

        // 2. 获取或创建 OPERATOR 角色
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "OWN_ORG");

        // 3. 创建双方操作员并授权
        senderUser = createUser(senderOrg.getId(), "snd_op_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(senderUser.getId(), opRole.getId());

        receiverUser = createUser(receiverOrg.getId(), "rcv_op_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(receiverUser.getId(), opRole.getId());

        // 4. 创建测试产品
        testProduct = createProduct("PRD_" + suffix, "大西洋鳕鱼-" + suffix, "ACTIVE");

        // 5. 登录获取 Session
        senderSession = (MockHttpSession) loginAndGetSession(senderUser.getUsername(), DEFAULT_PASSWORD);
        receiverSession = (MockHttpSession) loginAndGetSession(receiverUser.getUsername(), DEFAULT_PASSWORD);
    }

    @AfterEach
    void tearDown() {
        // 1. 幂等记录与公开追溯码相关表
        cleanTableSafely("DELETE FROM transfer_idempotency WHERE org_id = ?", createdOrgIds);
        cleanTableSafely("DELETE FROM public_trace_code_idempotency WHERE org_id = ?", createdOrgIds);
        cleanTableSafely("DELETE FROM public_trace_code WHERE batch_id = ?", createdBatchIds);

        // 2. 追溯事件（使用 details_json 中的 transferId 与 batch_id 关联清理）
        cleanTableSafely("DELETE FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR)", createdTransferIds);
        cleanTableSafely("DELETE FROM trace_event WHERE batch_id = ?", createdBatchIds);

        // 3. 批次操作谱系边及操作相关表（在删除 batch_operation/batch 前只按本测试记录的 batch/operation ID 物理删除 batch_relation，不吞异常）
        for (Long bId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE parent_batch_id = ? OR child_batch_id = ?", bId, bId);
        }
        for (Long opId : createdOperationIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE operation_id = ?", opId);
        }
        cleanTableSafely("DELETE FROM batch_operation_item WHERE batch_id = ?", createdBatchIds);
        cleanTableSafely("DELETE FROM batch_operation_item WHERE operation_id = ?", createdOperationIds);
        cleanTableSafely("DELETE FROM batch_operation WHERE org_id = ?", createdOrgIds);
        cleanTableSafely("DELETE FROM batch_operation WHERE id = ?", createdOperationIds);

        // 4. 交接单、批次、产品
        cleanTableSafely("DELETE FROM transfer WHERE id = ?", createdTransferIds);
        cleanTableSafely("DELETE FROM batch WHERE id = ?", createdBatchIds);
        cleanTableSafely("DELETE FROM product WHERE id = ?", createdProductIds);

        // 5. 用户与权限关系
        cleanTableSafely("DELETE FROM user_role WHERE user_id = ?", createdUserIds);
        cleanTableSafely("DELETE FROM user_role WHERE role_id = ?", createdRoleIds);
        cleanTableSafely("DELETE FROM app_user WHERE id = ?", createdUserIds);
        cleanTableSafely("DELETE FROM role WHERE id = ?", createdRoleIds);

        // 6. 审计日志与组织
        cleanTableSafely("DELETE FROM audit_log WHERE actor_org_id = ?", createdOrgIds);
        cleanTableSafely("DELETE FROM organization WHERE id = ?", createdOrgIds);

        // 7. 物理彻底清理断言验证：断言测试产生的全部实体无残留
        assertNoResidualData();

        createdTransferIds.clear();
        createdBatchIds.clear();
        createdProductIds.clear();
        createdUserIds.clear();
        createdRoleIds.clear();
        createdOrgIds.clear();
        createdOperationIds.clear();
    }

    private void cleanTableSafely(String sql, List<Long> ids) {
        for (Long id : ids) {
            try {
                jdbcTemplate.update(sql, id);
            } catch (Exception ignored) {
            }
        }
    }

    private void assertNoResidualData() {
        for (Long orgId : createdOrgIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM organization WHERE id = ?", Integer.class, orgId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE actor_org_id = ?", Integer.class, orgId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfer_idempotency WHERE org_id = ?", Integer.class, orgId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_operation WHERE org_id = ?", Integer.class, orgId)).isEqualTo(0);
        }
        for (Long batchId : createdBatchIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_relation WHERE parent_batch_id = ? OR child_batch_id = ?", Integer.class, batchId, batchId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch WHERE id = ?", Integer.class, batchId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trace_event WHERE batch_id = ?", Integer.class, batchId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, batchId)).isEqualTo(0);
        }
        for (Long opId : createdOperationIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_relation WHERE operation_id = ?", Integer.class, opId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_operation_item WHERE operation_id = ?", Integer.class, opId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_operation WHERE id = ?", Integer.class, opId)).isEqualTo(0);
        }
        for (Long transferId : createdTransferIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfer WHERE id = ?", Integer.class, transferId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR)", Integer.class, transferId)).isEqualTo(0);
        }
        for (Long userId : createdUserIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, userId)).isEqualTo(0);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_role WHERE user_id = ?", Integer.class, userId)).isEqualTo(0);
        }
        for (Long productId : createdProductIds) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE id = ?", Integer.class, productId)).isEqualTo(0);
        }
    }

    /**
     * 创建并提交一个 ACTIVE + NORMAL 批次。
     * <p>
     * {@code externalBatchNo} 为企业可选外部业务批号（可重复、不承担身份），
     * traceBatchNo 一律由服务端生成，客户端不得指定。
     * </p>
     */
    private Long createAndSubmitActiveBatch(MockHttpSession session, String externalBatchNo, BigDecimal quantity) throws Exception {
        return createAndSubmitActiveBatch(
                session,
                externalBatchNo,
                quantity,
                "idem-bat-c-" + UUID.randomUUID().toString().replace("-", "")
        );
    }

    /**
     * 创建并提交一个 ACTIVE + NORMAL 批次，允许显式指定创建幂等键。
     * <p>
     * 用于验证创建幂等域为不可变的 creationOrgId：不同组织可以合法持有完全相同的
     * creation_idempotency_key，且不会因此阻断 Transfer ACCEPT。
     * </p>
     */
    private Long createAndSubmitActiveBatch(
            MockHttpSession session,
            String externalBatchNo,
            BigDecimal quantity,
            String idemCreate
    ) throws Exception {
        BatchCreateRequest createReq = new BatchCreateRequest(
                externalBatchNo, testProduct.getId(), "SOURCE",
                quantity, "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180
        );

        MvcResult res = mockMvc.perform(post("/api/v1/batches")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idemCreate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString()).get("data");
        Long batchId = node.get("id").asLong();
        createdBatchIds.add(batchId);

        String idemSubmit = "idem-bat-s-" + UUID.randomUUID().toString().replace("-", "");
        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/submit")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idemSubmit)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk());

        return batchId;
    }

    @Test
    @DisplayName("场景 A：跨企业整批交接成功流转（批次所有权转移、公开追溯码归属同步变更、双时间留痕、追溯事件与审计日志）")
    void scenarioA_crossEnterpriseTransfer_accept_fullLifecycle() throws Exception {
        BigDecimal qty = new BigDecimal("500.000");
        String externalBatchNo = "BAT-SCENARIO-A-" + UUID.randomUUID().toString().substring(0, 6);
        Long batchId = createAndSubmitActiveBatch(senderSession, externalBatchNo, qty);

        // 1. 发货方为批次激活公开追溯码
        String activateKey = "idem-act-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", activateKey))
                .andExpect(status().isOk());

        // 校验底层激活后的公开追溯码组织归属为发货方
        Long ptcOrgBefore = jdbcTemplate.queryForObject(
                "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId
        );
        assertThat(ptcOrgBefore).isEqualTo(senderOrg.getId());

        // 2. 发货方创建交接草稿
        String createKey = "idem-create-" + UUID.randomUUID().toString().replace("-", "");
        TransferCreateRequest createReq = new TransferCreateRequest(batchId, receiverOrg.getId());
        MvcResult createRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.quantity").value(500.0))
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("data");
        Long transferId = createNode.get("id").asLong();
        createdTransferIds.add(transferId);

        // 幂等重放验证
        mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(transferId));

        // 3. 发货方提交交接
        OffsetDateTime shippedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        String submitKey = "idem-submit-" + UUID.randomUUID().toString().replace("-", "");
        TransferSubmitRequest submitReq = new TransferSubmitRequest(shippedAt, 0L);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", submitKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 4. 接收方确认接受（存在 1.5kg 合理干耗）
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String acceptKey = "idem-accept-" + UUID.randomUUID().toString().replace("-", "");
        TransferAcceptRequest acceptReq = new TransferAcceptRequest(
                new BigDecimal("498.500"), "kg", receivedAt, "合理干耗1.5kg", 1L
        );

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", acceptKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.receivedQuantity").value(498.5))
                .andExpect(jsonPath("$.data.differenceReason").value("合理干耗1.5kg"))
                .andExpect(jsonPath("$.data.version").value(2));

        // 5. 真实数据库校验：批次持有组织原子转移到接收方，且原 batch.quantity 快照保持不变
        Batch updatedBatch = batchMapper.selectById(batchId);
        assertThat(updatedBatch.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(updatedBatch.getQuantity()).isEqualByComparingTo(qty);
        assertThat(updatedBatch.getVersion()).isEqualTo(2L);

        // 6. 真实数据库校验：公开追溯码的 org_id 同步原子转移到接收方
        Long ptcOrgAfter = jdbcTemplate.queryForObject(
                "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId
        );
        assertThat(ptcOrgAfter).isEqualTo(receiverOrg.getId());

        // 7. 校验双时间独立留痕
        Transfer finalTransfer = transferMapper.selectById(transferId);
        assertThat(finalTransfer.getShippedAt()).isNotNull();
        assertThat(finalTransfer.getSubmittedRecordedAt()).isNotNull();
        assertThat(finalTransfer.getReceivedAt()).isNotNull();
        assertThat(finalTransfer.getDecisionRecordedAt()).isNotNull();
        assertThat(finalTransfer.getDecidedBy()).isEqualTo(receiverUser.getId());

        // 8. 校验追溯事件中记录了 ARRIVAL 事件，且关联 details_json.transferId 正确
        int arrivalEventsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                Integer.class, transferId
        );
        assertThat(arrivalEventsCount).isEqualTo(1);

        // 9. 校验审计日志生成了发货方提交和接收方接受的记录
        int submitAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'SUBMIT' AND object_type = 'TRANSFER'",
                Integer.class, senderOrg.getId()
        );
        assertThat(submitAuditCount).isGreaterThanOrEqualTo(1);

        int acceptAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER'",
                Integer.class, receiverOrg.getId()
        );
        assertThat(acceptAuditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("场景 B：交接拒收流转（批次所有权不转移、公开追溯码归属不变更、不追加追溯事件）")
    void scenarioB_crossEnterpriseTransfer_reject_preservesOwnershipAndNoEvent() throws Exception {
        BigDecimal qty = new BigDecimal("200.000");
        String externalBatchNo = "BAT-SCENARIO-B-" + UUID.randomUUID().toString().substring(0, 6);
        Long batchId = createAndSubmitActiveBatch(senderSession, externalBatchNo, qty);

        // 1. 发货方激活公开追溯码
        String activateKey = "idem-act-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", activateKey))
                .andExpect(status().isOk());

        // 2. 发货方创建与提交交接单
        String createKey = "idem-create-" + UUID.randomUUID().toString().replace("-", "");
        TransferCreateRequest createReq = new TransferCreateRequest(batchId, receiverOrg.getId());
        MvcResult createRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        Long transferId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        String submitKey = "idem-submit-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", submitKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 3. 接收方拒收交接
        OffsetDateTime rejectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String rejectKey = "idem-reject-" + UUID.randomUUID().toString().replace("-", "");
        TransferRejectRequest rejectReq = new TransferRejectRequest("解冻变质，不予收货", rejectedAt, 1L);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/reject")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", rejectKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("解冻变质，不予收货"));

        // 4. 真实数据库校验：批次所有权依然属于发货方，版本号未递增
        Batch b = batchMapper.selectById(batchId);
        assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());
        assertThat(b.getVersion()).isEqualTo(1L);

        // 5. 公开追溯码归属依然属于发货方
        Long ptcOrg = jdbcTemplate.queryForObject(
                "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId
        );
        assertThat(ptcOrg).isEqualTo(senderOrg.getId());

        // 6. 验证没有追加 ARRIVAL 追溯事件
        int eventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR)",
                Integer.class, transferId
        );
        assertThat(eventCount).isEqualTo(0);

        // 7. 审计日志校验存在拒收记录
        int rejectAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'REJECT' AND object_type = 'TRANSFER'",
                Integer.class, receiverOrg.getId()
        );
        assertThat(rejectAuditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("场景 C：负向校验（非持有者不可发起、非目标接收方不可决断、终态不可重复决断）")
    void scenarioC_negativeValidation_lifecycleAndRbac() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization thirdOrg = createOrg("ORG_T_" + suffix, "第三方无关企业-" + suffix);
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "OWN_ORG");
        AppUser thirdUser = createUser(thirdOrg.getId(), "thd_op_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(thirdUser.getId(), opRole.getId());
        MockHttpSession thirdSession = (MockHttpSession) loginAndGetSession(thirdUser.getUsername(), DEFAULT_PASSWORD);

        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-SCENARIO-C-" + suffix, new BigDecimal("100.000"));

        // 1. 非持有者不可发起交接：接收方尝试发起该批次交接给第三方组织
        String failKey = "idem-fail-c1-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/transfers")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", failKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, thirdOrg.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 发送方正常发起交接并提交为 PENDING
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-ok-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-sbm-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 2. 非目标接收方不可决断：
        // 2.1 第三方企业操作员尝试 ACCEPT
        TransferAcceptRequest accReq = new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(thirdSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-thd-acc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 2.2 发货方自己尝试 ACCEPT
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-snd-acc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 2.3 第三方企业操作员尝试 REJECT
        TransferRejectRequest rejReq = new TransferRejectRequest("非我司货物", OffsetDateTime.now(ZoneOffset.UTC), 1L);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/reject")
                        .session(thirdSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-thd-rej-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 3. 正常决断后，终态不可重复流转：
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-rcv-acc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isOk());

        // 3.1 终态下再次 ACCEPT（使用新幂等键）返回 409
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-rcv-re-acc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 2L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 3.2 终态下尝试 REJECT 返回 409
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/reject")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-rcv-re-rej-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRejectRequest("后悔拒收", OffsetDateTime.now(ZoneOffset.UTC), 2L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("场景 D：并发与幂等（同 key 重试返回同结果、不同语义 409、触发 uk_transfer_open_batch 转换为 409 不报错 500）")
    void scenarioD_concurrencyAndIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-SCENARIO-D-" + suffix, new BigDecimal("350.000"));

        String idemKey = "idem-d-key-" + UUID.randomUUID().toString().replace("-", "");
        TransferCreateRequest createReq = new TransferCreateRequest(batchId, receiverOrg.getId());

        // 1. 首次创建草稿 -> 201 Created
        MvcResult r1 = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId1);

        // 2. 同 key 同语义重放 -> 201 Created，返回完全相同的 transferId
        MvcResult r2 = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(transferId1))
                .andReturn();
        Long transferId2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("data").get("id").asLong();
        assertThat(transferId2).isEqualTo(transferId1);

        // 3. 同 key 不同语义 -> 409 IDEMPOTENCY_CONFLICT
        TransferCreateRequest diffReq = new TransferCreateRequest(batchId, receiverOrg.getId() + 9999L);
        mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diffReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 4. 触发 uk_transfer_open_batch 约束防御：已有 DRAFT 状态交接，使用新 key 再次针对该批次创建交接
        // 数据库唯一约束或前置检查拦截，必须返回 409 BATCH_TRANSFER_CONFLICT，绝不可报 500
        String newKey = "idem-d-new-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_TRANSFER_CONFLICT"));

        // 数据库底层断言：该批次在 transfer 表中只有 1 条记录
        int transferCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer WHERE batch_id = ?", Integer.class, batchId
        );
        assertThat(transferCount).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 E：批次操作互斥（在途 PENDING 批次被批次操作引用为 INPUT 时被拒绝 409 BATCH_TRANSFER_PENDING）")
    void scenarioE_batchOperationMutex_pendingTransferBlocksInput() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long inputBatchId = createAndSubmitActiveBatch(senderSession, "BAT-IN-" + suffix, new BigDecimal("200.000"));
        Long outputBatchId = createAndSubmitActiveBatch(senderSession, "BAT-OUT-" + suffix, new BigDecimal("200.000"));

        // 1. 发货方针对 inputBatchId 创建并提交交接单，进入 PENDING 在途状态
        TransferCreateRequest createReq = new TransferCreateRequest(inputBatchId, receiverOrg.getId());
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-e-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-e-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 2. 发货方尝试创建批次操作草稿，将该在途批次作为 INPUT 消耗
        BatchOperationCreateRequest opReq = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "测试在途批次互斥防御",
                List.of(
                        new BatchOperationItemRequest("INPUT", inputBatchId, new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", outputBatchId, new BigDecimal("100.000"))
                )
        );

        mockMvc.perform(post("/api/v1/batch-operations")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-op-create-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(opReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_TRANSFER_PENDING"));

        // 3. 数据库底层验证：batch_operation 表中未产生该操作草稿
        int opCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_operation WHERE org_id = ? AND note = '测试在途批次互斥防御'",
                Integer.class, senderOrg.getId()
        );
        assertThat(opCount).isEqualTo(0);
    }

    @Test
    @DisplayName("创建幂等域与责任组织解耦：双方持有相同 creation_idempotency_key 时 ACCEPT 仍成功，creation_org_id 不随交接漂移，转出后重放创建请求不重复建批")
    void acceptTransfer_sameCreationIdempotencyKeyAtBothSides_succeedsAndKeepsCreationOrgStable() throws Exception {
        // 发货方与接收方刻意使用完全相同的创建幂等键。
        // 旧模型 UNIQUE(org_id, creation_idempotency_key) 会在 ACCEPT 改写 org_id 时永久唯一键冲突；
        // 新模型以不可变 creation_org_id 为幂等域，双方各自独立，交接不受影响。
        String sharedCreationKey = "idem-shared-creation-" + UUID.randomUUID().toString().replace("-", "");

        Long senderBatchId = createAndSubmitActiveBatch(
                senderSession, "EXT-SHARED-KEY-S", new BigDecimal("300.000"), sharedCreationKey);
        Long receiverOwnBatchId = createAndSubmitActiveBatch(
                receiverSession, "EXT-SHARED-KEY-R", new BigDecimal("100.000"), sharedCreationKey);

        // 前置事实：两个不同组织的批次持有同一个 creation_idempotency_key
        assertThat(readCreationOrgId(senderBatchId)).isEqualTo(senderOrg.getId());
        assertThat(readCreationOrgId(receiverOwnBatchId)).isEqualTo(receiverOrg.getId());
        assertThat(readCreationIdempotencyKey(senderBatchId)).isEqualTo(sharedCreationKey);
        assertThat(readCreationIdempotencyKey(receiverOwnBatchId)).isEqualTo(sharedCreationKey);

        // 新建批次的 creation_org_id 与 org_id 初始一致
        Batch senderBatchBefore = batchMapper.selectById(senderBatchId);
        assertThat(senderBatchBefore.getCreationOrgId()).isEqualTo(senderBatchBefore.getOrgId());

        // 创建并提交交接
        MvcResult res = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-create-" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(senderBatchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-submit-" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 关键回归：接收方已持有相同 creation_idempotency_key，ACCEPT 依然成功
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-accept-" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferAcceptRequest(
                                new BigDecimal("300.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 责任组织转移，但创建组织是不可变事实，绝不漂移
        Batch senderBatchAfter = batchMapper.selectById(senderBatchId);
        assertThat(senderBatchAfter.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(senderBatchAfter.getCreationOrgId()).isEqualTo(senderOrg.getId());
        assertThat(readCreationOrgId(senderBatchId)).isEqualTo(senderOrg.getId());

        // 批次已转出后，原创建方重放原始创建请求：命中原批次，绝不产生第二条批次
        int batchCountBeforeReplay = countBatchesByCreationKey(senderOrg.getId(), sharedCreationKey);
        assertThat(batchCountBeforeReplay).isEqualTo(1);

        BatchCreateRequest replayReq = new BatchCreateRequest(
                "EXT-SHARED-KEY-S", testProduct.getId(), "SOURCE",
                new BigDecimal("300.000"), "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", sharedCreationKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replayReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(senderBatchId))
                // 如实返回该批次当前责任组织（已转移给接收方）
                .andExpect(jsonPath("$.data.orgId").value(receiverOrg.getId()))
                // creationOrgId 是服务端技术字段，绝不出现在对外响应中
                .andExpect(jsonPath("$.data.creationOrgId").doesNotExist());

        assertThat(countBatchesByCreationKey(senderOrg.getId(), sharedCreationKey))
                .as("转出后重放创建请求绝不能新增第二条批次")
                .isEqualTo(1);

        // 同 key 不同载荷仍然是 409 IDEMPOTENCY_KEY_REUSED
        BatchCreateRequest differentPayload = new BatchCreateRequest(
                "EXT-SHARED-KEY-S", testProduct.getId(), "SOURCE",
                new BigDecimal("999.000"), "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180
        );
        mockMvc.perform(post("/api/v1/batches")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", sharedCreationKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countBatchesByCreationKey(senderOrg.getId(), sharedCreationKey)).isEqualTo(1);

        // 接收方自己的同名幂等键批次完全不受影响
        Batch receiverOwnAfter = batchMapper.selectById(receiverOwnBatchId);
        assertThat(receiverOwnAfter.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(receiverOwnAfter.getCreationOrgId()).isEqualTo(receiverOrg.getId());
    }

    private Long readCreationOrgId(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT creation_org_id FROM batch WHERE id = ?", Long.class, batchId);
    }

    private String readCreationIdempotencyKey(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT creation_idempotency_key FROM batch WHERE id = ?", String.class, batchId);
    }

    private int countBatchesByCreationKey(Long creationOrgId, String creationIdempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch WHERE creation_org_id = ? AND creation_idempotency_key = ? AND is_deleted = 0",
                Integer.class, creationOrgId, creationIdempotencyKey
        );
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("接收方同名外部批号不再阻断交接：externalBatchNo 可重复，ACCEPT 成功且 traceBatchNo 稳定不变")
    void acceptTransfer_sameExternalBatchNoAtReceiver_succeeds() throws Exception {
        // 业务契约 v1.1 §3.2：externalBatchNo 是企业可选的外部原始批号，可重复，
        // 不作为 Batch 身份，也不参与 Transfer 接收冲突判断；Batch 身份由服务端生成的 traceBatchNo 承担。
        String sameExternalBatchNo = "BAT-DUP-EXT-" + UUID.randomUUID().toString().substring(0, 6);
        Long senderBatchId = createAndSubmitActiveBatch(senderSession, sameExternalBatchNo, new BigDecimal("300.000"));

        // 在接收方企业预先创建持有相同 externalBatchNo 的批次（历史上会触发 BATCH_NO_CONFLICT）
        Long receiverExistingBatchId = createAndSubmitActiveBatch(receiverSession, sameExternalBatchNo, new BigDecimal("100.000"));

        Batch senderBatchBefore = batchMapper.selectById(senderBatchId);
        String traceBatchNoBefore = senderBatchBefore.getTraceBatchNo();
        Batch receiverBatchBefore = batchMapper.selectById(receiverExistingBatchId);

        // 两个批次的外部批号相同，但服务端生成的 traceBatchNo 必须彼此不同且全局唯一
        assertThat(senderBatchBefore.getExternalBatchNo()).isEqualTo(sameExternalBatchNo);
        assertThat(receiverBatchBefore.getExternalBatchNo()).isEqualTo(sameExternalBatchNo);
        assertThat(traceBatchNoBefore).isNotEqualTo(receiverBatchBefore.getTraceBatchNo());

        // 发送方创建并提交交接
        String createKey = "idem-create-" + UUID.randomUUID().toString().replace("-", "");
        MvcResult res = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(senderBatchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();

        Long transferId = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-submit-" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 接收方接受：即使本企业已存在同名 externalBatchNo，也必须成功进入 ACCEPTED
        TransferAcceptRequest acceptReq = new TransferAcceptRequest(
                new BigDecimal("300.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L
        );
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-accept-" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 交接进入 ACCEPTED 终态
        Transfer t = transferMapper.selectById(transferId);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.ACCEPTED);

        // 责任组织转移至接收方；批次身份（traceBatchNo）与企业外部批号均保持不变
        Batch senderBatchAfter = batchMapper.selectById(senderBatchId);
        assertThat(senderBatchAfter.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(senderBatchAfter.getTraceBatchNo()).isEqualTo(traceBatchNoBefore);
        assertThat(senderBatchAfter.getExternalBatchNo()).isEqualTo(sameExternalBatchNo);

        // 交接不复制批次：接收方原有同名外部批号批次完全不受影响
        Batch receiverBatchAfter = batchMapper.selectById(receiverExistingBatchId);
        assertThat(receiverBatchAfter.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(receiverBatchAfter.getTraceBatchNo()).isEqualTo(receiverBatchBefore.getTraceBatchNo());
        assertThat(receiverBatchAfter.getExternalBatchNo()).isEqualTo(sameExternalBatchNo);

        // 同一 externalBatchNo 在库中确实存在两条不同身份的批次记录
        int duplicateExternalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch WHERE external_batch_no = ? AND is_deleted = 0",
                Integer.class, sameExternalBatchNo
        );
        assertThat(duplicateExternalCount).isEqualTo(2);
    }

    private Long createAndSubmitProcessOperation(MockHttpSession session, Long inputBatchId, Long outputBatchId, BigDecimal quantity) throws Exception {
        String idemCreate = "idem-op-c-" + UUID.randomUUID().toString().replace("-", "");
        BatchOperationCreateRequest opReq = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "测试物料消耗",
                List.of(
                        new BatchOperationItemRequest("INPUT", inputBatchId, quantity),
                        new BatchOperationItemRequest("OUTPUT", outputBatchId, quantity)
                )
        );
        MvcResult res = mockMvc.perform(post("/api/v1/batch-operations")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idemCreate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(opReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long opId = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdOperationIds.add(opId);

        String idemSubmit = "idem-op-s-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/batch-operations/" + opId + "/submit")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idemSubmit)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.example.traceability.batch.dto.BatchOperationSubmitRequest(0L))))
                .andExpect(status().isOk());
        return opId;
    }

    @Test
    @DisplayName("场景 F：批次双状态矩阵（仅 ACTIVE+NORMAL 可提交；FROZEN/RECALLED/CLOSED/DRAFT 组合禁止提交；PENDING 期间冻结召回禁止接受但允许拒收）")
    void scenarioF_batchStatusMatrix_blocksSubmissionAndAcceptance() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-F1-" + suffix, new BigDecimal("400.000"));

        // 创建交接草稿
        String createKey = "idem-f1-c-" + suffix;
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        // 1. 模拟将批次变更为 ACTIVE + FROZEN（质量冻结），提交被拦截 409 BATCH_FLOW_BLOCKED
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'FROZEN' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-sub-frz-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 2. 模拟将批次变更为 ACTIVE + RECALLED（按 V8 历史映射基线：召回只改风险维度），提交被拦截 409 BATCH_FLOW_BLOCKED
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'RECALLED' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-sub-rec-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 3. 模拟将批次变更为 CLOSED + NORMAL（正常流转结束），提交被拦截 409 BATCH_FLOW_BLOCKED
        jdbcTemplate.update("UPDATE batch SET flow_status = 'CLOSED', risk_status = 'NORMAL' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-sub-cls-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 4. 模拟将批次回退为 DRAFT + NORMAL，提交被拦截 409 BATCH_FLOW_BLOCKED
        jdbcTemplate.update("UPDATE batch SET flow_status = 'DRAFT', risk_status = 'NORMAL' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-sub-dft-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 5. 恢复为 ACTIVE + NORMAL（唯一可正常流转组合）并正常提交进入 PENDING 状态
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'NORMAL' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-sub-ok-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 6. 在途 PENDING 期间批次被质量冻结 (ACTIVE + FROZEN)：接收方尝试 accept 拦截抛 409 BATCH_FLOW_BLOCKED
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'FROZEN' WHERE id = ?", batchId);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-acc-frz-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferAcceptRequest(new BigDecimal("400.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 7. 但接收方依然允许 reject 拒收，状态流转至 REJECTED，批次归属不转移
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/reject")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f-rej-frz-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRejectRequest("在途被质量冻结，拒收处理", OffsetDateTime.now(ZoneOffset.UTC), 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        Batch b = batchMapper.selectById(batchId);
        assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());

        // 8. 补齐状态矩阵：新建独立交接，测试 PENDING 期间批次被召回 (RECALLED) 的行为
        Long batchId2 = createAndSubmitActiveBatch(senderSession, "BAT-F2-" + suffix, new BigDecimal("400.000"));
        MvcResult cRes2 = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f2-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId2, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId2 = objectMapper.readTree(cRes2.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId2);

        mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f2-sub-ok-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // PENDING 后批次进入召回风险状态 (ACTIVE + RECALLED)
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'RECALLED' WHERE id = ?", batchId2);

        // accept 必须 409 BATCH_FLOW_BLOCKED
        mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f2-acc-rec-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferAcceptRequest(new BigDecimal("400.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // receiver reject 必须 200 REJECTED，批次仍归 sender
        mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/reject")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-f2-rej-rec-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRejectRequest("在途被召回，拒收处理", OffsetDateTime.now(ZoneOffset.UTC), 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        Batch b2 = batchMapper.selectById(batchId2);
        assertThat(b2.getOrgId()).isEqualTo(senderOrg.getId());
    }

    @Test
    @DisplayName("场景 G：已消耗批次排他（已作为INPUT消耗提交的批次禁止新建或提交交接）")
    void scenarioG_consumedBatchMutex_blocksTransferCreationAndSubmission() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long inBatch1 = createAndSubmitActiveBatch(senderSession, "BAT-IN1-" + suffix, new BigDecimal("300.000"));
        Long outBatch1 = createAndSubmitActiveBatch(senderSession, "BAT-OUT1-" + suffix, new BigDecimal("300.000"));

        // 1. 通过批次操作将 inBatch1 作为 INPUT 消耗并提交
        createAndSubmitProcessOperation(senderSession, inBatch1, outBatch1, new BigDecimal("300.000"));

        // 发货方尝试针对已被消耗的批次创建交接草稿，返回 409 BATCH_ALREADY_CONSUMED
        mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-g-c1-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(inBatch1, receiverOrg.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_CONSUMED"));

        // 2. 测试先创建交接草稿，之后批次被物料操作消耗并提交，再提交交接单被拦截
        Long inBatch2 = createAndSubmitActiveBatch(senderSession, "BAT-IN2-" + suffix, new BigDecimal("300.000"));
        Long outBatch2 = createAndSubmitActiveBatch(senderSession, "BAT-OUT2-" + suffix, new BigDecimal("300.000"));

        MvcResult draftRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-g-c2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(inBatch2, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId2 = objectMapper.readTree(draftRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId2);

        // 执行物料操作并提交，消耗 inBatch2
        createAndSubmitProcessOperation(senderSession, inBatch2, outBatch2, new BigDecimal("300.000"));

        // 提交已创建的交接单，被二次拦截并返回 409 BATCH_ALREADY_CONSUMED
        mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-g-s2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_CONSUMED"));
    }

    @Test
    @DisplayName("场景 H：SQL 层双边组织隔离（第三方不可列表、不可查详情、不可提交、不可决断）")
    void scenarioH_strictOrgScopeIsolation_listDetailAndActionsDenied() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization thirdOrg = createOrg("ORG_H3_" + suffix, "隔离第三方企业-" + suffix);
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "OWN_ORG");
        AppUser thirdUser = createUser(thirdOrg.getId(), "thd_h3_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(thirdUser.getId(), opRole.getId());
        MockHttpSession thirdSession = (MockHttpSession) loginAndGetSession(thirdUser.getUsername(), DEFAULT_PASSWORD);

        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-H-" + suffix, new BigDecimal("150.000"));
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-h-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        // 1. 第三方企业列表查询：SQL 强制隔离，返回数据为空
        mockMvc.perform(get("/api/v1/transfers")
                        .session(thirdSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));

        // 2. 第三方企业查询详情：直接抛出 403 ORG_SCOPE_DENIED
        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .session(thirdSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 3. 第三方企业尝试提交发货方草稿：403 ORG_SCOPE_DENIED
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(thirdSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-h-sbm-thd-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("场景 I：RBAC 权限矩阵（接收方质量管理员允许代办；管理员与审计员写操作被拦截）")
    void scenarioI_rbacPermissionMatrix_qualityManagerAllowedAndAdminAuditorRestricted() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. 创建接收方质量管理员 QUALITY_MANAGER
        Role qmRole = getOrCreateRole("QUALITY_MANAGER", "质量管理员", "OWN_ORG");
        AppUser receiverQmUser = createUser(receiverOrg.getId(), "rcv_qm_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(receiverQmUser.getId(), qmRole.getId());
        MockHttpSession receiverQmSession = (MockHttpSession) loginAndGetSession(receiverQmUser.getUsername(), DEFAULT_PASSWORD);

        // 2. 创建系统管理员 SYSTEM_ADMIN
        Role adminRole = getOrCreateRole("SYSTEM_ADMIN", "系统管理员", "PLATFORM");
        AppUser adminUser = createUser(senderOrg.getId(), "sys_adm_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(adminUser.getId(), adminRole.getId());
        MockHttpSession adminSession = (MockHttpSession) loginAndGetSession(adminUser.getUsername(), DEFAULT_PASSWORD);

        // 3. 创建仅 AUDITOR 审计员
        Role auditorRole = getOrCreateRole("AUDITOR", "审计查看者", "PLATFORM");
        AppUser auditorUser = createUser(receiverOrg.getId(), "rcv_aud_" + suffix, DEFAULT_PASSWORD);
        bindUserRole(auditorUser.getId(), auditorRole.getId());
        MockHttpSession auditorSession = (MockHttpSession) loginAndGetSession(auditorUser.getUsername(), DEFAULT_PASSWORD);

        // 发货方创建并提交交接 A
        Long batchA = createAndSubmitActiveBatch(senderSession, "BAT-I-A-" + suffix, new BigDecimal("100.000"));
        MvcResult cResA = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-ca-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchA, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferIdA = objectMapper.readTree(cResA.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferIdA);

        mockMvc.perform(post("/api/v1/transfers/" + transferIdA + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-sa-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 4. 接收方质量管理员执行 ACCEPT：代办允许，返回 200 ACCEPTED
        mockMvc.perform(post("/api/v1/transfers/" + transferIdA + "/accept")
                        .session(receiverQmSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-qm-acc-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 发货方创建并提交交接 B
        Long batchB = createAndSubmitActiveBatch(senderSession, "BAT-I-B-" + suffix, new BigDecimal("100.000"));
        MvcResult cResB = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-cb-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchB, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferIdB = objectMapper.readTree(cResB.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferIdB);

        mockMvc.perform(post("/api/v1/transfers/" + transferIdB + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-sb-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 5. 接收方质量管理员执行 REJECT：代办允许，返回 200 REJECTED
        mockMvc.perform(post("/api/v1/transfers/" + transferIdB + "/reject")
                        .session(receiverQmSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-qm-rej-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRejectRequest("质检不合格，代办拒收", OffsetDateTime.now(ZoneOffset.UTC), 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 6. 系统管理员尝试创建交接：返回 403 ADMIN_RESTRICTED
        mockMvc.perform(post("/api/v1/transfers")
                        .session(adminSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-adm-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchA, receiverOrg.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_RESTRICTED"));

        // 7. 审计员尝试写操作：返回 403 ROLE_NOT_ALLOWED
        mockMvc.perform(post("/api/v1/transfers")
                        .session(auditorSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-i-aud-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchA, receiverOrg.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("场景 J1：真并发测试（双 accept 接收竞争：恰好一个成功，另一个409冲突）")
    void scenarioJ1_trueConcurrency_twoAcceptsCompetition() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batch1 = createAndSubmitActiveBatch(senderSession, "BAT-J1-" + suffix, new BigDecimal("250.000"));

        MvcResult cRes1 = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-j1-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batch1, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId1 = objectMapper.readTree(cRes1.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId1);

        mockMvc.perform(post("/api/v1/transfers/" + transferId1 + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-j1-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 使用两个独立 MockHttpSession
        MockHttpSession receiverSession1 = (MockHttpSession) loginAndGetSession(receiverUser.getUsername(), DEFAULT_PASSWORD);
        MockHttpSession receiverSession2 = (MockHttpSession) loginAndGetSession(receiverUser.getUsername(), DEFAULT_PASSWORD);

        // 两个线程并发发送 /accept 请求
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<MvcResult> resRef1 = new AtomicReference<>();
        AtomicReference<MvcResult> resRef2 = new AtomicReference<>();
        AtomicReference<Throwable> errorRef1 = new AtomicReference<>();
        AtomicReference<Throwable> errorRef2 = new AtomicReference<>();

        TransferAcceptRequest accReq1 = new TransferAcceptRequest(new BigDecimal("250.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
        TransferAcceptRequest accReq2 = new TransferAcceptRequest(new BigDecimal("250.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    MvcResult r = mockMvc.perform(post("/api/v1/transfers/" + transferId1 + "/accept")
                                    .session(receiverSession1)
                                    .with(csrf())
                                    .header("Idempotency-Key", "idem-j1-par-1-" + suffix)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(accReq1)))
                            .andReturn();
                    resRef1.set(r);
                } catch (Throwable t) {
                    errorRef1.set(t);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    barrier.await();
                    MvcResult r = mockMvc.perform(post("/api/v1/transfers/" + transferId1 + "/accept")
                                    .session(receiverSession2)
                                    .with(csrf())
                                    .header("Idempotency-Key", "idem-j1-par-2-" + suffix)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(accReq2)))
                            .andReturn();
                    resRef2.set(r);
                } catch (Throwable t) {
                    errorRef2.set(t);
                } finally {
                    latch.countDown();
                }
            });

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            if (errorRef1.get() != null) {
                throw new AssertionError("Thread 1 threw unexpected exception", errorRef1.get());
            }
            if (errorRef2.get() != null) {
                throw new AssertionError("Thread 2 threw unexpected exception", errorRef2.get());
            }

            int st1 = resRef1.get().getResponse().getStatus();
            int st2 = resRef2.get().getResponse().getStatus();

            // 验证恰好一个 200，另一个 409
            assertThat((st1 == 200 && st2 == 409) || (st1 == 409 && st2 == 200))
                    .as("Expected exactly one 200 and one 409, but got st1=%d, st2=%d", st1, st2)
                    .isTrue();

            // 底层数据库校验：transfer 为 ACCEPTED，批次持有方已转移，ARRIVAL 事件恰好 1 条
            Transfer finalT1 = transferMapper.selectById(transferId1);
            assertThat(finalT1.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
            Batch finalB1 = batchMapper.selectById(batch1);
            assertThat(finalB1.getOrgId()).isEqualTo(receiverOrg.getId());

            int arrivalCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                    Integer.class, transferId1
            );
            assertThat(arrivalCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("场景 J2：真并发测试（accept 与 reject 决策并发竞争：恰好一个成功，另一个409冲突）")
    void scenarioJ2_trueConcurrency_acceptVsRejectCompetition() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batch2 = createAndSubmitActiveBatch(senderSession, "BAT-J2-" + suffix, new BigDecimal("260.000"));

        // 发货方激活公开追溯码，用于验证并发流转时追溯码归属转移或保留
        mockMvc.perform(post("/api/v1/batches/" + batch2 + "/public-trace-code/activate")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-j2-act-" + suffix))
                .andExpect(status().isOk());

        MvcResult cRes2 = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-j2-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batch2, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId2 = objectMapper.readTree(cRes2.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId2);

        mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-j2-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 使用两个独立 MockHttpSession
        MockHttpSession sessionAccept = (MockHttpSession) loginAndGetSession(receiverUser.getUsername(), DEFAULT_PASSWORD);
        MockHttpSession sessionReject = (MockHttpSession) loginAndGetSession(receiverUser.getUsername(), DEFAULT_PASSWORD);

        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);

        AtomicReference<MvcResult> resAcceptRef = new AtomicReference<>();
        AtomicReference<MvcResult> resRejectRef = new AtomicReference<>();
        AtomicReference<Throwable> errAcceptRef = new AtomicReference<>();
        AtomicReference<Throwable> errRejectRef = new AtomicReference<>();

        TransferAcceptRequest accReq = new TransferAcceptRequest(new BigDecimal("260.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
        TransferRejectRequest rejReq = new TransferRejectRequest("并发拒收竞争测试", OffsetDateTime.now(ZoneOffset.UTC), 1L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    MvcResult r = mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/accept")
                                    .session(sessionAccept)
                                    .with(csrf())
                                    .header("Idempotency-Key", "idem-j2-par-acc-" + suffix)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(accReq)))
                            .andReturn();
                    resAcceptRef.set(r);
                } catch (Throwable t) {
                    errAcceptRef.set(t);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    barrier.await();
                    MvcResult r = mockMvc.perform(post("/api/v1/transfers/" + transferId2 + "/reject")
                                    .session(sessionReject)
                                    .with(csrf())
                                    .header("Idempotency-Key", "idem-j2-par-rej-" + suffix)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(rejReq)))
                            .andReturn();
                    resRejectRef.set(r);
                } catch (Throwable t) {
                    errRejectRef.set(t);
                } finally {
                    latch.countDown();
                }
            });

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            if (errAcceptRef.get() != null) {
                throw new AssertionError("Accept thread threw unexpected exception", errAcceptRef.get());
            }
            if (errRejectRef.get() != null) {
                throw new AssertionError("Reject thread threw unexpected exception", errRejectRef.get());
            }

            int stAccept = resAcceptRef.get().getResponse().getStatus();
            int stReject = resRejectRef.get().getResponse().getStatus();

            // 明确断言恰好一个 200、另一个 409
            assertThat((stAccept == 200 && stReject == 409) || (stAccept == 409 && stReject == 200))
                    .as("Expected exactly one 200 and one 409, but got accept: %d, reject: %d", stAccept, stReject)
                    .isTrue();

            Transfer finalT2 = transferMapper.selectById(transferId2);
            Batch finalB2 = batchMapper.selectById(batch2);
            Long ptcOrg = jdbcTemplate.queryForObject(
                    "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batch2
            );
            int arrivalCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                    Integer.class, transferId2
            );
            int acceptAuditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER' AND object_id = ? AND result = 'SUCCESS'",
                    Integer.class, receiverOrg.getId(), transferId2
            );
            int rejectAuditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'REJECT' AND object_type = 'TRANSFER' AND object_id = ? AND result = 'SUCCESS'",
                    Integer.class, receiverOrg.getId(), transferId2
            );

            if (stAccept == 200) {
                // 若最终 ACCEPTED：batch.org_id=receiver、ARRIVAL=1、public_trace_code 如存在归 receiver；
                assertThat(finalT2.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
                assertThat(finalB2.getOrgId()).isEqualTo(receiverOrg.getId());
                assertThat(arrivalCount).isEqualTo(1);
                assertThat(ptcOrg).isEqualTo(receiverOrg.getId());
                assertThat(acceptAuditCount).isEqualTo(1);
                assertThat(rejectAuditCount).isEqualTo(0);
            } else {
                // 若最终 REJECTED：batch.org_id=sender、ARRIVAL=0、public_trace_code 如存在仍归 sender；
                assertThat(finalT2.getStatus()).isEqualTo(TransferStatus.REJECTED);
                assertThat(finalB2.getOrgId()).isEqualTo(senderOrg.getId());
                assertThat(arrivalCount).isEqualTo(0);
                assertThat(ptcOrg).isEqualTo(senderOrg.getId());
                assertThat(acceptAuditCount).isEqualTo(0);
                assertThat(rejectAuditCount).isEqualTo(1);
            }

            // 对应 ACCEPT/REJECT 成功审计总数恰好 1，失败方无副作用
            assertThat(acceptAuditCount + rejectAuditCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("场景 K：事务原子回滚实证（ARRIVAL追溯事件写入冲突导致整个接受事务回滚）")
    void scenarioK_transactionAtomicRollback_arrivalConflictRollsBackAll() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-K-" + suffix, new BigDecimal("180.000"));

        // 发货方激活追溯码
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-k-act-" + suffix))
                .andExpect(status().isOk());

        // 发货方创建并提交交接
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-k-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-k-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 在 trace_event 表中预先插入一条冲突记录，使 appendArrivalEvent 触发唯一索引 (org_id, idempotency_key) 冲突
        String arrivalIdemKey = "TRANSFER_ARRIVAL_" + transferId;
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, details_json, version, is_deleted, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'ARRIVAL', NOW(6), NOW(6), ?, 'MANUAL', 'SUBMITTED', ?, '冲突预置事件', '{}', 0, 0, NOW(6), ?, NOW(6), ?)
                """, batchId, receiverOrg.getId(), receiverUser.getId(), arrivalIdemKey, receiverUser.getId(), receiverUser.getId());

        try {
            // 接收方调用 accept，预期触发回滚
            String acceptIdemKey = "idem-k-acc-" + suffix;
            TransferAcceptRequest accReq = new TransferAcceptRequest(new BigDecimal("180.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
            mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                            .session(receiverSession)
                            .with(csrf())
                            .header("Idempotency-Key", acceptIdemKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(accReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

            // 验证数据库完整原子回滚：
            // 1. transfer 仍为 PENDING，版本仍为 1
            Transfer t = transferMapper.selectById(transferId);
            assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(t.getVersion()).isEqualTo(1L);

            // 2. 批次归属仍为发货方，版本仍为 1
            Batch b = batchMapper.selectById(batchId);
            assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());
            assertThat(b.getVersion()).isEqualTo(1L);

            // 3. 公开追溯码归属仍为发货方
            Long ptcOrg = jdbcTemplate.queryForObject(
                    "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId
            );
            assertThat(ptcOrg).isEqualTo(senderOrg.getId());

            // 4. 接收方无本次接受成功的审计记录
            int acceptAuditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER' AND object_id = ?",
                    Integer.class, receiverOrg.getId(), transferId
            );
            assertThat(acceptAuditCount).isEqualTo(0);

            // 5. 验证本次 accept 的 transfer_idempotency 记录不存在（已回滚）
            int acceptIdemCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM transfer_idempotency WHERE org_id = ? AND idempotency_key = ?",
                    Integer.class, receiverOrg.getId(), acceptIdemKey
            );
            assertThat(acceptIdemCount).isEqualTo(0);

            // 6. 预置冲突 trace_event 仍只保留原记录且没有新的 ARRIVAL 成功副作用
            int arrivalCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                    Integer.class, transferId
            );
            assertThat(arrivalCount).isEqualTo(0);

            List<String> eventSummaries = jdbcTemplate.queryForList(
                    "SELECT summary FROM trace_event WHERE batch_id = ? AND event_type = 'ARRIVAL'",
                    String.class, batchId
            );
            assertThat(eventSummaries).containsExactly("冲突预置事件");
        } finally {
            // 7. 清理测试预置的冲突事件记录
            jdbcTemplate.update("DELETE FROM trace_event WHERE org_id = ? AND idempotency_key = ?", receiverOrg.getId(), arrivalIdemKey);
        }
    }

    @Test
    @DisplayName("场景 L：幂等重放 ARRIVAL 追溯事件唯一性与双时间留痕验证")
    void scenarioL_idempotentAccept_replaysSameResultAndSingleArrivalEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-L-" + suffix, new BigDecimal("320.000"));

        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-l-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-l-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 首次调用 accept
        String idemKey = "idem-l-acc-" + suffix;
        TransferAcceptRequest accReq = new TransferAcceptRequest(new BigDecimal("320.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 二次重放相同 accept 请求
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 验证 ARRIVAL 事件仅存在 1 条，且双时间字段完整有效
        int arrivalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                Integer.class, transferId
        );
        assertThat(arrivalCount).isEqualTo(1);

        Transfer finalT = transferMapper.selectById(transferId);
        assertThat(finalT.getShippedAt()).isNotNull();
        assertThat(finalT.getSubmittedRecordedAt()).isNotNull();
        assertThat(finalT.getReceivedAt()).isNotNull();
        assertThat(finalT.getDecisionRecordedAt()).isNotNull();
        assertThat(finalT.getDecidedBy()).isEqualTo(receiverUser.getId());
    }

    @Test
    @DisplayName("场景 M：最长 128 字符 Idempotency-Key 创建与幂等重放验证")
    void scenarioM_maxLength128IdempotencyKey_creationAndReplay() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-M-" + suffix, new BigDecimal("250.000"));

        // 构造恰好 128 字符合法幂等键
        String key128 = "idem-128-" + suffix + "-" + "k".repeat(110);
        assertThat(key128.length()).isEqualTo(128);

        // 首次创建交接草稿
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", key128)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();

        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        // 验证 MySQL 8.4 真实列完整落库 128 位且未被截断
        String savedKeyInDb = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM transfer WHERE id = ?", String.class, transferId
        );
        assertThat(savedKeyInDb).isEqualTo(key128);

        // 再次使用相同的 128 字符幂等键重放创建
        mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", key128)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(transferId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 验证数据库中未产生第 2 条记录
        int transferCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer WHERE idempotency_key = ?", Integer.class, key128
        );
        assertThat(transferCount).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 N：公开追溯码持有组织不匹配导致受控冲突 409 与整体事务回滚")
    void scenarioN_publicTraceCodeOrgMismatch_rollsBackEntireAcceptance() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-N-" + suffix, new BigDecimal("190.000"));

        // 激活公开追溯码
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-n-act-" + suffix))
                .andExpect(status().isOk());

        // 发货方创建并提交交接
        MvcResult cRes = mockMvc.perform(post("/api/v1/transfers")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-n-c-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferCreateRequest(batchId, receiverOrg.getId()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long transferId = objectMapper.readTree(cRes.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdTransferIds.add(transferId);

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/submit")
                        .session(senderSession)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-n-s-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L))))
                .andExpect(status().isOk());

        // 模拟异常脏数据：故意将关联的公开追溯码 org_id 篡改为第三方组织（例如不存在的假组织 999999）
        Long rogueOrgId = 999999L;
        jdbcTemplate.update("UPDATE public_trace_code SET org_id = ? WHERE batch_id = ?", rogueOrgId, batchId);

        // 接收方调用 accept，预期触发受控冲突 409 TRACE_CODE_ORG_CONFLICT
        String acceptIdemKey = "idem-n-acc-" + suffix;
        TransferAcceptRequest accReq = new TransferAcceptRequest(new BigDecimal("190.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);
        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/accept")
                        .session(receiverSession)
                        .with(csrf())
                        .header("Idempotency-Key", acceptIdemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACE_CODE_ORG_CONFLICT"));

        // 验证整体事务原子回滚：
        // 1. transfer 状态回滚为 PENDING，版本回滚为 1
        Transfer t = transferMapper.selectById(transferId);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.getVersion()).isEqualTo(1L);

        // 2. 批次归属回滚为发货方，版本回滚为 1
        Batch b = batchMapper.selectById(batchId);
        assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());
        assertThat(b.getVersion()).isEqualTo(1L);

        // 3. 追溯码没有被转移到 receiverOrg
        Long ptcOrg = jdbcTemplate.queryForObject(
                "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId
        );
        assertThat(ptcOrg).isEqualTo(rogueOrgId);

        // 4. 未产生 ARRIVAL 追溯事件
        int arrivalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trace_event WHERE JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.transferId')) = CAST(? AS CHAR) AND event_type = 'ARRIVAL'",
                Integer.class, transferId
        );
        assertThat(arrivalCount).isEqualTo(0);

        // 5. 未产生 ACCEPT 成功审计
        int auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER' AND object_id = ?",
                Integer.class, receiverOrg.getId(), transferId
        );
        assertThat(auditCount).isEqualTo(0);
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
