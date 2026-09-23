package com.example.traceability.trace;

import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.dto.TransferCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A Slice 5 终端 Sale 真实 MySQL 8.4 端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。零售批次均通过真实 API 获得：来源企业建批 → Transfer + Shipment 送达 →
 * 零售企业 ACCEPT。覆盖部分销售、售罄关闭、首次销售锁定、超卖、STORE 规则、幂等，以及
 * 真实双服务竞态（屏障并发）与确定性交错（持有批次行锁的 JDBC holder + performance_schema 锁等待同步点）。
 * 需要 JDBC holder 直接写入"首次销售"事实的用例在名称中标明"防御性"：它们验证不变量，而不是可由 API 真实触达的双服务竞态。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("终端 Sale MySQL 8.4 端到端集成测试")
class SaleMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    private Site inactiveStore;
    private Site retailerColdStore;
    private Site foreignStore;

    @BeforeEach
    void setUpRetailer() throws Exception {
        useRetailerReceiver();
        inactiveStore = createSite(receiverOrg.getId(), "RT-OFF-" + suffix, "停用门店-" + suffix, "STORE");
        jdbcTemplate.update("UPDATE site SET status = 'INACTIVE' WHERE id = ?", inactiveStore.getId());
        retailerColdStore = createSite(receiverOrg.getId(), "RT-COLD-" + suffix, "零售冷库-" + suffix, "COLD_STORE");
        foreignStore = createSite(senderOrg.getId(), "SRC-STORE-" + suffix, "来源企业门店-" + suffix, "STORE");
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private static OffsetDateTime occurredAt() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.MICROS);
    }

    private MockHttpServletRequestBuilder saleRequest(MockHttpSession session, Long batchId, Long siteId, String qty,
                                                      OffsetDateTime at, String idemKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", siteId);
        body.put("quantity", new BigDecimal(qty));
        body.put("occurredAt", at.toString());
        return postJson(session, "/api/v1/batches/" + batchId + "/sales", idemKey, body);
    }

    private JsonNode sell(Long batchId, String qty) throws Exception {
        return expect(saleRequest(receiverSession, batchId, receiverSite.getId(), qty, occurredAt(), key("idem-sale")), 201).get("data");
    }

    private JsonNode batchView(Long batchId) throws Exception {
        return expect(getReq(receiverSession, "/api/v1/batches/" + batchId), 200).get("data");
    }

    private int saleRows(Long batchId) {
        return count("SELECT count(*) FROM sale WHERE batch_id = ?", batchId);
    }

    private BigDecimal soldTotal(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM sale WHERE batch_id = ? AND status = 'SUBMITTED'", BigDecimal.class, batchId);
    }

    /** 与 Sale 一一对应的 SALE 事件：系统键、组织、门店、业务时间与 details.sourceObjectId 全部来自同一行 Sale。 */
    private int saleEventsBackedBySale(Long batchId) {
        return count("""
                SELECT count(*) FROM trace_event e JOIN sale s ON e.idempotency_key = CONCAT('SYS:SALE:SALE:', s.id)
                WHERE e.batch_id = ? AND e.event_type = 'SALE' AND s.batch_id = e.batch_id AND e.org_id = s.org_id
                  AND e.site_id = s.site_id AND e.occurred_at = s.occurred_at AND e.status = 'SUBMITTED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.sourceObjectType')) = 'SALE'
                  AND CAST(JSON_EXTRACT(e.details_json, '$.sourceObjectId') AS UNSIGNED) = s.id
                """, batchId);
    }

    private void assertSaleLedgerConsistent(Long batchId, int expectedSales) {
        assertThat(saleRows(batchId)).isEqualTo(expectedSales);
        assertThat(countEvents(batchId, "SALE")).isEqualTo(expectedSales);
        assertThat(saleEventsBackedBySale(batchId)).isEqualTo(expectedSales);
        BigDecimal declared = jdbcTemplate.queryForObject("SELECT quantity FROM batch WHERE id = ?", BigDecimal.class, batchId);
        assertThat(soldTotal(batchId)).isLessThanOrEqualTo(declared);
    }

    private Map<String, Object> batchRow(Long batchId) {
        return jdbcTemplate.queryForMap("SELECT org_id, flow_status, risk_status, trace_batch_no, quantity, first_sale_id, version FROM batch WHERE id = ?", batchId);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asString();
    }

    private interface Call {
        MvcResult run() throws Exception;
    }

    private interface HolderWork {
        void run(Connection holder) throws Exception;
    }

    /**
     * 确定性交错：holder 先持有批次行锁，被测请求启动并确认阻塞在 batch 行锁上，holder 写入竞争事实并提交，然后返回被测请求结果。
     */
    private MvcResult whileBatchLockHeld(Long batchId, Call request, HolderWork work) throws Exception {
        AtomicReference<MvcResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(request.run());
            } catch (Throwable t) {
                error.set(t);
            }
        }, "slice5-race-worker");
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM batch WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, batchId);
                ps.executeQuery().close();
            }
            worker.start();
            awaitRowLockWait(worker, "batch");
            work.run(holder);
            holder.commit();
        } finally {
            try {
                if (!holder.getAutoCommit()) {
                    holder.rollback();
                }
            } finally {
                holder.close();
            }
            if (worker.getState() != Thread.State.NEW) {
                worker.join(30_000);
                assertThat(worker.isAlive()).as("race worker did not finish").isFalse();
            }
        }
        if (error.get() != null) {
            throw new AssertionError("race request failed", error.get());
        }
        return result.get();
    }

    /** holder 在同一事务内写入一笔合法形状的 Sale 并回写首次销售标记（可选售罄关闭）。 */
    private HolderWork seedSale(Long batchId, Long orgId, Long siteId, String qty, boolean close) {
        return holder -> {
            long saleId;
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO sale (org_id, batch_id, site_id, quantity, unit_code, occurred_at, status, idempotency_key, request_hash, created_by) "
                            + "VALUES (?, ?, ?, ?, 'kg', UTC_TIMESTAMP(6), 'SUBMITTED', ?, REPEAT('h', 64), ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, orgId);
                ps.setLong(2, batchId);
                ps.setLong(3, siteId);
                ps.setBigDecimal(4, new BigDecimal(qty));
                ps.setString(5, "holder-" + java.util.UUID.randomUUID());
                ps.setLong(6, receiverUser.getId());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    saleId = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = holder.prepareStatement(
                    "UPDATE batch SET first_sale_id = COALESCE(first_sale_id, ?), flow_status = IF(?, 'CLOSED', flow_status), version = version + 1 WHERE id = ?")) {
                ps.setLong(1, saleId);
                ps.setBoolean(2, close);
                ps.setLong(3, batchId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        };
    }

    private MvcResult[] race(Call first, Call second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<MvcResult> r1 = new AtomicReference<>();
        AtomicReference<MvcResult> r2 = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runRace(barrier, latch, first, r1, err));
            executor.submit(() -> runRace(barrier, latch, second, r2, err));
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        }
        if (err.get() != null) {
            throw new AssertionError("concurrent request failed", err.get());
        }
        return new MvcResult[]{r1.get(), r2.get()};
    }

    private static void runRace(CyclicBarrier barrier, CountDownLatch latch, Call call,
                                AtomicReference<MvcResult> out, AtomicReference<Throwable> err) {
        try {
            barrier.await();
            out.set(call.run());
        } catch (Throwable t) {
            err.set(t);
        } finally {
            latch.countDown();
        }
    }

    // =========================================================================
    // 黄金链
    // =========================================================================

    @Test
    @DisplayName("黄金链：零售 ACCEPT 后 600kg → 售 200（剩余 400，ACTIVE，首次销售锁定交接）→ 售 400（剩余 0，CLOSED）→ 再售 422；责任组织 / 风险 / 身份不变；每笔恰好一条 SALE")
    void partialSaleThenSellOut() throws Exception {
        Long b2 = receiverHeldBatch("S5-B2-" + suffix, new BigDecimal("600.000"));
        Map<String, Object> before = batchRow(b2);

        JsonNode first = sell(b2, "200");
        assertThat(first.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(first.get("siteId").asLong()).isEqualTo(receiverSite.getId());
        JsonNode view = batchView(b2);
        assertThat(new BigDecimal(view.get("remainingQuantity").toString())).isEqualByComparingTo("400");
        assertThat(view.get("flowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("firstSaleId").asLong()).isEqualTo(first.get("id").asLong());
        Map<String, Object> afterFirst = batchRow(b2);
        assertThat(afterFirst.get("org_id")).isEqualTo(before.get("org_id"));
        assertThat(afterFirst.get("risk_status")).isEqualTo("NORMAL");
        assertThat(afterFirst.get("trace_batch_no")).isEqualTo(before.get("trace_batch_no"));
        assertThat(((BigDecimal) afterFirst.get("quantity"))).isEqualByComparingTo("600");
        assertThat((Long) afterFirst.get("version")).isEqualTo((Long) before.get("version") + 1);
        assertSaleLedgerConsistent(b2, 1);

        // 首次有效销售后：剩余量仍 > 0，但交接被永久禁止（409 BATCH_SALE_STARTED），不落交接行
        int transfersBefore = count("SELECT count(*) FROM transfer WHERE batch_id = ?", b2);
        expectProblem(postJson(receiverSession, "/api/v1/transfers", key("idem-trf-after-sale"),
                new TransferCreateRequest(b2, senderOrg.getId())), 409, "BATCH_SALE_STARTED");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", b2)).isEqualTo(transfersBefore);
        // 零售企业不是加工企业：批次操作入口按组织类型拒绝（首次销售守卫本身由下方防御性用例证明）
        expectProblem(createOperationRequest(receiverSession, key("idem-op-after-sale"), "SPLIT",
                List.of(opInput(b2, "400"), opOutput("200"), opOutput("200"))), 403, "ORG_TYPE_NOT_ALLOWED");

        // 售罄关闭
        JsonNode second = sell(b2, "400.000");
        view = batchView(b2);
        assertThat(new BigDecimal(view.get("remainingQuantity").toString())).isEqualByComparingTo("0");
        assertThat(view.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(view.get("riskStatus").asString()).isEqualTo("NORMAL");
        assertThat(view.get("firstSaleId").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(batchRow(b2).get("org_id")).isEqualTo(before.get("org_id"));
        assertSaleLedgerConsistent(b2, 2);
        assertThat(soldTotal(b2)).isEqualByComparingTo("600");

        // CLOSED 后禁止新增销售
        expectProblem(saleRequest(receiverSession, b2, receiverSite.getId(), "1", occurredAt(), key("idem-sale-closed")), 422, "BATCH_FLOW_BLOCKED");
        assertSaleLedgerConsistent(b2, 2);

        // 查询：当前责任组织按时间顺序看到两笔；历史参与的来源企业 403
        JsonNode list = expect(getReq(receiverSession, "/api/v1/batches/" + b2 + "/sales"), 200).get("data");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(list.get(1).get("id").asLong()).isEqualTo(second.get("id").asLong());
        assertThat(list.get(0).get("siteName").asString()).isEqualTo(receiverSite.getName());
        expectProblem(getReq(senderSession, "/api/v1/batches/" + b2 + "/sales"), 403, "ORG_SCOPE_DENIED");

        // 人工事件接口仍不能伪造或更正 SALE
        Map<String, Object> manual = Map.of("eventType", "SALE", "occurredAt", occurredAt().toString(), "dataSource", "MANUAL", "summary", "伪造销售");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + b2 + "/events", key("idem-evt-sale"), manual), 422, "EVENT_TYPE_NOT_MANUAL");
        assertThat(countEvents(b2, "SALE")).isEqualTo(2);
    }

    @Test
    @DisplayName("消费者公开时间线把 SALE 标注为『终端零售销售』（不再是经销出库），只输出标签 / 时间 / 来源")
    void publicTimelineLabelsTerminalSale() throws Exception {
        Long b = receiverHeldBatch("S5-PUB-" + suffix, new BigDecimal("50.000"));
        String publicId = expect(postJson(receiverSession, "/api/v1/batches/" + b + "/public-trace-code/activate", key("idem-ptc"), null), 200)
                .get("data").get("publicId").asString();
        sell(b, "50");
        JsonNode timeline = expect(get("/api/public/v1/public/traces/" + publicId), 200).get("data").get("timeline");
        List<String> labels = new ArrayList<>();
        timeline.forEach(item -> labels.add(item.get("event").asString()));
        assertThat(labels).contains("终端零售销售").doesNotContain("经销零售出库");
        assertThat(timeline.toString()).doesNotContain("零售门店");
    }

    private MockHttpServletRequestBuilder get(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    // =========================================================================
    // 拒绝矩阵
    // =========================================================================

    @Test
    @DisplayName("超卖 422 且零写入；随后恰好售完成功并关闭")
    void oversellRejected() throws Exception {
        Long b = receiverHeldBatch("S5-OVR-" + suffix, new BigDecimal("600.000"));
        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "600.001", occurredAt(), key("idem-ovr")), 422, "SALE_QUANTITY_EXCEEDS_REMAINING");
        assertSaleLedgerConsistent(b, 0);
        assertThat(batchRow(b).get("first_sale_id")).isNull();

        sell(b, "600");
        assertThat(batchRow(b).get("flow_status")).isEqualTo("CLOSED");
        assertSaleLedgerConsistent(b, 1);
    }

    @Test
    @DisplayName("STORE 规则：他组织门店 403、停用门店 422、零售冷库 422、不存在 404、未声明字段 400；均零写入")
    void storeRules() throws Exception {
        Long b = receiverHeldBatch("S5-SITE-" + suffix, new BigDecimal("100.000"));
        expectProblem(saleRequest(receiverSession, b, foreignStore.getId(), "1", occurredAt(), key("idem-s1")), 403, "ORG_SCOPE_DENIED");
        expectProblem(saleRequest(receiverSession, b, inactiveStore.getId(), "1", occurredAt(), key("idem-s2")), 422, "SITE_NOT_ACTIVE");
        expectProblem(saleRequest(receiverSession, b, retailerColdStore.getId(), "1", occurredAt(), key("idem-s3")), 422, "SALE_SITE_TYPE_INVALID");
        expect(saleRequest(receiverSession, b, 999_999_999L, "1", occurredAt(), key("idem-s4")), 404);
        Map<String, Object> smuggled = Map.of("siteId", receiverSite.getId(), "quantity", 1, "occurredAt", occurredAt().toString(), "remainingQuantity", 0);
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + b + "/sales", key("idem-s5"), smuggled), 400, "INVALID_REQUEST");
        assertSaleLedgerConsistent(b, 0);
        assertThat(batchRow(b).get("first_sale_id")).isNull();
    }

    @Test
    @DisplayName("批次授权先于场所探测：他零售企业对本批次销售（引用不存在场所）得到 403 而不是 404；非零售组织 403 ORG_TYPE_NOT_ALLOWED")
    void batchScopeBeforeSiteLookup() throws Exception {
        Long b = receiverHeldBatch("S5-SCOPE-" + suffix, new BigDecimal("100.000"));
        Organization otherRetailer = createOrg("ORG_RT2_" + suffix, "另一零售企业-" + suffix, "RETAILER");
        AppUser other = createUser(otherRetailer.getId(), "rt2_op_" + suffix);
        bindUserRole(other.getId(), operatorRole.getId());
        MockHttpSession otherSession = login(other.getUsername());
        expectProblem(saleRequest(otherSession, b, 999_999_999L, "1", occurredAt(), key("idem-sc1")), 403, "ORG_SCOPE_DENIED");
        expectProblem(saleRequest(otherSession, b, foreignStore.getId(), "1", occurredAt(), key("idem-sc2")), 403, "ORG_SCOPE_DENIED");
        expectProblem(saleRequest(senderSession, b, foreignStore.getId(), "1", occurredAt(), key("idem-sc3")), 403, "ORG_TYPE_NOT_ALLOWED");
        assertSaleLedgerConsistent(b, 0);
    }

    @Test
    @DisplayName("未结束交接时禁止销售 409 BATCH_TRANSFER_OPEN，零写入")
    void openTransferBlocksSale() throws Exception {
        Long b = receiverHeldBatch("S5-OPEN-" + suffix, new BigDecimal("100.000"));
        createDraftTransfer(receiverSession, b, senderOrg.getId());
        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "1", occurredAt(), key("idem-open")), 409, "BATCH_TRANSFER_OPEN");
        assertSaleLedgerConsistent(b, 0);
    }

    @Test
    @DisplayName("历史部分 INPUT 用量（v1.1 之前，无全量消耗标记）的 ACTIVE 批次：Sale 409 BATCH_ALREADY_CONSUMED，无 Sale、无 SALE 事件")
    void legacyPartialInputRejected() throws Exception {
        Long b = receiverHeldBatch("S5-LEG-" + suffix, new BigDecimal("1000.000"));
        jdbcTemplate.update("INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key) "
                + "VALUES (?, ?, 'PROCESS', UTC_TIMESTAMP(6), 'SUBMITTED', ?)", receiverOrg.getId(), "OP-LEG-" + suffix, "leg-op-" + suffix);
        Long opId = jdbcTemplate.queryForObject("SELECT id FROM batch_operation WHERE operation_no = ?", Long.class, "OP-LEG-" + suffix);
        createdOperationIds.add(opId);
        jdbcTemplate.update("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (?, ?, 'INPUT', 400.000, 'kg', 400.000)", opId, b);
        assertThat(batchRow(b).get("flow_status")).isEqualTo("ACTIVE");

        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "1", occurredAt(), key("idem-leg")), 409, "BATCH_ALREADY_CONSUMED");
        assertSaleLedgerConsistent(b, 0);
        assertThat(batchRow(b).get("first_sale_id")).isNull();
    }

    @Test
    @DisplayName("原子性：SALE 事件身份被占用时整笔售罄销售回滚——409 SALE_EVENT_CONFLICT，无 Sale 行、first_sale_id 仍为 NULL、流转状态与版本不变；释放后同一销售成功")
    void saleEventFailureRollsBackSaleAndBatchMarker() throws Exception {
        Long b = receiverHeldBatch("S5-RB-" + suffix, new BigDecimal("300.000"));
        Map<String, Object> before = batchRow(b);

        // 读取 sale 表下一个自增值（关闭 information_schema 统计缓存），并预先占用接下来若干个 SALE 事件系统身份
        long nextSaleId;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET SESSION information_schema_stats_expiry = 0");
            try (ResultSet rs = s.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sale'")) {
                rs.next();
                nextSaleId = rs.getLong(1);
            }
        }
        for (long id = nextSaleId; id < nextSaleId + 5; id++) {
            jdbcTemplate.update("INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, summary, idempotency_key) "
                    + "VALUES (?, ?, 'WAREHOUSE_IN', UTC_TIMESTAMP(6), '占用 SALE 事件身份', ?)", b, receiverOrg.getId(), "SYS:SALE:SALE:" + id);
        }

        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "300", occurredAt(), key("idem-rb")), 409, "SALE_EVENT_CONFLICT");
        assertThat(saleRows(b)).isZero();
        assertThat(countEvents(b, "SALE")).isZero();
        Map<String, Object> after = batchRow(b);
        assertThat(after.get("first_sale_id")).isNull();
        assertThat(after.get("flow_status")).isEqualTo("ACTIVE");
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(new BigDecimal(batchView(b).get("remainingQuantity").toString())).isEqualByComparingTo("300");

        // 释放被占用的身份后，同一售罄销售完整成功（Sale + 标记 + CLOSED + 恰好一条 SALE）
        jdbcTemplate.update("DELETE FROM trace_event WHERE batch_id = ? AND event_type = 'WAREHOUSE_IN' AND idempotency_key LIKE 'SYS:SALE:SALE:%'", b);
        sell(b, "300");
        assertThat(batchRow(b).get("flow_status")).isEqualTo("CLOSED");
        assertSaleLedgerConsistent(b, 1);
    }

    @Test
    @DisplayName("部分销售后批次仍 ACTIVE：本组织冷库出入库仍允许（契约只锁交接与批次操作）；售罄 CLOSED 后冷库事件 422 BATCH_FLOW_BLOCKED")
    void warehouseAllowedAfterPartialSaleBlockedAfterSellOut() throws Exception {
        Long b = receiverHeldBatch("S5-WH-" + suffix, new BigDecimal("300.000"));
        sell(b, "100");
        Map<String, Object> inbound = Map.of("eventType", "WAREHOUSE_IN", "siteId", retailerColdStore.getId(),
                "occurredAt", occurredAt().toString(), "dataSource", "MANUAL", "summary", "零售冷库入库");
        expect(postJson(receiverSession, "/api/v1/batches/" + b + "/events", key("idem-wh-in"), inbound), 201);
        assertThat(batchRow(b).get("flow_status")).isEqualTo("ACTIVE");

        sell(b, "200");
        Map<String, Object> outbound = Map.of("eventType", "WAREHOUSE_OUT", "siteId", retailerColdStore.getId(),
                "occurredAt", occurredAt().toString(), "dataSource", "MANUAL", "summary", "零售冷库出库");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + b + "/events", key("idem-wh-out"), outbound), 422, "BATCH_FLOW_BLOCKED");
        assertThat(countEvents(b, "WAREHOUSE_IN")).isEqualTo(1);
        assertThat(countEvents(b, "WAREHOUSE_OUT")).isZero();
        assertSaleLedgerConsistent(b, 2);
    }

    // =========================================================================
    // 幂等
    // =========================================================================

    @Test
    @DisplayName("幂等：同键同载荷重放同一 Sale（售罄关闭后仍重放）；同键改载荷 409；仅相差 1 微秒的业务时间视为不同载荷")
    void idempotency() throws Exception {
        Long b = receiverHeldBatch("S5-IDEM-" + suffix, new BigDecimal("300.000"));
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2).withNano(123_456_000);
        String k = key("idem-same");

        JsonNode first = expect(saleRequest(receiverSession, b, receiverSite.getId(), "300", at, k), 201).get("data");
        assertThat(batchRow(b).get("flow_status")).isEqualTo("CLOSED");
        assertThat(first.get("occurredAt").asString())
                .isEqualTo(at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX")));
        OffsetDateTime persisted = jdbcTemplate.queryForObject("SELECT occurred_at FROM sale WHERE id = ?", java.time.LocalDateTime.class, first.get("id").asLong()).atOffset(ZoneOffset.UTC);
        assertThat(persisted).isEqualTo(at);

        // 售罄关闭后重放：返回原 Sale，不写第二行
        JsonNode replay = expect(saleRequest(receiverSession, b, receiverSite.getId(), "300.000", at.withOffsetSameInstant(ZoneOffset.ofHours(8)), k), 201).get("data");
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        // 改载荷 409
        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "299", at, k), 409, "IDEMPOTENCY_CONFLICT");
        // 亚毫秒（微秒）不同 409：哈希与落库同为微秒精度
        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "300", at.plusNanos(1_000), k), 409, "IDEMPOTENCY_CONFLICT");
        assertSaleLedgerConsistent(b, 1);
    }

    // =========================================================================
    // 真实双服务并发（屏障）
    // =========================================================================

    @Test
    @DisplayName("并发 400 ∥ 400（600kg）：恰好一个 201、一个 422 超卖；1 行 Sale、1 条 SALE")
    void concurrentOversell() throws Exception {
        Long b = receiverHeldBatch("S5-C1-" + suffix, new BigDecimal("600.000"));
        MvcResult[] r = race(
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "400", occurredAt(), key("idem-c1a"))),
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "400", occurredAt(), key("idem-c1b"))));
        assertThat(List.of(status(r[0]), status(r[1]))).containsExactlyInAnyOrder(201, 422);
        MvcResult loser = status(r[0]) == 422 ? r[0] : r[1];
        assertThat(code(loser)).isEqualTo("SALE_QUANTITY_EXCEEDS_REMAINING");
        assertSaleLedgerConsistent(b, 1);
        assertThat(soldTotal(b)).isEqualByComparingTo("400");
        assertThat(batchRow(b).get("flow_status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("并发 300 ∥ 300（600kg）：两个 201，恰好售罄 CLOSED，2 行 Sale、2 条 SALE，首次销售标记为其中一笔")
    void concurrentSellToZero() throws Exception {
        Long b = receiverHeldBatch("S5-C2-" + suffix, new BigDecimal("600.000"));
        MvcResult[] r = race(
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "300", occurredAt(), key("idem-c2a"))),
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "300", occurredAt(), key("idem-c2b"))));
        assertThat(List.of(status(r[0]), status(r[1]))).containsExactly(201, 201);
        assertSaleLedgerConsistent(b, 2);
        Map<String, Object> row = batchRow(b);
        assertThat(row.get("flow_status")).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ? AND id = ?", b, row.get("first_sale_id"))).isEqualTo(1);
        assertThat(((Number) row.get("first_sale_id")).longValue()).isEqualTo(jdbcTemplate.queryForObject("SELECT MIN(id) FROM sale WHERE batch_id = ?", Long.class, b));
    }

    @Test
    @DisplayName("并发同键同载荷：两个 201 且同一 id，1 行 Sale、1 条 SALE")
    void concurrentSameKey() throws Exception {
        Long b = receiverHeldBatch("S5-C3-" + suffix, new BigDecimal("600.000"));
        OffsetDateTime at = occurredAt();
        String k = key("idem-c3");
        MvcResult[] r = race(
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "100", at, k)),
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "100", at, k)));
        assertThat(List.of(status(r[0]), status(r[1]))).containsExactly(201, 201);
        assertThat(objectMapper.readTree(r[0].getResponse().getContentAsString()).get("data").get("id").asLong())
                .isEqualTo(objectMapper.readTree(r[1].getResponse().getContentAsString()).get("data").get("id").asLong());
        assertSaleLedgerConsistent(b, 1);
    }

    @Test
    @DisplayName("并发同键不同载荷：恰好一个 201、一个 409 IDEMPOTENCY_CONFLICT，1 行 Sale")
    void concurrentSameKeyDifferentPayload() throws Exception {
        Long b = receiverHeldBatch("S5-C4-" + suffix, new BigDecimal("600.000"));
        OffsetDateTime at = occurredAt();
        String k = key("idem-c4");
        MvcResult[] r = race(
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "100", at, k)),
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "101", at, k)));
        assertThat(List.of(status(r[0]), status(r[1]))).containsExactlyInAnyOrder(201, 409);
        assertSaleLedgerConsistent(b, 1);
    }

    @Test
    @DisplayName("真实双向竞态 Sale ∥ Transfer create：恰好一方成功——Sale 先提交则交接 409 BATCH_SALE_STARTED，交接先提交则销售 409 BATCH_TRANSFER_OPEN")
    void concurrentSaleVsTransferCreate() throws Exception {
        Long b = receiverHeldBatch("S5-C5-" + suffix, new BigDecimal("600.000"));
        MvcResult[] r = race(
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "100", occurredAt(), key("idem-c5s"))),
                () -> perform(postJson(receiverSession, "/api/v1/transfers", key("idem-c5t"), new TransferCreateRequest(b, senderOrg.getId()))));
        int sale = status(r[0]);
        int transfer = status(r[1]);
        if (sale == 201) {
            assertThat(transfer).isEqualTo(409);
            assertThat(code(r[1])).isEqualTo("BATCH_SALE_STARTED");
            assertSaleLedgerConsistent(b, 1);
            assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ? AND status IN ('DRAFT','PENDING')", b)).isZero();
        } else {
            assertThat(sale).isEqualTo(409);
            assertThat(code(r[0])).isEqualTo("BATCH_TRANSFER_OPEN");
            assertThat(transfer).isEqualTo(201);
            assertSaleLedgerConsistent(b, 0);
            createdTransferIds.add(objectMapper.readTree(r[1].getResponse().getContentAsString()).get("data").get("id").asLong());
        }
    }

    // =========================================================================
    // 确定性交错（holder 持有批次行锁）
    // =========================================================================

    @Test
    @DisplayName("确定性：Sale 阻塞在批次行锁期间另一笔销售提交（剩余降为 100），取得锁后按最新剩余量 422 超卖，零写入")
    void saleWaitingSeesCommittedSale_noOversell() throws Exception {
        Long b = receiverHeldBatch("S5-D1-" + suffix, new BigDecimal("600.000"));
        MvcResult r = whileBatchLockHeld(b,
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "200", occurredAt(), key("idem-d1"))),
                seedSale(b, receiverOrg.getId(), receiverSite.getId(), "500", false));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("SALE_QUANTITY_EXCEEDS_REMAINING");
        assertThat(saleRows(b)).isEqualTo(1);
        assertThat(soldTotal(b)).isEqualByComparingTo("500");
        assertThat(countEvents(b, "SALE")).isZero();
    }

    @Test
    @DisplayName("确定性（REPEATABLE READ 回归）：交接创建阻塞在批次行锁期间首次销售提交，取得锁后当前读看到 first_sale_id → 409 BATCH_SALE_STARTED")
    void transferCreateWaitingSeesCommittedFirstSale() throws Exception {
        Long b = receiverHeldBatch("S5-D2-" + suffix, new BigDecimal("600.000"));
        MvcResult r = whileBatchLockHeld(b,
                () -> perform(postJson(receiverSession, "/api/v1/transfers", key("idem-d2"), new TransferCreateRequest(b, senderOrg.getId()))),
                seedSale(b, receiverOrg.getId(), receiverSite.getId(), "100", false));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("BATCH_SALE_STARTED");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", b)).isEqualTo(1);
    }

    @Test
    @DisplayName("确定性：Sale 阻塞在批次行锁期间交接草稿提交，取得锁后 409 BATCH_TRANSFER_OPEN，零写入")
    void saleWaitingSeesCommittedDraftTransfer() throws Exception {
        Long b = receiverHeldBatch("S5-D3-" + suffix, new BigDecimal("600.000"));
        MvcResult r = whileBatchLockHeld(b,
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "100", occurredAt(), key("idem-d3"))),
                holder -> {
                    try (PreparedStatement ps = holder.prepareStatement(
                            "INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code, status, idempotency_key) "
                                    + "VALUES (?, ?, ?, ?, 600.000, 'kg', 'DRAFT', ?)")) {
                        ps.setString(1, "TRF-D3-" + suffix);
                        ps.setLong(2, b);
                        ps.setLong(3, receiverOrg.getId());
                        ps.setLong(4, senderOrg.getId());
                        ps.setString(5, "d3-" + suffix);
                        ps.executeUpdate();
                    }
                });
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("BATCH_TRANSFER_OPEN");
        assertSaleLedgerConsistent(b, 0);
        assertThat(batchRow(b).get("first_sale_id")).isNull();
    }

    @Test
    @DisplayName("确定性：Sale 阻塞在批次行锁期间另一笔售罄销售提交（CLOSED），取得锁后 422 BATCH_FLOW_BLOCKED")
    void saleWaitingSeesSellToZero() throws Exception {
        Long b = receiverHeldBatch("S5-D4-" + suffix, new BigDecimal("600.000"));
        MvcResult r = whileBatchLockHeld(b,
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "1", occurredAt(), key("idem-d4"))),
                seedSale(b, receiverOrg.getId(), receiverSite.getId(), "600", true));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(saleRows(b)).isEqualTo(1);
        assertThat(countEvents(b, "SALE")).isZero();
    }

    @Test
    @DisplayName("防御性（种子态）：Sale 阻塞在批次行锁期间批次被操作全量消耗（CLOSED + consumed_by），取得锁后按实际校验顺序 422 BATCH_FLOW_BLOCKED")
    void defensive_saleWaitingSeesConsumption() throws Exception {
        Long b = receiverHeldBatch("S5-D5-" + suffix, new BigDecimal("600.000"));
        MvcResult r = whileBatchLockHeld(b,
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "1", occurredAt(), key("idem-d5"))),
                holder -> {
                    long opId;
                    try (PreparedStatement ps = holder.prepareStatement(
                            "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key) "
                                    + "VALUES (?, ?, 'PROCESS', UTC_TIMESTAMP(6), 'SUBMITTED', ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, receiverOrg.getId());
                        ps.setString(2, "OP-D5-" + suffix);
                        ps.setString(3, "d5-" + suffix);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            opId = keys.getLong(1);
                        }
                    }
                    try (PreparedStatement ps = holder.prepareStatement("UPDATE batch SET flow_status = 'CLOSED', consumed_by_operation_id = ? WHERE id = ?")) {
                        ps.setLong(1, opId);
                        ps.setLong(2, b);
                        ps.executeUpdate();
                    }
                });
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("BATCH_FLOW_BLOCKED");
        assertSaleLedgerConsistent(b, 0);
    }

    @Test
    @DisplayName("防御性（种子态）：已绑定运输任务的交接草稿提交阻塞在批次行锁期间首次销售写入，取得锁后 409 BATCH_SALE_STARTED，交接保持 DRAFT")
    void defensive_transferSubmitWaitingSeesFirstSale() throws Exception {
        Long b = receiverHeldBatch("S5-D6-" + suffix, new BigDecimal("600.000"));
        Long transferId = createDraftTransfer(receiverSession, b, senderOrg.getId());
        JsonNode shipment = expect(createShipmentRequest(receiverSession, key("idem-d6-shp"), carrierOrg.getId(), receiverSite.getId(), senderSite.getId()), 201).get("data");
        Long shipmentId = shipment.get("id").asLong();
        createdShipmentIds.add(shipmentId);
        expect(bindRequest(receiverSession, shipmentId, transferId, transferVersion(transferId)), 200);
        long version = transferVersion(transferId);

        MvcResult r = whileBatchLockHeld(b,
                () -> perform(submitRequest(receiverSession, transferId, version)),
                seedSale(b, receiverOrg.getId(), receiverSite.getId(), "100", false));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("BATCH_SALE_STARTED");
        assertThat(transferStatus(transferId)).isEqualTo("DRAFT");
    }
}
