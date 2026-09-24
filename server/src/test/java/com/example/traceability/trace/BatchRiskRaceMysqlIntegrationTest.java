package com.example.traceability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB1 批次风险状态确定性竞态（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * <b>幂等门闩（gate）</b>：holder JDBC 连接先插入一行<b>未提交</b>的门闩行，其唯一键与"先行请求"在取得批次行锁
 * <b>之后</b>才会插入的唯一键相同（风险台账 / 销售台账 / 交接幂等记录 / PROCESS 系统事件的组织内幂等键），
 * 门闩行引用一个与本用例无关的哑批次，因此 holder 的外键共享锁不会落在被测批次上；交接接受在锁定批次前就锁定读了自己的幂等键，
 * 因此改用公开追溯码行锁作为门闩（接受在更新批次之后才转移公开码归属）。先行请求随即在持有批次行锁的状态下
 * 阻塞于门闩（performance_schema 同步点确认）；再启动"后行请求"并确认其阻塞于 batch 行锁；最后 holder 回滚，
 * 先行请求完成提交，后行请求随后取得批次行锁并看到先行请求的结果。由此两个方向的先后顺序都是确定的。
 * </p>
 * <p>
 * 这也证明了 PB1 的锁顺序：风险台账 / 幂等索引锁只在批次行锁之后获取（先行冻结请求确实在持有批次锁时等待台账唯一索引），
 * 且不存在"持有台账 / 幂等索引锁再请求批次锁"的路径，不引入反向业务锁边。屏障并发用例补充验证无死锁与合法串行化结果。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("批次风险状态确定性竞态 MySQL 8.4 集成测试")
class BatchRiskRaceMysqlIntegrationTest extends AbstractBatchRiskMysqlIT {

    private interface Call {
        MvcResult run() throws Exception;
    }

    private interface Gate {
        void insert(Connection holder) throws Exception;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asString();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private Thread worker(String name, Call call, AtomicReference<MvcResult> out, AtomicReference<Throwable> err) {
        return new Thread(() -> {
            try {
                out.set(call.run());
            } catch (Throwable t) {
                err.set(t);
            }
        }, name);
    }

    private static void join(Thread t) throws InterruptedException {
        if (t.getState() != Thread.State.NEW) {
            t.join(30_000);
            assertThat(t.isAlive()).as("race worker %s did not finish", t.getName()).isFalse();
        }
    }

    /**
     * 确定性顺序：门闩行 → 先行请求阻塞于 {@code gateTable}（已持有批次行锁）→ 后行请求阻塞于 batch 行锁 → holder 回滚。
     */
    private MvcResult[] gated(Gate gate, String gateTable, Call first, Call second) throws Exception {
        AtomicReference<MvcResult> r1 = new AtomicReference<>();
        AtomicReference<MvcResult> r2 = new AtomicReference<>();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        Thread w1 = worker("pb1-gated-first", first, r1, e1);
        Thread w2 = worker("pb1-gated-second", second, r2, e2);
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            gate.insert(holder);
            w1.start();
            awaitRowLockWait(w1, gateTable);
            w2.start();
            awaitRowLockWait(w2, "batch");
        } finally {
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
            join(w1);
            join(w2);
        }
        if (e1.get() != null || e2.get() != null) {
            throw new AssertionError("gated request failed", e1.get() != null ? e1.get() : e2.get());
        }
        return new MvcResult[]{r1.get(), r2.get()};
    }

    /**
     * 强制重叠：两个请求都已通过持锁后幂等复读，并同时阻塞在门闩行的唯一索引上；holder 回滚后二者竞争插入（胜者不确定，结果对称）。
     */
    private MvcResult[] overlappedOnGate(Gate gate, String gateTable, Call a, Call b) throws Exception {
        AtomicReference<MvcResult> r1 = new AtomicReference<>();
        AtomicReference<MvcResult> r2 = new AtomicReference<>();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        Thread w1 = worker("pb1-overlap-a", a, r1, e1);
        Thread w2 = worker("pb1-overlap-b", b, r2, e2);
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            gate.insert(holder);
            w1.start();
            awaitRowLockWait(w1, gateTable);
            w2.start();
            awaitRowLockWaits(w2, gateTable, 2);
        } finally {
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
            join(w1);
            join(w2);
        }
        if (e1.get() != null || e2.get() != null) {
            throw new AssertionError("overlapped request failed", e1.get() != null ? e1.get() : e2.get());
        }
        return new MvcResult[]{r1.get(), r2.get()};
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
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
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
    // 门闩行
    // =========================================================================

