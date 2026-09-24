package com.example.traceability.trace;

import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.sale.mapper.SaleMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 企业端按组织范围读取的快照一致性（PB1 最终安全审查发现的读侧 TOCTOU）真实 MySQL 8.4 确定性回归。
 * <p>
 * 被测读路径先读取可变的责任组织事实（{@code batch.org_id}），据此授权，再执行不按组织过滤的数据读取或派生数量汇总。
 * 若这些语句分属不同的自动提交读视图，A 的请求可能在"A 仍是当前责任组织"的授权下，读到 A → B 交接接受之后
 * B 才提交的行或数量事实。修复要求：授权事实与构造该响应的全部数据属于同一个 InnoDB 一致性快照。
 * </p>
 * <p>
 * <b>确定性门闩（仅测试上下文）</b>：{@link SnapshotReadGateConfiguration} 注册一个 {@link BeanPostProcessor}，
 * 只把需要的四个 mapper 接口包进委托型 JDK 代理，只拦截指定方法，且只在被标记的 A 请求线程上生效；
 * 其余调用立即委托。A 的请求在授权读之后、目标数据读之前停住；主线程随后提交 ACCEPT 与 B 的写入，
 * 并断言这些业务写入在 A 仍停在门闩时已经完成（证明读事务不持有、也不等待任何业务锁）；最后放行 A。
 * 不使用 sleep，不修改生产代码。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("企业端组织范围读取快照一致性 MySQL 8.4 确定性回归（授权事实与响应数据同一快照）")
class OrgScopedReadSnapshotMysqlIntegrationTest extends AbstractBatchRiskMysqlIT {

    // =========================================================================
    // 测试专用门闩（只存在于本测试的 ApplicationContext）
    // =========================================================================

    /** 当前布设的门闩：拦截哪个 mapper 的哪个方法，以及到达 / 放行同步点。 */
    private record GateSpec(Class<?> mapper, String method, CountDownLatch arrived, CountDownLatch release, AtomicBoolean fired) {
    }

    private static final AtomicReference<GateSpec> ARMED = new AtomicReference<>();
    /** 只有被标记的 A 请求线程会被门闩拦截；主线程上的 ACCEPT / B 写入永远直接委托。 */
    private static final ThreadLocal<Boolean> GATED_THREAD = new ThreadLocal<>();

    @TestConfiguration
    static class SnapshotReadGateConfiguration {

        @Bean
        static BeanPostProcessor snapshotReadGatePostProcessor() {
            return new SnapshotReadGatePostProcessor();
        }
    }

    static final class SnapshotReadGatePostProcessor implements BeanPostProcessor {

