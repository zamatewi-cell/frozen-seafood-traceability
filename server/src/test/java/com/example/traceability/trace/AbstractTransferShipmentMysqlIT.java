package com.example.traceability.trace;

import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.dto.ShipmentBindTransferRequest;
import com.example.traceability.trace.dto.ShipmentCreateRequest;
import com.example.traceability.trace.dto.ShipmentDispatchRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferSubmitRequest;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Transfer / Shipment 真实 MySQL 8.4 集成测试共享夹具。
 * <p>
 * 每个测试用例独立创建：发货方 SOURCE 组织、接收方 PROCESSOR 组织、承运方 CARRIER 组织（Demo MVP Phase A 三方分离），
 * 各自 OPERATOR 账号与真实登录 Session、发货方启运场所 (PORT)、接收方目的场所 (FACTORY) 与测试产品；
 * 并提供 Transfer / Shipment 全路径 HTTP 辅助方法。测试结束后按外键依赖顺序物理清理并断言无残留。
 * </p>
 */
abstract class AbstractTransferShipmentMysqlIT {

    protected static final String DEFAULT_PASSWORD = "Password123!";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected OrganizationMapper organizationMapper;

    @Autowired
    protected AppUserMapper appUserMapper;

    @Autowired
    protected RoleMapper roleMapper;

    @Autowired
    protected UserRoleMapper userRoleMapper;

    @Autowired
    protected ProductMapper productMapper;

    @Autowired
    protected SiteMapper siteMapper;

    @Autowired
    protected BatchMapper batchMapper;

    @Autowired
    protected TransferMapper transferMapper;

    @Autowired
    protected javax.sql.DataSource dataSource;

    protected final List<Long> createdTransferIds = new ArrayList<>();
    protected final List<Long> createdShipmentIds = new ArrayList<>();
    protected final List<Long> createdBatchIds = new ArrayList<>();
    protected final List<Long> createdProductIds = new ArrayList<>();
    protected final List<Long> createdUserIds = new ArrayList<>();
    protected final List<Long> createdOrgIds = new ArrayList<>();
    protected final List<Long> createdRoleIds = new ArrayList<>();
    protected final List<Long> createdOperationIds = new ArrayList<>();
    protected final List<Long> createdSiteIds = new ArrayList<>();

    protected String suffix;
    protected Organization senderOrg;
    protected Organization receiverOrg;
    protected Organization carrierOrg;
    protected AppUser senderUser;
    protected AppUser receiverUser;
    protected AppUser carrierUser;
    protected Site senderSite;
    protected Site receiverSite;
    protected Product testProduct;
    protected Role operatorRole;
    protected MockHttpSession senderSession;
    protected MockHttpSession receiverSession;
    protected MockHttpSession carrierSession;

    /**
     * 一张已绑定运输任务的交接。
     *
     * @param transferId 交接 ID
     * @param shipmentId 运输任务 ID
     */
    protected record Handover(Long transferId, Long shipmentId) {
    }

    @BeforeEach
    void setUpTransferShipmentFixture() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        senderOrg = createOrg("ORG_S_" + suffix, "来源捕捞企业-" + suffix, "SOURCE");
        receiverOrg = createOrg("ORG_R_" + suffix, "加工企业-" + suffix, "PROCESSOR");
        carrierOrg = createOrg("ORG_C_" + suffix, "冷链承运企业-" + suffix, "CARRIER");

        operatorRole = getOrCreateRole("OPERATOR", "企业操作员", "OWN_ORG");
        senderUser = createUser(senderOrg.getId(), "snd_op_" + suffix);
        bindUserRole(senderUser.getId(), operatorRole.getId());
        receiverUser = createUser(receiverOrg.getId(), "rcv_op_" + suffix);
        bindUserRole(receiverUser.getId(), operatorRole.getId());
        carrierUser = createUser(carrierOrg.getId(), "car_op_" + suffix);
        bindUserRole(carrierUser.getId(), operatorRole.getId());

