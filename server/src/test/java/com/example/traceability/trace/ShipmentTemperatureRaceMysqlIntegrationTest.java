package com.example.traceability.trace;

import com.example.traceability.audit.mapper.AuditLogMapper;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB2 在途温度登记与运输任务生命周期的确定性竞态（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * <b>确定性门闩（仅测试上下文）</b>：{@link TemperatureRaceGateConfiguration} 注册一个 {@link BeanPostProcessor}，只把
 * TemperatureRecordMapper / ShipmentMapper / TransferMapper / AuditLogMapper 包进委托型 JDK 代理，只拦截指定方法，
 * 且只在被标记的工作线程上生效；其余调用立即委托，生产代码不含任何钩子。另一个请求是否已阻塞在某张表的行锁上，
 * 通过 performance_schema 的锁等待视图确认（同步点），不使用 sleep。
 * </p>
 * <p>
 * 证明的锁顺序与可见性：温度登记与确认到达都先取得运输任务行锁（FOR UPDATE），再访问 temperature_record；
 * 到达在持锁后以当前读（FOR SHARE）读取最新测量时间，因此即使其取锁前的幂等预读已在 REPEATABLE READ 下建立了旧读视图，
 * 也能看到等待期间已提交的温度记录（同一事务内的普通一致性读看不到它——红转绿证明）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("在途温度登记确定性竞态 MySQL 8.4 集成测试（PB2）")
class ShipmentTemperatureRaceMysqlIntegrationTest extends AbstractBatchRiskMysqlIT {

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

    // =========================================================================
    // 测试专用门闩（只存在于本测试的 ApplicationContext）
    // =========================================================================

    private interface AfterRelease {
        void run() throws Exception;
    }

    /** 当前布设的门闩：拦截哪个 mapper 的哪个方法、到达 / 放行同步点，以及放行后在被拦截线程上执行的探针。 */
    private record GateSpec(Class<?> mapper, String method, CountDownLatch arrived, CountDownLatch release,
                            AtomicBoolean fired, AfterRelease afterRelease) {
    }

    private static final AtomicReference<GateSpec> ARMED = new AtomicReference<>();
    /** 只有被标记的工作线程会被门闩拦截。 */
    private static final ThreadLocal<Boolean> GATED_THREAD = new ThreadLocal<>();

    @TestConfiguration
    static class TemperatureRaceGateConfiguration {

        @Bean
        static BeanPostProcessor temperatureRaceGatePostProcessor() {
            return new TemperatureRaceGatePostProcessor();
        }
    }

    static final class TemperatureRaceGatePostProcessor implements BeanPostProcessor {