        private static final List<Class<?>> GATED_MAPPERS = List.of(
                BatchRiskTransitionMapper.class, TraceEventMapper.class, SaleMapper.class, BatchOperationItemMapper.class);

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof FactoryBean<?>) {
                return bean;
            }
            for (Class<?> mapper : GATED_MAPPERS) {
                if (mapper.isInstance(bean)) {
                    Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(bean.getClass(), mapper.getClassLoader());
                    return Proxy.newProxyInstance(mapper.getClassLoader(), interfaces, new GateHandler(mapper, bean));
                }
            }
            return bean;
        }
    }

    private static final class GateHandler implements InvocationHandler {

        private final Class<?> mapper;
        private final Object target;

        GateHandler(Class<?> mapper, Object target) {
            this.mapper = mapper;
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "SnapshotReadGate[" + target + "]";
                    default -> invokeTarget(method, args);
                };
            }
            GateSpec spec = ARMED.get();
            if (spec != null && Boolean.TRUE.equals(GATED_THREAD.get()) && spec.mapper() == mapper
                    && spec.method().equals(method.getName()) && spec.fired().compareAndSet(false, true)) {
                spec.arrived().countDown();
                if (!spec.release().await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("snapshot read gate was never released");
                }
            }
            return invokeTarget(method, args);
        }

        private Object invokeTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private interface WhileParked {
        void run() throws Exception;
    }

    /**
     * A 的请求在工作线程中执行：授权读之后停在 {@code mapper.method} 之前；主线程执行 {@code whileParked}（ACCEPT 与 B 的写入），
     * 断言 A 在这些写入全部提交后仍停在门闩上，再放行 A 并返回其响应。所有门闩 / ThreadLocal / 工作线程状态在 finally 中清理。
     */
    private MvcResult gatedRead(Class<?> mapper, String method, MockHttpServletRequestBuilder request, WhileParked whileParked) throws Exception {
        GateSpec spec = new GateSpec(mapper, method, new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean(false));
        AtomicReference<MvcResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            GATED_THREAD.set(Boolean.TRUE);
            try {
                result.set(perform(request));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                GATED_THREAD.remove();
            }
        }, "snapshot-read-A");
        ARMED.set(spec);
        try {
            worker.start();
            assertThat(spec.arrived().await(30, TimeUnit.SECONDS))
                    .as("A's request passed its authorization read and parked before %s.%s", mapper.getSimpleName(), method).isTrue();
            whileParked.run();
            assertThat(worker.isAlive()).as("ACCEPT and B's write committed while A's read request stayed parked").isTrue();
            assertThat(spec.release().getCount()).isEqualTo(1);
        } finally {
            spec.release().countDown();
            worker.join(60_000);
            ARMED.set(null);
        }
        assertThat(worker.isAlive()).as("A's request finished after release").isFalse();
        if (error.get() != null) {
            throw new AssertionError("A's gated request failed", error.get());
        }
        return result.get();
    }

    @AfterEach
    void disarmGate() {
        ARMED.set(null);
    }

    private JsonNode body(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private void accept(Handover h, String qty) throws Exception {
        expect(acceptRequest(receiverSession, h.transferId(), new BigDecimal(qty), key("idem-trf-acc")), 200);
    }

    private MockHttpSession platformSession() throws Exception {
        return newSession(senderOrg, "plat_adm", "SYSTEM_ADMIN", "PLATFORM");
    }

    private MockHttpSession unrelatedSession() throws Exception {
        Organization other = createOrg("ORG_U_" + suffix, "无关企业-" + suffix, "PROCESSOR");
        return newSession(other, "unrel_op", "OPERATOR", "OWN_ORG");
    }

    private Long userIdOf(String usernamePrefix) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, usernamePrefix + "_" + suffix);
    }

    // =========================================================================
    // Race 1：PB1 风险转换历史
    // =========================================================================

    @Test
    @DisplayName("风险历史：A 授权读后停住 → ACCEPT A→B → B 质量管理员冻结；A 的在途响应只含 A 的两行，看不到 B 的行 / 原因 / 操作人")
    void riskHistory_inFlightReadNeverSeesNewOwnerTransition() throws Exception {
        MockHttpSession aQm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        Long b = createAndSubmitActiveBatch(senderSession, "SNAP-R-" + suffix, new BigDecimal("300.000"));
        freeze(aQm, b, "A 组织抽检");
        release(aQm, b, "A 组织复检合格");
        Handover h = prepareDeliveredHandover(b);
        MockHttpSession bQm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        Long bQmUserId = userIdOf("rcv_qm");
        String sentinel = "B-SECRET-RISK-REASON-" + suffix;

        MvcResult r = gatedRead(BatchRiskTransitionMapper.class, "selectByBatchId",
                getReq(aQm, "/api/v1/batches/" + b + "/risk-transitions"), () -> {
                    accept(h, "300");
                    assertThat(batchOrgId(b)).isEqualTo(receiverOrg.getId());
                    freeze(bQm, b, sentinel);
                    assertThat(ledgerRows(b)).as("B's transition committed").isEqualTo(3);
                });

        JsonNode rows = body(r).get("data");
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            assertThat(row.get("orgId").asLong()).isEqualTo(senderOrg.getId());
            assertThat(row.get("actorUserId").asLong()).isNotEqualTo(bQmUserId);
        }
        assertThat(rows.toString()).doesNotContain(sentinel);

        // 非竞态语义不变：A 现为历史参与组织只见本组织行；B / 平台见完整历史；无关组织 403
        JsonNode aNow = history(aQm, b);
        assertThat(aNow).hasSize(2);
        assertThat(aNow.toString()).doesNotContain(sentinel);
        JsonNode bNow = history(bQm, b);
        assertThat(bNow).hasSize(3);
        assertThat(bNow.get(2).get("reason").asString()).isEqualTo(sentinel);
        assertThat(bNow.get(2).get("actorUserId").asLong()).isEqualTo(bQmUserId);
        assertThat(history(platformSession(), b)).hasSize(3);
        expectProblem(getReq(unrelatedSession(), "/api/v1/batches/" + b + "/risk-transitions"), 403, "ORG_SCOPE_DENIED");
    }

    // =========================================================================
    // Race 2：追溯事件
    // =========================================================================

    @Test
    @DisplayName("追溯事件：A 授权读后停住 → ACCEPT A→B → B 在自有冷库登记入库；A 的在途响应等于授权快照中的事件集合，没有 B 的事件 / 摘要 / 场所 / 操作人")
    void traceEvents_inFlightReadNeverSeesNewOwnerEvent() throws Exception {
        Site bColdStore = createSite(receiverOrg.getId(), "SNAP-COLD-" + suffix, "B-SECRET-COLD-" + suffix, "COLD_STORE");
        Long b = createAndSubmitActiveBatch(senderSession, "SNAP-E-" + suffix, new BigDecimal("300.000"));
        Handover h = prepareDeliveredHandover(b);
        List<Long> snapshotEventIds = jdbcTemplate.queryForList(
                "SELECT id FROM trace_event WHERE batch_id = ? AND is_deleted = 0 ORDER BY id", Long.class, b);
        assertThat(snapshotEventIds).isNotEmpty();
        String sentinel = "B-SECRET-EVENT-SUMMARY-" + suffix;
        AtomicReference<Long> bEventId = new AtomicReference<>();

        MvcResult r = gatedRead(TraceEventMapper.class, "selectByBatchId",
                getReq(senderSession, "/api/v1/batches/" + b + "/events"), () -> {
                    accept(h, "300");
                    Map<String, Object> event = Map.of("eventType", "WAREHOUSE_IN",
                            "occurredAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1).toString(),
                            "siteId", bColdStore.getId(), "dataSource", "MANUAL", "summary", sentinel);
                    bEventId.set(expect(postJson(receiverSession, "/api/v1/batches/" + b + "/events", key("idem-evt"), event), 201)
                            .get("data").get("id").asLong());
                });

        JsonNode events = body(r).get("data");
        List<Long> returned = new ArrayList<>();
        events.forEach(e -> returned.add(e.get("id").asLong()));
        assertThat(returned).containsExactlyInAnyOrderElementsOf(snapshotEventIds).doesNotContain(bEventId.get());
        assertThat(events.toString()).doesNotContain(sentinel).doesNotContain("B-SECRET-COLD");
        for (JsonNode e : events) {
            assertThat(e.get("orgId").asLong()).as("no new-owner event").isNotEqualTo(receiverOrg.getId());
            assertThat(e.path("siteId").isNull() || e.path("siteId").asLong() != bColdStore.getId()).as("no B site").isTrue();
            assertThat(e.path("operatorId").isNull() || e.path("operatorId").asLong() != receiverUser.getId()).as("no B operator").isTrue();
        }

        // 非竞态语义不变：A 现为历史参与组织只见本组织记录的事件；B / 平台见完整时间线；无关组织 403
        JsonNode aNow = expect(getReq(senderSession, "/api/v1/batches/" + b + "/events"), 200).get("data");
        assertThat(aNow.toString()).doesNotContain(sentinel);
        aNow.forEach(e -> assertThat(e.get("orgId").asLong()).isEqualTo(senderOrg.getId()));
        assertThat(expect(getReq(receiverSession, "/api/v1/batches/" + b + "/events"), 200).get("data").toString()).contains(sentinel);
        assertThat(expect(getReq(platformSession(), "/api/v1/batches/" + b + "/events"), 200).get("data").toString()).contains(sentinel);
        expectProblem(getReq(unrelatedSession(), "/api/v1/batches/" + b + "/events"), 403, "ORG_SCOPE_DENIED");
    }

    // =========================================================================
    // Race 3：终端销售记录
    // =========================================================================

    @Test
    @DisplayName("终端销售：A 对未销售批次授权读后停住 → ACCEPT A→零售 B → B 在自有门店首售；A 的在途响应为空，没有 B 的销售或门店")
    void sales_inFlightReadNeverSeesNewOwnerFirstSale() throws Exception {
        useRetailerReceiver();
        Site bStore = createSite(receiverOrg.getId(), "SNAP-STORE-" + suffix, "B-SECRET-STORE-" + suffix, "STORE");
        Long b = createAndSubmitActiveBatch(senderSession, "SNAP-S-" + suffix, new BigDecimal("600.000"));
        Handover h = prepareDeliveredHandover(b);
        AtomicReference<Long> bSaleId = new AtomicReference<>();

        MvcResult r = gatedRead(SaleMapper.class, "selectByBatchId",
                getReq(senderSession, "/api/v1/batches/" + b + "/sales"), () -> {
                    accept(h, "600");
                    bSaleId.set(expect(saleRequest(receiverSession, b, bStore.getId(), "200", key("idem-sale")), 201)
                            .get("data").get("id").asLong());
                    assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", b)).isEqualTo(1);
                });

        JsonNode sales = body(r).get("data");
        assertThat(sales).isEmpty();
        assertThat(sales.toString()).doesNotContain("B-SECRET-STORE").doesNotContain("\"id\":" + bSaleId.get());

        // 非竞态语义不变：A 现在 403；B 与平台看到该销售；无关组织 403
        expectProblem(getReq(senderSession, "/api/v1/batches/" + b + "/sales"), 403, "ORG_SCOPE_DENIED");
        JsonNode bNow = expect(getReq(receiverSession, "/api/v1/batches/" + b + "/sales"), 200).get("data");
        assertThat(bNow).hasSize(1);
        assertThat(bNow.get(0).get("siteName").asString()).isEqualTo("B-SECRET-STORE-" + suffix);
        assertThat(expect(getReq(platformSession(), "/api/v1/batches/" + b + "/sales"), 200).get("data")).hasSize(1);
        expectProblem(getReq(unrelatedSession(), "/api/v1/batches/" + b + "/sales"), 403, "ORG_SCOPE_DENIED");
    }

    // =========================================================================
    // Race 4：批次详情派生剩余量
    // =========================================================================

    @Test
    @DisplayName("批次详情：A 读到 ACTIVE 600kg 批次后停在数量汇总前 → ACCEPT A→零售 B → B 售出 200；A 的在途详情仍为快照一致的剩余 600kg")
    void batchDetail_inFlightQuantityMatchesAuthorizedSnapshot() throws Exception {
        useRetailerReceiver();
        Long b = createAndSubmitActiveBatch(senderSession, "SNAP-D-" + suffix, new BigDecimal("600.000"));
        Handover h = prepareDeliveredHandover(b);

        MvcResult r = gatedRead(BatchOperationItemMapper.class, "sumSubmittedInputQuantityByBatchId",
                getReq(senderSession, "/api/v1/batches/" + b), () -> {
                    accept(h, "600");
                    expect(saleRequest(receiverSession, b, receiverSite.getId(), "200", key("idem-sale")), 201);
                    assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", b)).isEqualTo(1);
                });

        JsonNode view = body(r).get("data");
        assertThat(view.get("orgId").asLong()).isEqualTo(senderOrg.getId());
        assertThat(view.get("flowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(view.path("firstSaleId").isNull() || view.path("firstSaleId").isMissingNode()).isTrue();
        assertThat(new BigDecimal(view.get("remainingQuantity").toString())).isEqualByComparingTo("600");

        expectProblem(getReq(senderSession, "/api/v1/batches/" + b), 403, "ORG_SCOPE_DENIED");
        JsonNode bView = expect(getReq(receiverSession, "/api/v1/batches/" + b), 200).get("data");
        assertThat(new BigDecimal(bView.get("remainingQuantity").toString())).isEqualByComparingTo("400");
    }

    // =========================================================================
    // Race 5：批次列表派生剩余量
    // =========================================================================

    @Test
    @DisplayName("批次列表：A 的组织范围分页已读到该批次后停在销售汇总前 → ACCEPT A→零售 B → B 售出 200；A 的在途分页中该行仍为剩余 600kg，计数 / 行 / 派生数量同一快照")
    void batchList_inFlightQuantityMatchesAuthorizedSnapshot() throws Exception {
        useRetailerReceiver();
        Long b = createAndSubmitActiveBatch(senderSession, "SNAP-L-" + suffix, new BigDecimal("600.000"));
        Handover h = prepareDeliveredHandover(b);

        MvcResult r = gatedRead(SaleMapper.class, "sumSubmittedQuantityByBatchIds",
                getReq(senderSession, "/api/v1/batches?page=1&size=20"), () -> {
                    accept(h, "600");
                    expect(saleRequest(receiverSession, b, receiverSite.getId(), "200", key("idem-sale")), 201);
                    assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", b)).isEqualTo(1);
                });

        JsonNode page = body(r);
        JsonNode rows = page.get("data");
        assertThat(page.get("meta").get("page").get("totalElements").asLong()).isEqualTo(rows.size());
        JsonNode row = null;
        for (JsonNode candidate : rows) {
            if (candidate.get("id").asLong() == b) {
                row = candidate;
            }
        }
        assertThat(row).as("the org-scoped page read before the transfer contains the batch").isNotNull();
        assertThat(row.get("orgId").asLong()).isEqualTo(senderOrg.getId());
        assertThat(new BigDecimal(row.get("remainingQuantity").toString())).isEqualByComparingTo("600");

        JsonNode fresh = expect(getReq(senderSession, "/api/v1/batches?page=1&size=20"), 200).get("data");
        for (JsonNode candidate : fresh) {
            assertThat(candidate.get("id").asLong()).as("transferred batch no longer listed for A").isNotEqualTo(b);
        }
    }
}
