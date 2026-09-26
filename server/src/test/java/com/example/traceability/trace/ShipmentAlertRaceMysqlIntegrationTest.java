package com.example.traceability.trace;

import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.mapper.ShipmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB3 在途持续超温告警的确定性竞态（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 证明的锁顺序：温度登记（含告警创建）shipment → temperature_record → alert → batch（升序）；确认到达 shipment →
 * temperature_record（当前读）；人工风险转换只锁 batch；告警确认只锁 alert。因此：
 * <ul>
 *   <li>两条并发登记同时补全同一片段：由运输任务行锁串行化，后到者看到先到者的告警，只产生一条告警；</li>
 *   <li>补全片段的登记与确认到达：由运输任务行锁串行化，任一顺序都得到确定结果（先登记：告警与冻结后仍可到达；
 *       先到达：登记 409 且没有告警）；</li>
 *   <li>自动冻结与人工冻结 / 解除：由批次行锁串行化，任一顺序都不产生重复转换，且告警提交后批次一定处于冻结；</li>
 *   <li>并发确认：由告警行锁串行化，只产生一条确认动作。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractPhaseBRaceMysqlIT.GateConfiguration.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("在途持续超温告警确定性竞态 MySQL 8.4 集成测试（PB3）")
class ShipmentAlertRaceMysqlIntegrationTest extends AbstractPhaseBRaceMysqlIT {

    private LocalDateTime twoHoursAgo() {
        return LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
    }

    @Test
    @DisplayName("两条并发登记同时补全同一越界片段：运输任务行锁串行化，只产生一条告警，批次各只冻结一次")
    void concurrentCompletingReadings_exactlyOneAlert() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Manifest m = inTransitManifest("RACE-TWO", twoHoursAgo(), "600", "360");
        LocalDateTime t = m.loadedAt();
        recordAt(m.shipmentId(), t.plusMinutes(10), "-12.00");

        AtomicReference<Running> b = new AtomicReference<>();
        MvcResult a = gated(TemperatureRecordMapper.class, "insert",
                () -> perform(recordReq(m.shipmentId(), key("idem-temp-a"), t.plusMinutes(40), "-11.00")),
                () -> {
                    b.set(background("pb3-reading-B", () -> perform(recordReq(m.shipmentId(), key("idem-temp-b"), t.plusMinutes(41), "-11.50"))));
                    awaitRowLockWait(b.get().thread, "shipment");
                });
        MvcResult rb = b.get().join();