    /** 风险台账门闩：同组织同幂等键，引用哑批次。 */
    private Gate ledgerGate(Long orgId, String idemKey, Long dummyBatchId) {
        return holder -> {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO batch_risk_transition (batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id, "
                            + "reason, idempotency_key, request_hash, occurred_at) "
                            + "VALUES (?, ?, 'ACTIVE', 'NORMAL', 'FROZEN', 'MANUAL', ?, 'gate', ?, REPEAT('g', 64), UTC_TIMESTAMP(6))")) {
                ps.setLong(1, dummyBatchId);
                ps.setLong(2, orgId);
                ps.setLong(3, senderUser.getId());
                ps.setString(4, idemKey);
                ps.executeUpdate();
            }
        };
    }

    /** 销售台账门闩：同组织同幂等键，引用哑批次与本组织门店。 */
    private Gate saleGate(Long orgId, Long storeId, String idemKey, Long dummyBatchId) {
        return holder -> {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO sale (org_id, batch_id, site_id, quantity, unit_code, occurred_at, status, idempotency_key, request_hash, created_by) "
                            + "VALUES (?, ?, ?, 1, 'kg', UTC_TIMESTAMP(6), 'SUBMITTED', ?, REPEAT('g', 64), ?)")) {
                ps.setLong(1, orgId);
                ps.setLong(2, dummyBatchId);
                ps.setLong(3, storeId);
                ps.setString(4, idemKey);
                ps.setLong(5, receiverUser.getId());
                ps.executeUpdate();
            }
        };
    }

    /**
     * 公开追溯码行锁门闩：交接接受在锁定并更新批次之后才转移公开追溯码归属（步骤 12）。
     * 接受的幂等键在锁定批次之前就做了锁定读，因此不能用交接幂等记录作为接受的门闩。
     */
    private Gate publicCodeRowGate(Long batchId) {
        return holder -> {
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM public_trace_code WHERE batch_id = ? FOR UPDATE")) {
                ps.setLong(1, batchId);
                ps.executeQuery().close();
            }
        };
    }

    /** 交接幂等记录门闩（无外键）：交接创建在锁定批次并插入交接行之后写入。 */
    private Gate transferIdempotencyGate(Long orgId, String idemKey) {
        return holder -> {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO transfer_idempotency (org_id, idempotency_key, action, transfer_id, request_hash) VALUES (?, ?, 'GATE', 0, REPEAT('g', 64))")) {
                ps.setLong(1, orgId);
                ps.setString(2, idemKey);
                ps.executeUpdate();
            }
        };
    }

    /** PROCESS 系统事件门闩：批次操作提交在锁定全部批次之后按系统幂等键写入 PROCESS 事件。 */
    private Gate processEventGate(Long orgId, String systemKey, Long dummyBatchId) {
        return holder -> {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, summary, idempotency_key) VALUES (?, ?, 'PROCESS', UTC_TIMESTAMP(6), 'gate', ?)")) {
                ps.setLong(1, dummyBatchId);
                ps.setLong(2, orgId);
                ps.setString(3, systemKey);
                ps.executeUpdate();
            }
        };
    }

    private Long dummyBatch() throws Exception {
        return createAndSubmitActiveBatch(senderSession, "PB1-GATE-" + suffix + "-" + createdBatchIds.size(), new BigDecimal("1.000"));
    }

    // =========================================================================
    // 冻结 ∥ 终端销售
    // =========================================================================

    @Test
    @DisplayName("冻结先行 → 销售 422 BATCH_FLOW_BLOCKED，零销售、数量不变")
    void freezeFirst_thenSale() throws Exception {
        useRetailerReceiver();
        MockHttpSession qm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        Long b = receiverHeldBatch("PB1-RS1-" + suffix, new BigDecimal("600.000"));
        Long dummy = dummyBatch();
        String kf = key("idem-risk-f");

        MvcResult[] r = gated(ledgerGate(receiverOrg.getId(), kf, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b, "门店抽检", kf)),
                () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "200", key("idem-sale"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(422);
        assertThat(code(r[1])).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", b)).isZero();
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertThat(batchRow(b).get("flow_status")).isEqualTo("ACTIVE");
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("部分销售先行 → 冻结成功（ACTIVE/FROZEN，保留已售）；售罄销售先行 → 冻结成功（CLOSED/FROZEN，从不重新打开）")
    void saleFirst_thenFreeze() throws Exception {
        useRetailerReceiver();
        MockHttpSession qm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        Long partial = receiverHeldBatch("PB1-RS2-" + suffix, new BigDecimal("600.000"));
        Long soldOut = receiverHeldBatch("PB1-RS3-" + suffix, new BigDecimal("300.000"));
        Long dummy = dummyBatch();

        String ks = key("idem-sale");
        MvcResult[] r = gated(saleGate(receiverOrg.getId(), receiverSite.getId(), ks, dummy), "sale",
                () -> perform(saleRequest(receiverSession, partial, receiverSite.getId(), "200", ks)),
                () -> perform(freezeRequest(qm, partial, "销售后抽检", key("idem-risk-f"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(201);
        assertThat(data(r[1]).get("flowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", partial)).isEqualTo(1);
        assertThat(riskStatus(partial)).isEqualTo("FROZEN");

        String ks2 = key("idem-sale");
        MvcResult[] r2 = gated(saleGate(receiverOrg.getId(), receiverSite.getId(), ks2, dummy), "sale",
                () -> perform(saleRequest(receiverSession, soldOut, receiverSite.getId(), "300", ks2)),
                () -> perform(freezeRequest(qm, soldOut, "售罄后风险调查", key("idem-risk-f"))));
        assertThat(status(r2[0])).isEqualTo(201);
        assertThat(status(r2[1])).isEqualTo(201);
        assertThat(data(r2[1]).get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(batchRow(soldOut).get("flow_status")).isEqualTo("CLOSED");
        assertThat(riskStatus(soldOut)).isEqualTo("FROZEN");
        assertLedgerConsistent(partial);
        assertLedgerConsistent(soldOut);
    }

    // =========================================================================
    // 冻结 ∥ 交接创建 / 接受
    // =========================================================================

    @Test
    @DisplayName("冻结先行 → 交接创建 409 BATCH_NOT_ACTIVE（零交接行）；交接创建先行 → 冻结成功，之后绑定 409")
    void freezeVsTransferCreate_bothOrders() throws Exception {
        MockHttpSession qm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();

        Long b1 = createAndSubmitActiveBatch(senderSession, "PB1-TC1-" + suffix, new BigDecimal("100.000"));
        String kf = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(senderOrg.getId(), kf, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b1, "交接前抽检", kf)),
                () -> perform(createTransferRequest(senderSession, b1, receiverOrg.getId(), key("idem-trf-c"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(409);
        assertThat(code(r[1])).isEqualTo("BATCH_NOT_ACTIVE");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", b1)).isZero();

        Long b2 = createAndSubmitActiveBatch(senderSession, "PB1-TC2-" + suffix, new BigDecimal("100.000"));
        String kt = key("idem-trf-c");
        MvcResult[] r2 = gated(transferIdempotencyGate(senderOrg.getId(), kt), "transfer_idempotency",
                () -> perform(createTransferRequest(senderSession, b2, receiverOrg.getId(), kt)),
                () -> perform(freezeRequest(qm, b2, "交接草稿后抽检", key("idem-risk-f"))));
        assertThat(status(r2[0])).isEqualTo(201);
        assertThat(status(r2[1])).isEqualTo(201);
        Long transferId = data(r2[0]).get("id").asLong();
        createdTransferIds.add(transferId);
        assertThat(transferStatus(transferId)).isEqualTo("DRAFT");
        Long shipmentId = createShipment(senderSession);
        expectProblem(bindRequest(senderSession, shipmentId, transferId, transferVersion(transferId)), 409, "BATCH_FLOW_BLOCKED");
        assertLedgerConsistent(b1);
        assertLedgerConsistent(b2);
    }

    @Test
    @DisplayName("冻结先行 → 接受 409 BATCH_FLOW_BLOCKED（交接保持 PENDING，拒收仍可）；接受先行 → 原责任组织冻结 403 ORG_SCOPE_DENIED，新责任组织可冻结")
    void freezeVsAccept_bothOrders() throws Exception {
        MockHttpSession senderQm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        MockHttpSession receiverQm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();

        Long b1 = createAndSubmitActiveBatch(senderSession, "PB1-AC1-" + suffix, new BigDecimal("100.000"));
        Handover h1 = prepareDeliveredHandover(b1);
        String kf = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(senderOrg.getId(), kf, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(senderQm, b1, "到货前风险调查", kf)),
                () -> perform(acceptRequest(receiverSession, h1.transferId(), new BigDecimal("100.000"), key("idem-trf-acc"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(409);
        assertThat(code(r[1])).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(transferStatus(h1.transferId())).isEqualTo("PENDING");
        assertThat(batchOrgId(b1)).isEqualTo(senderOrg.getId());
        expect(rejectRequest(receiverSession, h1.transferId(), key("idem-trf-rej")), 200);
        assertThat(riskStatus(b1)).isEqualTo("FROZEN");

        Long b2 = createAndSubmitActiveBatch(senderSession, "PB1-AC2-" + suffix, new BigDecimal("100.000"));
        expect(activateCodeRequest(senderSession, b2, key("idem-ptc")), 200);
        Handover h2 = prepareDeliveredHandover(b2);
        MvcResult[] r2 = gated(publicCodeRowGate(b2), "public_trace_code",
                () -> perform(acceptRequest(receiverSession, h2.transferId(), new BigDecimal("100.000"), key("idem-trf-acc"))),
                () -> perform(freezeRequest(senderQm, b2, "接受时抽检", key("idem-risk-f"))));
        assertThat(status(r2[0])).isEqualTo(200);
        assertThat(status(r2[1])).isEqualTo(403);
        assertThat(code(r2[1])).isEqualTo("ORG_SCOPE_DENIED");
        assertThat(batchOrgId(b2)).isEqualTo(receiverOrg.getId());
        assertThat(riskStatus(b2)).isEqualTo("NORMAL");
        assertThat(ledgerRows(b2)).isZero();
        freeze(receiverQm, b2, "新责任组织入库抽检");
        assertLedgerConsistent(b1);
        assertLedgerConsistent(b2);
    }

    // =========================================================================
    // 冻结 ∥ 批次操作提交
    // =========================================================================

    @Test
    @DisplayName("冻结先行 → 操作提交 422（操作与输出保持 DRAFT）；操作提交先行 → 输入已 CLOSED，冻结成功为 CLOSED/FROZEN，输出不受影响")
    void freezeVsOperationSubmit_bothOrders() throws Exception {
        MockHttpSession qm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();

        Long b1 = processorHeldBatch("PB1-OP1-" + suffix, new BigDecimal("300.000"));
        JsonNode draft1 = expect(processRequest(receiverSession, b1, "300.000", key("idem-op-c")), 201).get("data");
        registerOperation(draft1);
        String kf = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(receiverOrg.getId(), kf, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b1, "投料前抽检", kf)),
                () -> perform(submitOperationRequest(receiverSession, draft1.get("id").asLong(), draft1.get("version").asLong(), key("idem-op-s"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(422);
        assertThat(code(r[1])).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM batch_operation WHERE id = ?", String.class, draft1.get("id").asLong())).isEqualTo("DRAFT");
        assertThat(batchRow(b1).get("flow_status")).isEqualTo("ACTIVE");

        Long b2 = processorHeldBatch("PB1-OP2-" + suffix, new BigDecimal("300.000"));
        JsonNode draft2 = expect(processRequest(receiverSession, b2, "300.000", key("idem-op-c")), 201).get("data");
        registerOperation(draft2);
        Long opId = draft2.get("id").asLong();
        Long output = null;
        for (JsonNode item : draft2.get("items")) {
            if ("OUTPUT".equals(item.get("role").asString())) {
                output = item.get("batchId").asLong();
            }
        }
        String systemKey = "SYS:PROCESS:OPERATION:" + opId + ":BATCH:" + output;
        MvcResult[] r2 = gated(processEventGate(receiverOrg.getId(), systemKey, dummy), "trace_event",
                () -> perform(submitOperationRequest(receiverSession, opId, draft2.get("version").asLong(), key("idem-op-s"))),
                () -> perform(freezeRequest(qm, b2, "加工后历史风险调查", key("idem-risk-f"))));
        assertThat(status(r2[0])).isEqualTo(200);
        assertThat(status(r2[1])).isEqualTo(201);
        assertThat(data(r2[1]).get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(batchRow(b2).get("flow_status")).isEqualTo("CLOSED");
        assertThat(riskStatus(b2)).isEqualTo("FROZEN");
        assertThat(batchRow(output).get("flow_status")).isEqualTo("ACTIVE");
        assertThat(riskStatus(output)).as("no propagation to the output").isEqualTo("NORMAL");
        assertLedgerConsistent(b1);
        assertLedgerConsistent(b2);
    }

    // =========================================================================
    // 冻结 ∥ 解除、重复冻结、同键
    // =========================================================================

    @Test
    @DisplayName("冻结先行 → 解除成功（NORMAL，两行）；解除先行（起点 FROZEN）→ 冻结成功（FROZEN）")
    void freezeVsRelease_bothOrders() throws Exception {
        MockHttpSession qm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-FR-" + suffix, new BigDecimal("100.000"));

        String kf = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(senderOrg.getId(), kf, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b, "抽检", kf)),
                () -> perform(releaseRequest(qm, b, "复检合格", key("idem-risk-r"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(201);
        assertThat(riskStatus(b)).isEqualTo("NORMAL");
        assertThat(ledgerRows(b)).isEqualTo(2);

        freeze(qm, b, "再次抽检");
        String kr = key("idem-risk-r");
        MvcResult[] r2 = gated(ledgerGate(senderOrg.getId(), kr, dummy), "batch_risk_transition",
                () -> perform(releaseRequest(qm, b, "复检合格", kr)),
                () -> perform(freezeRequest(qm, b, "新的风险线索", key("idem-risk-f"))));
        assertThat(status(r2[0])).isEqualTo(201);
        assertThat(status(r2[1])).isEqualTo(201);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertThat(ledgerRows(b)).isEqualTo(5);
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("重复冻结（不同幂等键）：先行 201、后行 409 INVALID_STATE_TRANSITION；一行台账、一条审计")
    void duplicateFreeze_differentKeys() throws Exception {
        MockHttpSession qm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-DF-" + suffix, new BigDecimal("100.000"));
        String k1 = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(senderOrg.getId(), k1, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b, "质检员甲冻结", k1)),
                () -> perform(freezeRequest(qm, b, "质检员乙冻结", key("idem-risk-f"))));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(409);
        assertThat(code(r[1])).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(ledgerRows(b)).isEqualTo(1);
        assertThat(auditRows(b, "RISK_FREEZE")).isEqualTo(1);
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("同键同语义并发：后到者在批次锁后复读识别先到者，两者返回同一转换；一行台账、一条审计")
    void sameKeySameRequest_concurrent() throws Exception {
        MockHttpSession qm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-SK-" + suffix, new BigDecimal("100.000"));
        String k = key("idem-risk-f");
        MvcResult[] r = gated(ledgerGate(senderOrg.getId(), k, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, b, "抽检", k)),
                () -> perform(freezeRequest(qm, b, "抽检", k)));
        assertThat(status(r[0])).isEqualTo(201);
        assertThat(status(r[1])).isEqualTo(201);
        assertThat(data(r[1]).get("id").asLong()).isEqualTo(data(r[0]).get("id").asLong());
        assertThat(ledgerRows(b)).isEqualTo(1);
        assertThat(auditRows(b, "RISK_FREEZE")).isEqualTo(1);
    }

    @Test
    @DisplayName("同键不同批次强制重叠于台账唯一索引：恰好一个 201、一个 409（语义冲突或可重试并发冲突，绝无 5xx），失败方批次与版本不变；同键重试得到 409 IDEMPOTENCY_CONFLICT")
    void sameKeyDifferentBatches_overlapOnInsert() throws Exception {
        MockHttpSession qm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long dummy = dummyBatch();
        Long x = createAndSubmitActiveBatch(senderSession, "PB1-SX-" + suffix, new BigDecimal("100.000"));
        Long y = createAndSubmitActiveBatch(senderSession, "PB1-SY-" + suffix, new BigDecimal("100.000"));
        long vx = batchVersion(x);
        long vy = batchVersion(y);
        String k = key("idem-risk-f");
        MvcResult[] r = overlappedOnGate(ledgerGate(senderOrg.getId(), k, dummy), "batch_risk_transition",
                () -> perform(freezeRequest(qm, x, "抽检", k)),
                () -> perform(freezeRequest(qm, y, "抽检", k)));
        List<Integer> statuses = List.of(status(r[0]), status(r[1]));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        MvcResult loser = status(r[0]) == 409 ? r[0] : r[1];
        // 失败方要么走唯一键冲突后的复读（语义不同 409），要么被 InnoDB 三方同键插入模式选为死锁牺牲者（可重试 409）
        assertThat(code(loser)).isIn("IDEMPOTENCY_CONFLICT", "BATCH_CONCURRENT_CONFLICT");
        Long winnerBatch = status(r[0]) == 201 ? x : y;
        Long loserBatch = winnerBatch.equals(x) ? y : x;
        // 以同一幂等键重试得到确定结论：该键已被本组织用于另一批次
        expectProblem(freezeRequest(qm, loserBatch, "抽检", k), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(riskStatus(winnerBatch)).isEqualTo("FROZEN");
        assertThat(riskStatus(loserBatch)).isEqualTo("NORMAL");
        assertThat(batchVersion(loserBatch)).isEqualTo(loserBatch.equals(x) ? vx : vy);
        assertThat(ledgerRows(loserBatch)).isZero();
        assertThat(count("SELECT count(*) FROM batch_risk_transition WHERE org_id = ? AND idempotency_key = ?", senderOrg.getId(), k)).isEqualTo(1);
    }

    // =========================================================================
    // 屏障并发：无死锁、合法串行化
    // =========================================================================

    @Test
    @DisplayName("屏障并发 冻结 ∥ 销售 ×5、冻结 ∥ 接受 ×3：无死锁 / 无 5xx，每轮结果都是两种合法串行化之一")
    void barrierRaces_noDeadlock() throws Exception {
        useRetailerReceiver();
        MockHttpSession retailerQm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        MockHttpSession senderQm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long b = receiverHeldBatch("PB1-BS" + i + "-" + suffix, new BigDecimal("600.000"));
            MvcResult[] r = race(
                    () -> perform(freezeRequest(retailerQm, b, "并发抽检", key("idem-risk-f"))),
                    () -> perform(saleRequest(receiverSession, b, receiverSite.getId(), "200", key("idem-sale"))));
            int sales = count("SELECT count(*) FROM sale WHERE batch_id = ?", b);
            assertThat(status(r[0])).isEqualTo(201);
            assertThat(riskStatus(b)).isEqualTo("FROZEN");
            if (status(r[1]) == 201) {
                assertThat(sales).isEqualTo(1);
                outcomes.add("sale-first");
            } else {
                assertThat(status(r[1])).isEqualTo(422);
                assertThat(code(r[1])).isEqualTo("BATCH_FLOW_BLOCKED");
                assertThat(sales).isZero();
                outcomes.add("freeze-first");
            }
            assertLedgerConsistent(b);
        }
        for (int i = 0; i < 3; i++) {
            Long b = createAndSubmitActiveBatch(senderSession, "PB1-BA" + i + "-" + suffix, new BigDecimal("100.000"));
            Handover h = prepareDeliveredHandover(b);
            MvcResult[] r = race(
                    () -> perform(freezeRequest(senderQm, b, "并发调查", key("idem-risk-f"))),
                    () -> perform(acceptRequest(receiverSession, h.transferId(), new BigDecimal("100.000"), key("idem-trf-acc"))));
            if (status(r[1]) == 200) {
                assertThat(status(r[0])).isEqualTo(403);
                assertThat(code(r[0])).isEqualTo("ORG_SCOPE_DENIED");
                assertThat(batchOrgId(b)).isEqualTo(receiverOrg.getId());
                assertThat(riskStatus(b)).isEqualTo("NORMAL");
                outcomes.add("accept-first");
            } else {
                assertThat(status(r[1])).isEqualTo(409);
                assertThat(status(r[0])).isEqualTo(201);
                assertThat(batchOrgId(b)).isEqualTo(senderOrg.getId());
                assertThat(riskStatus(b)).isEqualTo("FROZEN");
                outcomes.add("freeze-first");
            }
            assertLedgerConsistent(b);
        }
        assertThat(outcomes).hasSize(8);
    }
}
