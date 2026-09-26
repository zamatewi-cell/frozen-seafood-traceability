package com.example.traceability.trace;

import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一批次多个风险事项的确定性竞态（Phase B 独立评审修复；真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 证明的锁顺序：告警放行 alert(X) → batch(X)，并在批次行锁下读取批次的其他未解除风险事项；创建风险事项的告警温度登记
 * shipment → temperature_record → alert → batch（升序）后才插入受影响批次快照。因此：
 * <ul>
 *   <li>两个告警并发形成放行结论（任一顺序）：两个结论都记录，恰好一条 FROZEN → NORMAL 转换，来源是解除最后一个风险事项的那个告警；</li>
 *   <li>放行与新越界片段并发（任一顺序）：新告警要么看到已恢复正常的批次并重新冻结，要么先成为风险事项使放行只记录结论；
 *       批次最终都被新告警保持 FROZEN，不存在“有未处置告警却已恢复正常”的状态。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractPhaseBRaceMysqlIT.GateConfiguration.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("同一批次多个风险事项确定性竞态 MySQL 8.4 集成测试（独立评审修复）")
class AlertMultiHoldRaceMysqlIntegrationTest extends AbstractPhaseBRaceMysqlIT {

    private List<Map<String, Object>> releases(Long batchId) {
        return jdbcTemplate.queryForList("SELECT source_alert_id FROM batch_risk_transition WHERE batch_id = ? AND source_type = 'ALERT' "
                + "AND from_status = 'FROZEN' AND to_status = 'NORMAL'", batchId);
    }

    private Long decisionTransition(Long alertId, Long batchId) {
        return jdbcTemplate.queryForObject("SELECT risk_transition_id FROM alert_action WHERE alert_id = ? AND batch_id = ? "
                + "AND action = 'RELEASE_BATCH'", Long.class, alertId, batchId);
    }

    private void concurrentReleases(TwoAlerts t, Long first, Long second) throws Exception {
        inspect(senderQm(), t.batchId(), "SND-A1-PASS", "PASS", t.a1());
        inspect(senderQm(), t.batchId(), "SND-A2-PASS", "PASS", t.a2());

        AtomicReference<Running> other = new AtomicReference<>();
        MvcResult firstResult = gated(AlertActionMapper.class, "insert",
                () -> perform(releaseReq(senderQm(), first, t.batchId(), null, key("idem-rel-first"))),
                () -> {
                    other.set(background("pbfix-release-B", () -> perform(releaseReq(senderQm(), second, t.batchId(), null, key("idem-rel-second")))));
                    awaitRowLockWait(other.get().thread, "batch");
                });
        MvcResult secondResult = other.get().join();

        assertThat(status(firstResult)).isEqualTo(200);
        assertThat(status(secondResult)).isEqualTo(200);
        assertThat(riskStatus(t.batchId())).isEqualTo("NORMAL");
        List<Map<String, Object>> releases = releases(t.batchId());
        assertThat(releases).as("exactly one FROZEN -> NORMAL transition").hasSize(1);
        assertThat(((Number) releases.get(0).get("source_alert_id")).longValue()).as("sourced from the decision that cleared the last hold")
                .isEqualTo(second);
        assertThat(decisionTransition(first, t.batchId())).as("first decision: other alert still open").isNull();
        assertThat(decisionTransition(second, t.batchId())).as("second decision references the release").isNotNull();
        expect(resolveReq(senderQm(), t.a1(), "A1 复检合格", key("idem-res-a1")), 200);
        expect(resolveReq(senderQm(), t.a2(), "A2 复检合格", key("idem-res-a2")), 200);
        assertLedgerConsistent(t.batchId());
    }

    @Test
    @DisplayName("两个告警并发放行：A1 先持有告警与批次行锁、A2 在批次行锁上等待——A1 只记录结论，A2 解除最后一个风险事项并恢复 NORMAL")
    void concurrentReleases_a1First() throws Exception {
        TwoAlerts t = twoOpenAlertsOnOneBatch("MH1");
        concurrentReleases(t, t.a1(), t.a2());
    }

