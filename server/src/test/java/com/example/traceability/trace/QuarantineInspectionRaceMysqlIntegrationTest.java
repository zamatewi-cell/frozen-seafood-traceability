package com.example.traceability.trace;

import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.InspectionReportMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB4 隔离收货与质量结论的确定性竞态（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 证明的锁顺序：隔离 / 接受 / 拒收 shipment(S) → transfer(X)（接受再锁 batch）；检验报告提交 batch(X)；告警放行 alert(X) → batch(X)，
 * 并在批次行锁之后读取最新检验结论。因此：
 * <ul>
 *   <li>隔离与接受同一交接：交接行锁串行化，任一顺序都只有一个决定生效（后到者 409），不会出现"既隔离又接受"；</li>
 *   <li>检验报告与放行：批次行锁串行化——先提交的不合格报告必然阻止之后的放行；先完成的放行不受之后报告影响（之后的报告仍被登记为证据）；</li>
 *   <li>隔离接收方的检验报告与接受同一隔离交接：报告持有批次行锁、接受持有 shipment → transfer 后等待批次；报告对隔离交接的外键检查
 *       只锁交接的复合唯一索引记录（接受不修改这些列），因此不形成 batch → transfer 反向等待，不死锁。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractPhaseBRaceMysqlIT.GateConfiguration.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("隔离收货与质量结论确定性竞态 MySQL 8.4 集成测试（PB4）")
class QuarantineInspectionRaceMysqlIntegrationTest extends AbstractPhaseBRaceMysqlIT {

    @Test
    @DisplayName("隔离先持有交接行锁、接受等待：隔离提交后接受 409（版本已变），交接为 QUARANTINED，责任组织仍为发送方")
    void quarantineFirst_thenAcceptRejected() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-RQA-" + suffix, new BigDecimal("300"));
        Handover h = prepareDeliveredHandover(batchId);

        AtomicReference<Running> accept = new AtomicReference<>();
        MvcResult quarantined = gated(TransferMapper.class, "quarantineByIdAndVersion",
                () -> perform(quarantineReq(receiverSession, h.transferId(), "300", null, receiverSite.getId(), "外包装破损", key("idem-q"))),
                () -> {
                    accept.set(background("pb4-accept-B", () -> perform(acceptRequest(receiverSession, h.transferId(),
                            new BigDecimal("300"), key("idem-acc")))));
                    awaitRowLockWait(accept.get().thread, "transfer");
                });
        MvcResult accepted = accept.get().join();