        private static final List<Class<?>> GATED_MAPPERS = List.of(
                TemperatureRecordMapper.class, ShipmentMapper.class, TransferMapper.class, AuditLogMapper.class);

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
                    case "toString" -> "TemperatureRaceGate[" + target + "]";
                    default -> invokeTarget(method, args);
                };
            }
            GateSpec spec = ARMED.get();
            if (spec != null && Boolean.TRUE.equals(GATED_THREAD.get()) && spec.mapper() == mapper
                    && spec.method().equals(method.getName()) && spec.fired().compareAndSet(false, true)) {
                spec.arrived().countDown();
                if (!spec.release().await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("temperature race gate was never released");
                }
                if (spec.afterRelease() != null) {
                    spec.afterRelease().run();
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

    @AfterEach
    void disarmGate() {
        ARMED.set(null);
    }

    // =========================================================================
    // 编排辅助
    // =========================================================================

    private interface Call {
        MvcResult run() throws Exception;
    }

    private interface WhileParked {
        void run() throws Exception;
    }

    private final class Running {
        final Thread thread;
        final AtomicReference<MvcResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        Running(String name, boolean gated, Call call) {
            this.thread = new Thread(() -> {
                if (gated) {
                    GATED_THREAD.set(Boolean.TRUE);
                }
                try {
                    result.set(call.run());
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    GATED_THREAD.remove();
                }
            }, name);
        }

        MvcResult join() throws Exception {
            thread.join(60_000);
            assertThat(thread.isAlive()).as("race worker %s did not finish", thread.getName()).isFalse();
            if (error.get() != null) {
                throw new AssertionError(thread.getName() + " failed", error.get());
            }
            return result.get();
        }
    }

    /**
     * 被标记的请求 A 在 {@code mapper.method} 调用前停住；主线程执行 {@code whileParked}（启动 / 完成其他请求并确认同步点），
     * 再放行 A。门闩、ThreadLocal 与工作线程状态在 finally 中清理。
     */
    private MvcResult gated(Class<?> mapper, String method, AfterRelease afterRelease, Call a, WhileParked whileParked) throws Exception {
        GateSpec spec = new GateSpec(mapper, method, new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean(false), afterRelease);
        Running running = new Running("pb2-gated-A", true, a);
        ARMED.set(spec);
        try {
            running.thread.start();
            assertThat(spec.arrived().await(30, TimeUnit.SECONDS))
                    .as("request A reached the gate before %s.%s", mapper.getSimpleName(), method).isTrue();
            whileParked.run();
        } finally {
            spec.release().countDown();
            running.thread.join(60_000);
            ARMED.set(null);
        }
        return running.join();
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

    private static String iso(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).format(MICROS);
    }

    private MockHttpServletRequestBuilder recordReq(Long shipmentId, String idemKey, LocalDateTime measuredAt, String temperature) throws Exception {
        return temperatureRequest(carrierSession, shipmentId, idemKey, iso(measuredAt), temperature, "MANUAL", null);
    }

    private MockHttpServletRequestBuilder arriveReq(Long shipmentId, LocalDateTime unloadedAt, long expectedVersion) throws Exception {
        return postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/arrive", key("idem-shp-a"),
                new ShipmentArriveRequest(unloadedAt.atOffset(ZoneOffset.UTC), expectedVersion));
    }

    private Handover inTransit(String tag) throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-TRACE-" + tag + "-" + suffix, new BigDecimal("500"));
        return prepareInTransitHandover(batchId);
    }

    private Long batchOf(Handover h) {
        return jdbcTemplate.queryForObject("SELECT batch_id FROM transfer WHERE id = ?", Long.class, h.transferId());
    }

    private int records(Long shipmentId) {
        return count("SELECT count(*) FROM temperature_record WHERE shipment_id = ?", shipmentId);
    }

    // =========================================================================
    // 登记 × 确认到达（两种加锁先后）
    // =========================================================================

    @Test
    @DisplayName("登记先取得运输任务行锁 → 到达阻塞于 shipment 行锁 → 登记提交 → 到达持锁后看到该记录，早于它的到达时间 422；之后以不早于它的时间到达成功")
    void recordLocksFirst_arrivalWaitsAndSeesCommittedRecord() throws Exception {
        Handover h = inTransit("R1");
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        LocalDateTime measuredAt = loadedAt.plusSeconds(60);
        long version = shipmentVersion(h.shipmentId());
        AtomicReference<Running> arrival = new AtomicReference<>();

        MvcResult recorded = gated(TemperatureRecordMapper.class, "insert", null,
                () -> perform(recordReq(h.shipmentId(), key("idem-temp"), measuredAt, "-18.00")),
                () -> {
                    Running b = new Running("pb2-arrive-B", false, () -> perform(arriveReq(h.shipmentId(), loadedAt.plusSeconds(30), version)));
                    arrival.set(b);
                    b.thread.start();
                    awaitRowLockWait(b.thread, "shipment");
                });

        assertThat(status(recorded)).isEqualTo(201);
        MvcResult arrived = arrival.get().join();
        assertThat(status(arrived)).as(arrived.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(code(arrived)).isEqualTo("INVALID_BUSINESS_TIME");
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("IN_TRANSIT");
        assertThat(countEvents(batchOf(h), "ARRIVAL")).isZero();
        assertThat(records(h.shipmentId())).isEqualTo(1);

        expect(arriveReq(h.shipmentId(), measuredAt, version), 200);
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("到达先取得运输任务行锁（停在最新测量时间当前读之前）→ 登记阻塞于 shipment 行锁 → 到达提交 DELIVERED → 登记看到已到达 409，未写入任何记录")
    void arrivalLocksFirst_recordWaitsAndIsRejected() throws Exception {
        Handover h = inTransit("R2");
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        long version = shipmentVersion(h.shipmentId());
        AtomicReference<Running> recording = new AtomicReference<>();

        MvcResult arrived = gated(ShipmentMapper.class, "selectLatestTemperatureMeasuredAtForShare", null,
                () -> perform(arriveReq(h.shipmentId(), loadedAt.plusSeconds(1), version)),
                () -> {
                    Running b = new Running("pb2-record-B", false, () -> perform(recordReq(h.shipmentId(), key("idem-temp"), loadedAt.plusSeconds(2), "-18.00")));
                    recording.set(b);
                    b.thread.start();
                    awaitRowLockWait(b.thread, "shipment");
                });

        assertThat(status(arrived)).as(arrived.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult recorded = recording.get().join();
        assertThat(status(recorded)).isEqualTo(409);
        assertThat(code(recorded)).isEqualTo("SHIPMENT_NOT_IN_TRANSIT");
        assertThat(records(h.shipmentId())).isZero();
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("DELIVERED");
        assertThat(countEvents(batchOf(h), "ARRIVAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("红转绿：到达的取锁前幂等预读建立旧读视图 → 温度记录提交 → 同一事务内普通一致性读看不到它（红）→ 到达持锁后当前读看到它（绿），早于它的到达时间 422")
    void arrivalSnapshotPredatesRecord_currentReadStillSeesIt() throws Exception {
        Handover h = inTransit("R3");
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        LocalDateTime measuredAt = loadedAt.plusSeconds(60);
        long version = shipmentVersion(h.shipmentId());
        AtomicReference<LocalDateTime> snapshotRead = new AtomicReference<>(LocalDateTime.MIN);
        AtomicReference<Integer> snapshotCount = new AtomicReference<>(-1);
        AtomicBoolean insideArrivalTransaction = new AtomicBoolean(false);
        AtomicReference<String> isolation = new AtomicReference<>();

        MvcResult arrived = gated(ShipmentMapper.class, "selectByIdForUpdate",
                // 在被拦截的到达线程上、到达事务之内执行：普通一致性读复用预读建立的读视图
                () -> {
                    insideArrivalTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    isolation.set(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class));
                    snapshotCount.set(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM temperature_record WHERE shipment_id = ?", Integer.class, h.shipmentId()));
                    snapshotRead.set(jdbcTemplate.queryForObject(
                            "SELECT MAX(measured_at) FROM temperature_record WHERE shipment_id = ?", LocalDateTime.class, h.shipmentId()));
                },
                () -> perform(arriveReq(h.shipmentId(), loadedAt.plusSeconds(30), version)),
                () -> {
                    // 到达已完成取锁前的幂等预读、尚未请求运输任务行锁：温度登记不受阻塞，立即提交
                    MvcResult recorded = perform(recordReq(h.shipmentId(), key("idem-temp"), measuredAt, "-18.00"));
                    assertThat(status(recorded)).as(recorded.getResponse().getContentAsString()).isEqualTo(201);
                    assertThat(records(h.shipmentId())).isEqualTo(1);
                });

        assertThat(insideArrivalTransaction.get()).as("probe ran inside the arrival transaction").isTrue();
        assertThat(isolation.get()).isEqualTo("REPEATABLE-READ");
        assertThat(snapshotCount.get()).as("RED: a plain consistent read in the arrival transaction misses the committed record").isZero();
        assertThat(snapshotRead.get()).isNull();
        assertThat(status(arrived)).as("GREEN: the locking current read after the shipment lock sees it; body=%s",
                arrived.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(code(arrived)).isEqualTo("INVALID_BUSINESS_TIME");
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("IN_TRANSIT");
        assertThat(countEvents(batchOf(h), "ARRIVAL")).isZero();
    }

    // =========================================================================
    // 同键并发
    // =========================================================================

    @Test
    @DisplayName("同键同语义并发：先行请求持运输任务行锁停在插入前，后行请求阻塞于 shipment 行锁；放行后后行请求持锁复读命中并重放 → 两次 201 同一记录，一行、一条审计")
    void sameKeySameRequest_serializedOnShipmentLock() throws Exception {
        Handover h = inTransit("R4");
        LocalDateTime measuredAt = shipmentLoadedAt(h.shipmentId()).plusSeconds(5);
        String k = key("idem-temp-same");
        AtomicReference<Running> second = new AtomicReference<>();

        MvcResult first = gated(TemperatureRecordMapper.class, "insert", null,
                () -> perform(recordReq(h.shipmentId(), k, measuredAt, "-18.00")),
                () -> {
                    Running b = new Running("pb2-same-B", false, () -> perform(recordReq(h.shipmentId(), k, measuredAt, "-18.00")));
                    second.set(b);
                    b.thread.start();
                    awaitRowLockWait(b.thread, "shipment");
                });

        MvcResult replay = second.get().join();
        assertThat(status(first)).isEqualTo(201);
        assertThat(status(replay)).isEqualTo(201);
        assertThat(data(replay).get("id").asLong()).isEqualTo(data(first).get("id").asLong());
        assertThat(records(h.shipmentId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'TEMPERATURE_RECORD' AND object_id = ?", h.shipmentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("同键不同运输任务：B 已插入未提交（停在审计前）→ A 持锁复读看不到、插入阻塞于幂等唯一键 → B 提交 → A 唯一键冲突后锁定读得到 B 的记录，语义不同 409；只有 B 的一行")
    void sameKeyDifferentShipment_uncommittedWinnerThenConflict() throws Exception {
        Handover h1 = inTransit("R5A");
        Handover h2 = inTransit("R5B");
        String k = key("idem-temp-cross");
        LocalDateTime m1 = shipmentLoadedAt(h1.shipmentId()).plusSeconds(5);
        LocalDateTime m2 = shipmentLoadedAt(h2.shipmentId()).plusSeconds(5);
        AtomicReference<Running> loser = new AtomicReference<>();

        MvcResult winner = gated(AuditLogMapper.class, "insert", null,
                () -> perform(recordReq(h2.shipmentId(), k, m2, "-19.00")),
                () -> {
                    Running a = new Running("pb2-cross-A", false, () -> perform(recordReq(h1.shipmentId(), k, m1, "-18.00")));
                    loser.set(a);
                    a.thread.start();
                    awaitRowLockWait(a.thread, "temperature_record");
                });

        MvcResult conflict = loser.get().join();
        assertThat(status(winner)).isEqualTo(201);
        assertThat(status(conflict)).as(conflict.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(records(h2.shipmentId())).isEqualTo(1);
        assertThat(records(h1.shipmentId())).isZero();
    }

    @Test
    @DisplayName("三方同键：holder 未提交门闩行使两个不同运输任务的同键请求都阻塞于幂等唯一键；holder 回滚后恰好一个 201、另一个 409（幂等冲突或可重试并发冲突），从不 500")
    void threeWaySameKey_neverServerError() throws Exception {
        Handover h1 = inTransit("R6A");
        Handover h2 = inTransit("R6B");
        Handover gate = inTransit("R6G");
        String k = key("idem-temp-3way");
        LocalDateTime m1 = shipmentLoadedAt(h1.shipmentId()).plusSeconds(5);
        LocalDateTime m2 = shipmentLoadedAt(h2.shipmentId()).plusSeconds(5);
        Running a = new Running("pb2-3way-A", false, () -> perform(recordReq(h1.shipmentId(), k, m1, "-18.00")));
        Running b = new Running("pb2-3way-B", false, () -> perform(recordReq(h2.shipmentId(), k, m2, "-19.00")));

        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("INSERT INTO temperature_record (shipment_id, org_id, actor_user_id, stage_code, "
                    + "measured_at, recorded_at, temperature, unit_code, data_source, evaluation, idempotency_key, request_hash) "
                    + "VALUES (?, ?, ?, 'TRANSPORT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), -18.00, 'CELSIUS', 'MANUAL', 'MISSING_CONTEXT', ?, REPEAT('0', 64))")) {
                ps.setLong(1, gate.shipmentId());
                ps.setLong(2, carrierOrg.getId());
                ps.setLong(3, carrierUser.getId());
                ps.setString(4, k);
                ps.executeUpdate();
            }
            a.thread.start();
            awaitRowLockWait(a.thread, "temperature_record");
            b.thread.start();
            awaitRowLockWaits(b.thread, "temperature_record", 2);
        } finally {
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
        }
        MvcResult ra = a.join();
        MvcResult rb = b.join();
        List<Integer> statuses = List.of(status(ra), status(rb));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        MvcResult loserResult = status(ra) == 409 ? ra : rb;
        assertThat(code(loserResult)).isIn("IDEMPOTENCY_CONFLICT", "TEMPERATURE_RECORD_CONCURRENT_CONFLICT");
        assertThat(count("SELECT count(*) FROM temperature_record WHERE org_id = ? AND idempotency_key = ?", carrierOrg.getId(), k)).isEqualTo(1);
        assertThat(records(gate.shipmentId())).isZero();
    }

    // =========================================================================
    // 登记 × 发运；登记 × PB1 风险冻结
    // =========================================================================

    @Test
    @DisplayName("发运持有运输任务行锁（停在锁定装载清单之前）→ 登记阻塞于 shipment 行锁 → 发运提交 IN_TRANSIT → 登记在锁后读取到在途状态并成功")
    void recordWaitsForDispatch_thenSeesInTransit() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-TRACE-R7-" + suffix, new BigDecimal("500"));
        Handover h = preparePendingHandover(batchId);
        LocalDateTime measuredAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);
        AtomicReference<Running> recording = new AtomicReference<>();

        MvcResult dispatched = gated(TransferMapper.class, "selectByShipmentIdForUpdate", null,
                () -> perform(dispatchRequest(carrierSession, h.shipmentId(), shipmentVersion(h.shipmentId()), key("idem-shp-d"))),
                () -> {
                    Running b = new Running("pb2-record-dispatch", false, () -> perform(recordReq(h.shipmentId(), key("idem-temp"), measuredAt, "-18.00")));
                    recording.set(b);
                    b.thread.start();
                    awaitRowLockWait(b.thread, "shipment");
                });

        assertThat(status(dispatched)).isEqualTo(200);
        MvcResult recorded = recording.get().join();
        assertThat(status(recorded)).as(recorded.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(records(h.shipmentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("登记与 PB1 风险冻结锁集合不相交：登记持运输任务行锁停住时，同一批次的人工冻结立即完成；放行后登记成功，风险状态保持 FROZEN")
    void recordAndFreeze_disjointLocks() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Handover h = inTransit("R8");
        Long batchId = batchOf(h);
        MockHttpSession senderQm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        LocalDateTime measuredAt = shipmentLoadedAt(h.shipmentId()).plusSeconds(5);

        MvcResult recorded = gated(TemperatureRecordMapper.class, "insert", null,
                () -> perform(recordReq(h.shipmentId(), key("idem-temp"), measuredAt, "-5.00")),
                () -> {
                    MvcResult frozen = perform(freezeRequest(senderQm, batchId, "在途抽检异常", key("idem-risk-f")));
                    assertThat(status(frozen)).as(frozen.getResponse().getContentAsString()).isEqualTo(201);
                    assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
                });

        assertThat(status(recorded)).isEqualTo(201);
        assertThat(data(recorded).get("evaluation").asString()).isEqualTo("HIGH");
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
        assertThat(ledgerRows(batchId)).isEqualTo(1);
        assertThat(records(h.shipmentId())).isEqualTo(1);
    }
}