    @Test
    @DisplayName("两个告警并发放行：A2 先持有告警与批次行锁、A1 在批次行锁上等待——A2 只记录结论，A1 解除最后一个风险事项并恢复 NORMAL")
    void concurrentReleases_a2First() throws Exception {
        TwoAlerts t = twoOpenAlertsOnOneBatch("MH2");
        concurrentReleases(t, t.a2(), t.a1());
    }

    /** 允许越界时长 0：越界（A1，自动冻结）→ 回到范围内；运输任务仍在途，A1 已确认且有关联的合格检验。 */
    private Manifest inTransitWithOneReleasableAlert(String tag) throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest(tag, LocalDateTime.now(ZoneOffset.UTC).minusHours(2), "500");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(10), "-18.00");
        Long a1 = onlyAlertId(m.shipmentId());
        acknowledge(a1);
        inspect(senderQm(), m.batch(0), "SND-A1-PASS", "PASS", a1);
        return m;
    }

    @Test
    @DisplayName("放行先持有批次行锁、新越界片段的告警在批次行锁上等待：放行恢复 NORMAL 后新告警重新自动冻结，批次最终被新告警保持 FROZEN")
    void releaseFirst_thenNewEpisodeFreezesAgain() throws Exception {
        Manifest m = inTransitWithOneReleasableAlert("MH3");
        Long b = m.batch(0);
        Long a1 = onlyAlertId(m.shipmentId());

        AtomicReference<Running> reading = new AtomicReference<>();
        MvcResult released = gated(AlertActionMapper.class, "insert",
                () -> perform(releaseReq(senderQm(), a1, b, null, key("idem-rel-a1"))),
                () -> {
                    reading.set(background("pbfix-episode-B", () -> perform(recordReq(m.shipmentId(), key("idem-temp-b"),
                            m.loadedAt().plusMinutes(15), "-10.00"))));
                    awaitRowLockWait(reading.get().thread, "batch");
                });
        MvcResult recorded = reading.get().join();

        assertThat(status(released)).isEqualTo(200);
        assertThat(status(recorded)).isEqualTo(201);
        assertThat(decisionTransition(a1, b)).as("A1 was the only hold when it decided").isNotNull();
        List<Map<String, Object>> alerts = alertRows(m.shipmentId());
        assertThat(alerts).hasSize(2);
        Long a2 = ((Number) alerts.get(1).get("id")).longValue();
        assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ? AND batch_id = ? AND risk_status_before = 'NORMAL' "
                + "AND freeze_transition_id IS NOT NULL", a2, b)).as("new alert saw the released batch and froze it again").isEqualTo(1);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("新越界片段的告警先持有批次行锁（快照前）、放行在批次行锁上等待：新告警成为风险事项，放行只记录 A1 的结论，批次保持 FROZEN")
    void newEpisodeFirst_thenReleaseOnlyRecordsDecision() throws Exception {
        Manifest m = inTransitWithOneReleasableAlert("MH4");
        Long b = m.batch(0);
        Long a1 = onlyAlertId(m.shipmentId());

        AtomicReference<Running> release = new AtomicReference<>();
        MvcResult recorded = gated(AlertBatchMapper.class, "insert",
                () -> perform(recordReq(m.shipmentId(), key("idem-temp-a"), m.loadedAt().plusMinutes(15), "-10.00")),
                () -> {
                    release.set(background("pbfix-release-B", () -> perform(releaseReq(senderQm(), a1, b, null, key("idem-rel-a1")))));
                    awaitRowLockWait(release.get().thread, "batch");
                });
        MvcResult released = release.get().join();

        assertThat(status(recorded)).isEqualTo(201);
        assertThat(status(released)).isEqualTo(200);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertThat(decisionTransition(a1, b)).as("the new alert is still an open hold").isNull();
        assertThat(releases(b)).isEmpty();
        Long a2 = ((Number) alertRows(m.shipmentId()).get(1).get("id")).longValue();
        assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ? AND batch_id = ? AND risk_status_before = 'FROZEN' "
                + "AND freeze_transition_id IS NULL", a2, b)).as("new alert snapshotted the still frozen batch").isEqualTo(1);
        assertLedgerConsistent(b);
    }
}