        assertThat(status(quarantined)).isEqualTo(200);
        assertThat(status(accepted)).isEqualTo(409);
        assertThat(code(accepted)).isEqualTo("VERSION_CONFLICT");
        assertThat(transferStatus(h.transferId())).isEqualTo("QUARANTINED");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
    }

    @Test
    @DisplayName("接受先持有交接行锁、隔离等待：接受提交后隔离 409 INVALID_STATE_TRANSITION，责任组织为接收方，交接没有隔离事实")
    void acceptFirst_thenQuarantineRejected() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-RAQ-" + suffix, new BigDecimal("300"));
        Handover h = prepareDeliveredHandover(batchId);

        AtomicReference<Running> quarantine = new AtomicReference<>();
        MvcResult accepted = gated(TransferMapper.class, "updateByIdAndVersion",
                () -> perform(acceptRequest(receiverSession, h.transferId(), new BigDecimal("300"), key("idem-acc"))),
                () -> {
                    quarantine.set(background("pb4-quarantine-B", () -> perform(quarantineReq(receiverSession, h.transferId(), "300", null,
                            receiverSite.getId(), "外包装破损", key("idem-q")))));
                    awaitRowLockWait(quarantine.get().thread, "transfer");
                });
        MvcResult quarantined = quarantine.get().join();

        assertThat(status(accepted)).isEqualTo(200);
        assertThat(status(quarantined)).isEqualTo(409);
        assertThat(code(quarantined)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(transferStatus(h.transferId())).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT quarantine_site_id FROM transfer WHERE id = ?", Long.class, h.transferId())).isNull();
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
    }

    @Test
    @DisplayName("不合格检验报告先持有批次行锁、放行等待：报告提交后放行读取到最新 FAIL，409 INSPECTION_NOT_PASSED，批次仍冻结")
    void failReportFirst_blocksRelease() throws Exception {
        Manifest m = deliveredAlertManifest("RACE-FR", "500");
        Long alertId = onlyAlertId(m.shipmentId());
        acknowledge(alertId);
        inspect(senderQm(), m.batch(0), "SND-PASS", "PASS", alertId);

        AtomicReference<Running> release = new AtomicReference<>();
        MvcResult failed = gated(InspectionReportMapper.class, "insert",
                () -> perform(inspectionReq(senderQm(), m.batch(0), "SND-FAIL", "FAIL", alertId, key("idem-insp"))),
                () -> {
                    release.set(background("pb4-release-B", () -> perform(releaseReq(senderQm(), alertId, m.batch(0), null, key("idem-rel")))));
                    awaitRowLockWait(release.get().thread, "batch");
                });
        MvcResult released = release.get().join();

        assertThat(status(failed)).isEqualTo(201);
        assertThat(status(released)).isEqualTo(409);
        assertThat(code(released)).isEqualTo("INSPECTION_NOT_PASSED");
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ? AND action = 'RELEASE_BATCH'", alertId)).isZero();
    }

    @Test
    @DisplayName("放行先持有告警与批次行锁、不合格报告等待：放行依据当时最新的 PASS 生效，之后的 FAIL 报告仍被登记为证据")
    void releaseFirst_thenLateFailReportRecorded() throws Exception {
        Manifest m = deliveredAlertManifest("RACE-RF", "500");
        Long alertId = onlyAlertId(m.shipmentId());
        acknowledge(alertId);
        Long passId = inspect(senderQm(), m.batch(0), "SND-PASS", "PASS", alertId).get("id").asLong();

        AtomicReference<Running> report = new AtomicReference<>();
        MvcResult released = gated(AlertActionMapper.class, "insert",
                () -> perform(releaseReq(senderQm(), alertId, m.batch(0), null, key("idem-rel"))),
                () -> {
                    report.set(background("pb4-report-B", () -> perform(inspectionReq(senderQm(), m.batch(0), "SND-FAIL", "FAIL", alertId,
                            key("idem-insp")))));
                    awaitRowLockWait(report.get().thread, "batch");
                });
        MvcResult reported = report.get().join();

        assertThat(status(released)).isEqualTo(200);
        assertThat(status(reported)).isEqualTo(201);
        assertThat(riskStatus(m.batch(0))).isEqualTo("NORMAL");
        assertThat(jdbcTemplate.queryForObject("SELECT inspection_report_id FROM alert_action WHERE alert_id = ? AND action = 'RELEASE_BATCH'",
                Long.class, alertId)).isEqualTo(passId);
    }

    @Test
    @DisplayName("隔离接收方的检验报告先持有批次行锁、从隔离接受等待（已持有 shipment → transfer）：无死锁，报告以 QUARANTINE_RECEIVER 登记后接受生效")
    void receiverReportFirst_thenAcceptFromQuarantine() throws Exception {
        Manifest m = deliveredAlertManifest("RACE-IA", "500");
        Long alertId = onlyAlertId(m.shipmentId());
        Long transferId = m.transferIds().get(0);
        quarantine(transferId, "500");
        acknowledge(alertId);
        inspect(receiverQm(), m.batch(0), "RCV-PASS", "PASS", alertId);
        expect(releaseReq(senderQm(), alertId, m.batch(0), null, key("idem-rel")), 200);

        AtomicReference<Running> accept = new AtomicReference<>();
        MvcResult reported = gated(InspectionReportMapper.class, "insert",
                () -> perform(inspectionReq(receiverQm(), m.batch(0), "RCV-RECHECK", "PASS", alertId, key("idem-insp-2"))),
                () -> {
                    accept.set(background("pb4-accept-B", () -> perform(acceptRequest(receiverSession, transferId, new BigDecimal("500"),
                            key("idem-acc")))));
                    awaitRowLockWait(accept.get().thread, "batch");
                });
        MvcResult accepted = accept.get().join();

        assertThat(status(reported)).isEqualTo(201);
        assertThat(data(reported).get("submitterRole").asString()).isEqualTo("QUARANTINE_RECEIVER");
        assertThat(status(accepted)).isEqualTo(200);
        assertThat(transferStatus(transferId)).isEqualTo("ACCEPTED");
        assertThat(batchOrgId(m.batch(0))).isEqualTo(receiverOrg.getId());
    }
}