        senderSite = createSite(senderOrg.getId(), "SRC-PORT-" + suffix, "来源码头-" + suffix, "PORT");
        receiverSite = createSite(receiverOrg.getId(), "PRC-FAC-" + suffix, "加工厂-" + suffix, "FACTORY");

        testProduct = createProduct("PRD_" + suffix, "冷冻大黄鱼-" + suffix);

        senderSession = login(senderUser.getUsername());
        receiverSession = login(receiverUser.getUsername());
        carrierSession = login(carrierUser.getUsername());
    }

    /**
     * 把接收方替换为另一个 SOURCE 类型组织（含目的场所与登录会话），用于需要接收方自行创建来源批次的回归场景：
     * Slice 1 规则只允许 SOURCE 组织创建来源批次。
     */
    protected void useSourceTypeReceiver() throws Exception {
        receiverOrg = createOrg("ORG_RS_" + suffix, "来源型接收企业-" + suffix, "SOURCE");
        receiverUser = createUser(receiverOrg.getId(), "rcv_src_" + suffix);
        bindUserRole(receiverUser.getId(), operatorRole.getId());
        receiverSite = createSite(receiverOrg.getId(), "RS-PORT-" + suffix, "接收码头-" + suffix, "PORT");
        receiverSession = login(receiverUser.getUsername());
    }

    /**
     * 把接收方替换为 RETAILER 组织（含启用门店 STORE 作为运输目的场所与销售场所、登录会话），用于终端销售场景：
     * 来源企业建批 → Transfer + Shipment 送达 → 零售企业 ACCEPT 后即由零售企业负责。
     */
    protected void useRetailerReceiver() throws Exception {
        receiverOrg = createOrg("ORG_RT_" + suffix, "零售企业-" + suffix, "RETAILER");
        receiverUser = createUser(receiverOrg.getId(), "rcv_ret_" + suffix);
        bindUserRole(receiverUser.getId(), operatorRole.getId());
        receiverSite = createSite(receiverOrg.getId(), "RT-STORE-" + suffix, "零售门店-" + suffix, "STORE");
        receiverSession = login(receiverUser.getUsername());
    }

    /**
     * 来源企业建批 → Transfer + Shipment 送达 → 当前接收方 ACCEPT，返回已由接收方负责的批次（接收方类型由调用方预先设定）。
     */
    protected Long receiverHeldBatch(String externalBatchNo, BigDecimal quantity) throws Exception {
        return processorHeldBatch(externalBatchNo, quantity);
    }

    /**
     * 等待本 schema 指定表上出现 InnoDB 行锁等待（即被测请求已阻塞在行锁上），作为确定性交错的同步点。
     * <p>
     * performance_schema 的锁视图需要管理员权限：被测应用数据源（CI 中为非 root 的 trace_user）不能也不应读取，
     * 因此仅这一诊断探针沿用迁移测试的约定，通过 DB_ROOT_USERNAME / DB_ROOT_PASSWORD 单独建立管理连接；
     * 应用数据源、权限与业务行为均不变。若被测线程在出现锁等待前就已结束，说明未经过同步点，立即失败。
     * </p>
     */
    protected void awaitRowLockWait(Thread worker, String table) throws Exception {
        awaitRowLockWaits(worker, table, 1);
    }

    /**
     * 等待本 schema 指定表上出现至少 {@code minWaits} 个 InnoDB 行锁等待（用于确认多个被测请求同时阻塞在同一张表上）。
     */
    protected void awaitRowLockWaits(Thread worker, String table, int minWaits) throws Exception {
        String baseUrl = ((com.zaxxer.hikari.HikariDataSource) dataSource).getJdbcUrl();
        String rootUser = System.getenv().getOrDefault("DB_ROOT_USERNAME", "root");
        if (rootUser.isBlank()) {
            rootUser = "root";
        }
        String rootPassword = System.getenv().getOrDefault("DB_ROOT_PASSWORD", "");
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        String probe = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = ?";
        long deadline = System.currentTimeMillis() + 15_000;
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(baseUrl, rootUser, rootPassword);
             java.sql.PreparedStatement ps = admin.prepareStatement(probe)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            while (true) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) >= minWaits) {
                        return;
                    }
                }
                assertThat(worker.isAlive()).as("request finished without waiting on the %s row lock", table).isTrue();
                assertThat(System.currentTimeMillis()).as("request never waited on the %s row lock", table).isLessThan(deadline);
                Thread.sleep(20);
            }
        }
    }

    @AfterEach
    void tearDownTransferShipmentFixture() {
        // V12：batch_risk_transition → batch / organization（追加式台账），必须先于批次与组织清理
        clean("DELETE FROM batch_risk_transition WHERE batch_id = ?", createdBatchIds);
        clean("DELETE FROM batch_risk_transition WHERE org_id = ?", createdOrgIds);
        clean("DELETE FROM batch_risk_transition WHERE batch_id IN (SELECT id FROM batch WHERE creation_org_id = ? OR org_id = ?)", createdOrgIds, 2);
        clean("DELETE FROM transfer_idempotency WHERE org_id = ?", createdOrgIds);
        clean("DELETE FROM shipment_idempotency WHERE org_id = ?", createdOrgIds);
        clean("DELETE FROM public_trace_code_idempotency WHERE org_id = ?", createdOrgIds);
        clean("DELETE FROM public_trace_code WHERE batch_id = ?", createdBatchIds);
        clean("DELETE FROM trace_event WHERE batch_id = ?", createdBatchIds);
        // 批次操作产出的输出批次由服务端生成（不在 createdBatchIds 中），按创建组织兜底清理其事件
        clean("DELETE FROM trace_event WHERE batch_id IN (SELECT id FROM batch WHERE creation_org_id = ?)", createdOrgIds);

        // V11：batch.first_sale_id → sale（同批次同组织）与 sale → batch / site 互相引用：先断开首次销售标记，再删销售台账
        clean("UPDATE batch SET first_sale_id = NULL WHERE id = ?", createdBatchIds);
        clean("UPDATE batch SET first_sale_id = NULL WHERE creation_org_id = ? OR org_id = ?", createdOrgIds, 2);
        clean("DELETE FROM sale WHERE batch_id = ?", createdBatchIds);
        clean("DELETE FROM sale WHERE org_id = ?", createdOrgIds);
        clean("DELETE FROM sale WHERE batch_id IN (SELECT id FROM batch WHERE creation_org_id = ?)", createdOrgIds);

        // V10 外键：batch.produced_by / consumed_by → batch_operation；item / relation → batch。
        // 先断开批次对操作的引用，再删谱系边与明细，最后删操作
        clean("UPDATE batch SET produced_by_operation_id = NULL, consumed_by_operation_id = NULL WHERE id = ?", createdBatchIds);
        clean("UPDATE batch SET produced_by_operation_id = NULL, consumed_by_operation_id = NULL WHERE creation_org_id = ? OR org_id = ?", createdOrgIds, 2);
        for (Long bId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE parent_batch_id = ? OR child_batch_id = ?", bId, bId);
        }
        clean("DELETE FROM batch_relation WHERE operation_id IN (SELECT id FROM batch_operation WHERE org_id = ?)", createdOrgIds);
        for (Long opId : createdOperationIds) {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE operation_id = ?", opId);
        }
        clean("DELETE FROM batch_operation_item WHERE batch_id = ?", createdBatchIds);
        clean("DELETE FROM batch_operation_item WHERE operation_id = ?", createdOperationIds);
        clean("DELETE FROM batch_operation_item WHERE operation_id IN (SELECT id FROM batch_operation WHERE org_id = ?)", createdOrgIds);
        clean("DELETE FROM batch_operation WHERE org_id = ?", createdOrgIds);

        // transfer.shipment_id 外键指向 shipment：先删交接，再删运输任务
        clean("DELETE FROM transfer WHERE batch_id = ?", createdBatchIds);
        clean("DELETE FROM transfer WHERE id = ?", createdTransferIds);
        clean("DELETE FROM shipment WHERE sender_org_id = ?", createdOrgIds);
        clean("DELETE FROM shipment WHERE id = ?", createdShipmentIds);
        clean("DELETE FROM batch WHERE id = ?", createdBatchIds);
        clean("DELETE FROM batch WHERE creation_org_id = ?", createdOrgIds);
        clean("DELETE FROM product WHERE id = ?", createdProductIds);

        clean("DELETE FROM user_role WHERE user_id = ?", createdUserIds);
        clean("DELETE FROM user_role WHERE role_id = ?", createdRoleIds);
        clean("DELETE FROM app_user WHERE id = ?", createdUserIds);
        clean("DELETE FROM role WHERE id = ?", createdRoleIds);
        clean("DELETE FROM audit_log WHERE actor_org_id = ?", createdOrgIds);
        clean("DELETE FROM site WHERE id = ?", createdSiteIds);
        clean("DELETE FROM organization WHERE id = ?", createdOrgIds);

        for (Long orgId : createdOrgIds) {
            assertThat(count("SELECT count(*) FROM organization WHERE id = ?", orgId)).isZero();
            assertThat(count("SELECT count(*) FROM shipment WHERE sender_org_id = ? OR receiver_org_id = ? OR carrier_org_id = ?", orgId, orgId, orgId)).isZero();
            assertThat(count("SELECT count(*) FROM shipment_idempotency WHERE org_id = ?", orgId)).isZero();
            assertThat(count("SELECT count(*) FROM transfer_idempotency WHERE org_id = ?", orgId)).isZero();
            assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ?", orgId)).isZero();
            assertThat(count("SELECT count(*) FROM sale WHERE org_id = ?", orgId)).isZero();
            assertThat(count("SELECT count(*) FROM batch_risk_transition WHERE org_id = ?", orgId)).isZero();
        }
        for (Long batchId : createdBatchIds) {
            assertThat(count("SELECT count(*) FROM batch WHERE id = ?", batchId)).isZero();
            assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId)).isZero();
            assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", batchId)).isZero();
        }
        for (Long siteId : createdSiteIds) {
            assertThat(count("SELECT count(*) FROM site WHERE id = ?", siteId)).isZero();
        }
        createdTransferIds.clear();
        createdShipmentIds.clear();
        createdBatchIds.clear();
        createdProductIds.clear();
        createdUserIds.clear();
        createdOrgIds.clear();
        createdRoleIds.clear();
        createdOperationIds.clear();
        createdSiteIds.clear();
    }

    // =========================================================================
    // HTTP 辅助
    // =========================================================================

    protected static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    protected MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    protected MockHttpServletRequestBuilder postJson(MockHttpSession session, String url, String idempotencyKey, Object body) throws Exception {
        MockHttpServletRequestBuilder b = post(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            b = b.header("Idempotency-Key", idempotencyKey);
        }
        return body == null ? b : b.content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder getReq(MockHttpSession session, String url) {
        return get(url).session(session);
    }

    protected MockHttpServletRequestBuilder deleteReq(MockHttpSession session, String url) {
        return delete(url).session(session).with(csrf());
    }

    /**
     * 执行请求并断言 HTTP 状态，返回完整响应体 JSON。
     */
    protected JsonNode expect(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = perform(request);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("unexpected status, body=%s", body)
                .isEqualTo(expectedStatus);
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    protected void expectProblem(MockHttpServletRequestBuilder request, int expectedStatus, String expectedCode) throws Exception {
        JsonNode body = expect(request, expectedStatus);
        assertThat(body.path("code").asString()).as("problem=%s", body).isEqualTo(expectedCode);
    }

    // =========================================================================
    // 业务动作辅助
    // =========================================================================

    protected Long createDraftTransfer(MockHttpSession session, Long batchId, Long receiverOrgId) throws Exception {
        JsonNode data = expect(postJson(session, "/api/v1/transfers", key("idem-trf-c"),
                new TransferCreateRequest(batchId, receiverOrgId)), 201).get("data");
        Long id = data.get("id").asLong();
        createdTransferIds.add(id);
        return id;
    }

    protected MockHttpServletRequestBuilder createShipmentRequest(MockHttpSession session, String idemKey, Long carrierOrgId, Long originSiteId, Long destinationSiteId) throws Exception {
        return postJson(session, "/api/v1/shipments", idemKey,
                new ShipmentCreateRequest(carrierOrgId, originSiteId, destinationSiteId, "浙A-" + suffix));
    }

    protected Long createShipment(MockHttpSession session) throws Exception {
        JsonNode data = expect(createShipmentRequest(session, key("idem-shp-c"), carrierOrg.getId(), senderSite.getId(), receiverSite.getId()), 201).get("data");
        Long id = data.get("id").asLong();
        createdShipmentIds.add(id);
        assertThat(data.get("status").asString()).isEqualTo("PLANNED");
        return id;
    }

    protected MockHttpServletRequestBuilder bindRequest(MockHttpSession session, Long shipmentId, Long transferId, long expectedTransferVersion) throws Exception {
        return postJson(session, "/api/v1/shipments/" + shipmentId + "/transfers", key("idem-shp-b"),
                new ShipmentBindTransferRequest(transferId, expectedTransferVersion));
    }

    protected JsonNode bind(Long shipmentId, Long transferId) throws Exception {
        return expect(bindRequest(senderSession, shipmentId, transferId, transferVersion(transferId)), 200).get("data");
    }

    protected MockHttpServletRequestBuilder submitRequest(MockHttpSession session, Long transferId, long expectedVersion) throws Exception {
        return postJson(session, "/api/v1/transfers/" + transferId + "/submit", key("idem-trf-s"),
                new TransferSubmitRequest(expectedVersion));
    }

    protected JsonNode submit(Long transferId) throws Exception {
        return expect(submitRequest(senderSession, transferId, transferVersion(transferId)), 200).get("data");
    }

    protected MockHttpServletRequestBuilder dispatchRequest(MockHttpSession session, Long shipmentId, long expectedVersion, String idemKey) throws Exception {
        return postJson(session, "/api/v1/shipments/" + shipmentId + "/dispatch", idemKey,
                new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), expectedVersion));
    }

    protected JsonNode dispatch(Long shipmentId) throws Exception {
        return expect(dispatchRequest(carrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-shp-d")), 200).get("data");
    }

    protected MockHttpServletRequestBuilder arriveRequest(MockHttpSession session, Long shipmentId, long expectedVersion, String idemKey) throws Exception {
        return postJson(session, "/api/v1/shipments/" + shipmentId + "/arrive", idemKey,
                new ShipmentArriveRequest(OffsetDateTime.now(ZoneOffset.UTC), expectedVersion));
    }

    protected JsonNode arrive(Long shipmentId) throws Exception {
        return expect(arriveRequest(carrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-shp-a")), 200).get("data");
    }

    /**
     * 创建 DRAFT 交接 → 创建 PLANNED 运输任务 → 绑定 → 提交为 PENDING。
     */
    protected Handover preparePendingHandover(Long batchId) throws Exception {
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        bind(shipmentId, transferId);
        submit(transferId);
        return new Handover(transferId, shipmentId);
    }

    /**
     * 在 {@link #preparePendingHandover(Long)} 基础上由承运商确认发运与到达，交接仍为 PENDING。
     */
    protected Handover prepareDeliveredHandover(Long batchId) throws Exception {
        Handover h = preparePendingHandover(batchId);
        dispatch(h.shipmentId());
        arrive(h.shipmentId());
        return h;
    }

    /**
     * 来源企业建批 → Transfer + Shipment 送达 → 加工企业（receiverOrg，PROCESSOR）ACCEPT，返回已由加工企业负责的批次。
     */
    protected Long processorHeldBatch(String externalBatchNo, BigDecimal quantity) throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, externalBatchNo, quantity);
        Handover h = prepareDeliveredHandover(batchId);
        expect(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-trf-acc"),
                new TransferAcceptRequest(quantity, "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(h.transferId()))), 200);
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        return batchId;
    }

    protected MockHttpServletRequestBuilder createOperationRequest(MockHttpSession session, String idemKey, String type, List<BatchOperationItemRequest> items) throws Exception {
        return postJson(session, "/api/v1/batch-operations", idemKey,
                new BatchOperationCreateRequest(type, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5), "Slice 3 测试", items));
    }

    /**
     * 以加工企业身份创建批次操作草稿，返回响应 data。
     */
    protected JsonNode createOperation(String type, List<BatchOperationItemRequest> items) throws Exception {
        JsonNode data = expect(createOperationRequest(receiverSession, key("idem-op-c"), type, items), 201).get("data");
        registerOperation(data);
        return data;
    }

    /**
     * 登记操作及其服务端生成的 OUTPUT 批次，便于 teardown 清理。
     */
    protected void registerOperation(JsonNode operationData) {
        createdOperationIds.add(operationData.get("id").asLong());
        for (JsonNode item : operationData.get("items")) {
            if ("OUTPUT".equals(item.get("role").asString())) {
                createdBatchIds.add(item.get("batchId").asLong());
            }
        }
    }

    protected MockHttpServletRequestBuilder submitOperationRequest(MockHttpSession session, Long operationId, long version, String idemKey) throws Exception {
        return postJson(session, "/api/v1/batch-operations/" + operationId + "/submit", idemKey, new BatchOperationSubmitRequest(version));
    }

    /**
     * 以加工企业身份创建并提交批次操作，返回提交后的响应 data。
     */
    protected JsonNode createAndSubmitOperation(String type, List<BatchOperationItemRequest> items) throws Exception {
        JsonNode draft = createOperation(type, items);
        return expect(submitOperationRequest(receiverSession, draft.get("id").asLong(), draft.get("version").asLong(), key("idem-op-s")), 200).get("data");
    }

    protected static BatchOperationItemRequest opInput(Long batchId, String qty) {
        return new BatchOperationItemRequest("INPUT", batchId, new BigDecimal(qty));
    }

    protected static BatchOperationItemRequest opOutput(String qty) {
        return new BatchOperationItemRequest("OUTPUT", null, new BigDecimal(qty));
    }

    protected static BatchOperationItemRequest opOther(String role, String qty) {
        return new BatchOperationItemRequest(role, null, new BigDecimal(qty));
    }

    protected long transferVersion(Long transferId) {
        return jdbcTemplate.queryForObject("SELECT version FROM transfer WHERE id = ?", Long.class, transferId);
    }

    protected long shipmentVersion(Long shipmentId) {
        return jdbcTemplate.queryForObject("SELECT version FROM shipment WHERE id = ?", Long.class, shipmentId);
    }

    protected String shipmentStatus(Long shipmentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM shipment WHERE id = ?", String.class, shipmentId);
    }

    protected String transferStatus(Long transferId) {
        return jdbcTemplate.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId);
    }

    protected Long batchOrgId(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT org_id FROM batch WHERE id = ?", Long.class, batchId);
    }

    protected int countEvents(Long batchId, String eventType) {
        return count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type = ?", batchId, eventType);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // =========================================================================
    // 基础数据夹具
    // =========================================================================

    protected Long createAndSubmitActiveBatch(MockHttpSession session, String externalBatchNo, BigDecimal quantity) throws Exception {
        return createAndSubmitActiveBatch(session, externalBatchNo, quantity, key("idem-bat-c"));
    }

    protected Long createAndSubmitActiveBatch(MockHttpSession session, String externalBatchNo, BigDecimal quantity, String idemCreate) throws Exception {
        BatchCreateRequest createReq = new BatchCreateRequest(
                externalBatchNo, testProduct.getId(),
                quantity, "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180
        );
        JsonNode data = expect(postJson(session, "/api/v1/batches", idemCreate, createReq), 201).get("data");
        Long batchId = data.get("id").asLong();
        createdBatchIds.add(batchId);
        expect(postJson(session, "/api/v1/batches/" + batchId + "/submit", key("idem-bat-s"), new BatchSubmitRequest(0L)), 200);
        return batchId;
    }

    protected MockHttpSession login(String username) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, DEFAULT_PASSWORD))))
                .andReturn();
        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) mvcResult.getRequest().getSession(false);
    }

    /**
     * 为组织创建一个指定角色的账号并登录。
     */
    protected MockHttpSession newSession(Organization org, String usernamePrefix, String roleCode, String scopeType) throws Exception {
        Role role = getOrCreateRole(roleCode, roleCode, scopeType);
        AppUser user = createUser(org.getId(), usernamePrefix + "_" + suffix);
        bindUserRole(user.getId(), role.getId());
        return login(user.getUsername());
    }

    protected Organization createOrg(String orgNo, String name, String orgType) {
        Organization org = new Organization();
        org.setOrgNo(orgNo);
        org.setName(name);
        org.setOrgType(orgType);
        org.setStatus("ACTIVE");
        org.setVersion(0L);
        org.setIsDeleted(0);
        org.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());
        return org;
    }

    protected Site createSite(Long orgId, String siteNo, String name, String siteType) {
        Site site = new Site();
        site.setOrgId(orgId);
        site.setSiteNo(siteNo);
        site.setName(name);
        site.setSiteType(siteType);
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        site.setVersion(0L);
        site.setIsDeleted(0);
        site.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        site.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        siteMapper.insert(site);
        createdSiteIds.add(site.getId());
        return site;
    }

    protected Role getOrCreateRole(String roleCode, String name, String scopeType) {
        Role existing = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, roleCode));
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

    protected AppUser createUser(Long orgId, String username) {
        AppUser user = new AppUser();
        user.setOrgId(orgId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setStatus("ACTIVE");
        user.setVersion(0L);
        user.setIsDeleted(0);
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        appUserMapper.insert(user);
        createdUserIds.add(user.getId());
        return user;
    }

    protected void bindUserRole(Long userId, Long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        ur.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRoleMapper.insert(ur);
    }

    protected Product createProduct(String productCode, String publicName) {
        Product p = new Product();
        p.setProductCode(productCode);
        p.setPublicName(publicName);
        p.setCategory("FISH");
        p.setSpecification("500g/条");
        p.setSourceType("DOMESTIC_CAPTURE");
        p.setBaseUnitCode("kg");
        p.setStatus("ACTIVE");
        p.setVersion(0L);
        p.setIsDeleted(0);
        p.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        p.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        productMapper.insert(p);
        createdProductIds.add(p.getId());
        return p;
    }

    private void clean(String sql, List<Long> ids) {
        clean(sql, ids, 1);
    }

    /**
     * 按 ID 逐条执行清理语句；{@code bindCount} 表示同一 ID 在语句中绑定的次数。
     */
    private void clean(String sql, List<Long> ids, int bindCount) {
        for (Long id : ids) {
            try {
                Object[] args = new Object[bindCount];
                java.util.Arrays.fill(args, id);
                jdbcTemplate.update(sql, args);
            } catch (Exception ignored) {
                // 清理阶段容错：依赖顺序已保证，个别表不存在对应行时忽略
            }
        }
    }
}