        assertThat(status(a)).isEqualTo(201);
        assertThat(status(rb)).isEqualTo(201);
        Map<String, Object> alert = alertRows(m.shipmentId()).get(0);
        assertThat(alertRows(m.shipmentId())).hasSize(1);
        assertThat(((Number) alert.get("sustained_record_id")).longValue()).as("the first committed completing reading")
                .isEqualTo(data(a).get("id").asLong());
        Long alertId = ((Number) alert.get("id")).longValue();
        for (Long batch : m.batchIds()) {
            assertThat(riskStatus(batch)).isEqualTo("FROZEN");
            assertThat(ledgerRows(batch)).isEqualTo(1);
            assertThat(alertLedgerRows(batch, alertId)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ?", alertId)).isEqualTo(2);
    }

    @Test
    @DisplayName("补全片段的登记先持有运输任务行锁、确认到达等待：告警与自动冻结先提交，之后到达成功，批次仍冻结且责任组织不变")
    void completingReadingFirst_thenArrival() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Manifest m = inTransitManifest("RACE-ARR1", twoHoursAgo(), "500");
        LocalDateTime t = m.loadedAt();
        recordAt(m.shipmentId(), t.plusMinutes(10), "-12.00");
        long shipmentVersion = shipmentVersion(m.shipmentId());

        AtomicReference<Running> arrival = new AtomicReference<>();
        MvcResult reading = gated(AlertMapper.class, "insert",
                () -> perform(recordReq(m.shipmentId(), key("idem-temp-a"), t.plusMinutes(40), "-11.00")),
                () -> {
                    arrival.set(background("pb3-arrival-B", () -> perform(postJson(carrierSession,
                            "/api/v1/shipments/" + m.shipmentId() + "/arrive", key("idem-shp-a"),
                            new com.example.traceability.trace.dto.ShipmentArriveRequest(
                                    t.plusMinutes(45).atOffset(ZoneOffset.UTC), shipmentVersion)))));
                    awaitRowLockWait(arrival.get().thread, "shipment");
                });
        MvcResult arrived = arrival.get().join();

        assertThat(status(reading)).isEqualTo(201);
        assertThat(status(arrived)).isEqualTo(200);
        assertThat(alertRows(m.shipmentId())).hasSize(1);
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
        assertThat(shipmentStatus(m.shipmentId())).isEqualTo("DELIVERED");
        assertThat(batchOrgId(m.batch(0))).isEqualTo(senderOrg.getId());
        assertThat(countEvents(m.batch(0), "ARRIVAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("确认到达先持有运输任务行锁、补全片段的登记等待：到达先提交，登记 409 SHIPMENT_NOT_IN_TRANSIT，没有告警，批次保持 NORMAL")
    void arrivalFirst_thenCompletingReadingRejected() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Manifest m = inTransitManifest("RACE-ARR2", twoHoursAgo(), "500");
        LocalDateTime t = m.loadedAt();
        recordAt(m.shipmentId(), t.plusMinutes(10), "-12.00");

        AtomicReference<Running> reading = new AtomicReference<>();
        MvcResult arrived = gated(ShipmentMapper.class, "selectLatestTemperatureMeasuredAtForShare",
                () -> perform(arriveReq(m.shipmentId(), t.plusMinutes(45))),
                () -> {
                    reading.set(background("pb3-reading-B", () -> perform(recordReq(m.shipmentId(), key("idem-temp-b"), t.plusMinutes(40), "-11.00"))));
                    awaitRowLockWait(reading.get().thread, "shipment");
                });
        MvcResult rejected = reading.get().join();

        assertThat(status(arrived)).isEqualTo(200);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("SHIPMENT_NOT_IN_TRANSIT");
        assertThat(alertRows(m.shipmentId())).isEmpty();
        assertThat(riskStatus(m.batch(0))).isEqualTo("NORMAL");
        assertThat(count("SELECT count(*) FROM temperature_record WHERE shipment_id = ?", m.shipmentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("人工冻结先持有批次行锁、告警自动冻结等待：告警只快照该批次为 FROZEN（无 ALERT 转换），其余批次正常自动冻结")
    void manualFreezeFirst_thenAlertSnapshotsFrozen() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("RACE-MF", twoHoursAgo(), "600", "360");
        senderQm();

        AtomicReference<Running> reading = new AtomicReference<>();
        MvcResult manual = gated(BatchRiskTransitionMapper.class, "insert",
                () -> perform(freezeRequest(senderQm(), m.batch(0), "在途抽检异常", key("idem-risk-f"))),
                () -> {
                    reading.set(background("pb3-reading-B", () -> perform(recordReq(m.shipmentId(), key("idem-temp-b"),
                            m.loadedAt().plusMinutes(5), "-10.00"))));
                    awaitRowLockWait(reading.get().thread, "batch");
                });
        MvcResult recorded = reading.get().join();

        assertThat(status(manual)).isEqualTo(201);
        assertThat(status(recorded)).isEqualTo(201);
        Long alertId = onlyAlertId(m.shipmentId());
        assertThat(jdbcTemplate.queryForMap("SELECT risk_status_before, freeze_transition_id FROM alert_batch WHERE alert_id = ? AND batch_id = ?",
                alertId, m.batch(0))).containsEntry("risk_status_before", "FROZEN").containsEntry("freeze_transition_id", null);
        assertThat(ledgerRows(m.batch(0))).isEqualTo(1);
        assertThat(alertLedgerRows(m.batch(0), alertId)).isZero();
        assertThat(alertLedgerRows(m.batch(1), alertId)).isEqualTo(1);
        assertLedgerConsistent(m.batch(0));
        assertLedgerConsistent(m.batch(1));
    }

    @Test
    @DisplayName("告警自动冻结先持有批次行锁、人工冻结等待：告警提交后人工冻结 409 INVALID_STATE_TRANSITION，只有一条 ALERT 转换")
    void alertFreezeFirst_thenManualFreezeRejected() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("RACE-AF", twoHoursAgo(), "500");
        senderQm();

        AtomicReference<Running> manual = new AtomicReference<>();
        MvcResult recorded = gated(AlertBatchMapper.class, "insert",
                () -> perform(recordReq(m.shipmentId(), key("idem-temp-a"), m.loadedAt().plusMinutes(5), "-10.00")),
                () -> {
                    manual.set(background("pb3-manual-B", () -> perform(freezeRequest(senderQm(), m.batch(0), "在途抽检异常", key("idem-risk-f")))));
                    awaitRowLockWait(manual.get().thread, "batch");
                });
        MvcResult rejected = manual.get().join();

        assertThat(status(recorded)).isEqualTo(201);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(ledgerRows(m.batch(0))).isEqualTo(1);
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
        assertLedgerConsistent(m.batch(0));
    }

    @Test
    @DisplayName("人工解除冻结先持有批次行锁、告警等待：解除提交后告警把该批次重新自动冻结——告警提交后批次一定处于冻结")
    void manualReleaseFirst_thenAlertRefreezes() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("RACE-MR", twoHoursAgo(), "500");
        freeze(senderQm(), m.batch(0), "在途前抽检");

        AtomicReference<Running> reading = new AtomicReference<>();
        MvcResult released = gated(BatchRiskTransitionMapper.class, "insert",
                () -> perform(releaseRequest(senderQm(), m.batch(0), "复检合格", key("idem-risk-r"))),
                () -> {
                    reading.set(background("pb3-reading-B", () -> perform(recordReq(m.shipmentId(), key("idem-temp-b"),
                            m.loadedAt().plusMinutes(5), "-10.00"))));
                    awaitRowLockWait(reading.get().thread, "batch");
                });
        MvcResult recorded = reading.get().join();

        assertThat(status(released)).isEqualTo(201);
        assertThat(status(recorded)).isEqualTo(201);
        Long alertId = onlyAlertId(m.shipmentId());
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
        assertThat(ledgerRows(m.batch(0))).as("manual freeze, manual release, alert freeze").isEqualTo(3);
        assertThat(alertLedgerRows(m.batch(0), alertId)).isEqualTo(1);
        assertLedgerConsistent(m.batch(0));
    }

    @Test
    @DisplayName("并发确认：告警行锁串行化——不同幂等键的后到者 409 INVALID_STATE_TRANSITION，相同键同语义的后到者重放；只有一条确认动作")
    void concurrentAcknowledge_serializedByAlertLock() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("RACE-ACK", twoHoursAgo(), "500");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        Long alertId = onlyAlertId(m.shipmentId());
        senderQm();
        String sharedKey = key("idem-ack-shared");

        AtomicReference<Running> other = new AtomicReference<>();
        AtomicReference<Running> same = new AtomicReference<>();
        MvcResult first = gated(AlertActionMapper.class, "insert",
                () -> perform(acknowledgeReq(senderQm(), alertId, "确认", sharedKey)),
                () -> {
                    other.set(background("pb3-ack-other", () -> perform(acknowledgeReq(senderQm(), alertId, "确认", key("idem-ack-b")))));
                    same.set(background("pb3-ack-same", () -> perform(acknowledgeReq(senderQm(), alertId, "确认", sharedKey))));
                    awaitRowLockWaits(other.get().thread, "alert", 2);
                });
        MvcResult otherResult = other.get().join();
        MvcResult sameResult = same.get().join();

        assertThat(status(first)).isEqualTo(200);
        assertThat(status(otherResult)).isEqualTo(409);
        assertThat(code(otherResult)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(status(sameResult)).isEqualTo(200);
        JsonNode replayed = data(sameResult);
        assertThat(replayed.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ?", alertId)).isEqualTo(1);
    }
}
