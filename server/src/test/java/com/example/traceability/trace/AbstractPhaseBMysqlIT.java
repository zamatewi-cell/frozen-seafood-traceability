package com.example.traceability.trace;

import com.example.traceability.quality.dto.AlertAcknowledgeRequest;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.dto.ShipmentDispatchRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B（PB3 起）异常闭环真实 MySQL 8.4 集成测试共享夹具：多批次在途运输任务（可指定过去的装载时间，便于构造跨越
 * 允许越界时长的测量序列）、在途温度登记、告警查询与处置请求、发货方 / 接收方质量管理员会话。
 * 全部业务状态经真实 API 改变。
 */
abstract class AbstractPhaseBMysqlIT extends AbstractBatchRiskMysqlIT {

    protected static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    protected static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

    /**
     * 一个装载多张交接的运输任务。
     *
     * @param shipmentId  运输任务 ID
     * @param transferIds 交接 ID（与 batchIds 一一对应）
     * @param batchIds    装载批次 ID（按创建顺序，即 ID 升序）
     * @param loadedAt    装载发运业务时间（UTC）
     */
    protected record Manifest(Long shipmentId, List<Long> transferIds, List<Long> batchIds, LocalDateTime loadedAt) {

        Long batch(int i) {
            return batchIds.get(i);
        }

        Long transfer(int i) {
            return transferIds.get(i);
        }
    }

    /** 本测试用例开始时间（UTC，秒精度）：构造确定性的业务时间，使同键重放的请求语义一致。 */
    protected final LocalDateTime testStartedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    private MockHttpSession senderQmSession;
    private MockHttpSession receiverQmSession;

    protected MockHttpSession senderQm() throws Exception {
        if (senderQmSession == null) {
            senderQmSession = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        }
        return senderQmSession;
    }

    protected MockHttpSession receiverQm() throws Exception {
        if (receiverQmSession == null) {
            receiverQmSession = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        }
        return receiverQmSession;
    }

    protected static String iso(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).format(MICROS);
    }

    /**
     * 发货方创建 {@code quantities.length} 个 ACTIVE 批次与各自的交接，绑定到同一个运输任务并提交；承运商以指定装载时间确认发运。
     */
    protected Manifest inTransitManifest(String tag, LocalDateTime loadedAt, String... quantities) throws Exception {
        List<Long> batches = new ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            batches.add(createAndSubmitActiveBatch(senderSession, "EXT-" + tag + "-" + i + "-" + suffix, new BigDecimal(quantities[i])));
        }
        return inTransitManifestOf(batches, loadedAt);
    }

    /**
     * 对给定的发货方 ACTIVE 批次建立交接 → 同一运输任务 → 提交 → 以指定装载时间发运。
     */
    protected Manifest inTransitManifestOf(List<Long> batches, LocalDateTime loadedAt) throws Exception {
        Long shipmentId = createShipment(senderSession);
        List<Long> transfers = new ArrayList<>();
        for (Long batchId : batches) {
            Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
            bind(shipmentId, transferId);
            transfers.add(transferId);
        }
        for (Long transferId : transfers) {
            submit(transferId);
        }
        LocalDateTime loaded = loadedAt.truncatedTo(ChronoUnit.MICROS);
        expect(postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/dispatch", key("idem-shp-d"),
                new ShipmentDispatchRequest(loaded.atOffset(ZoneOffset.UTC), shipmentVersion(shipmentId))), 200);
        return new Manifest(shipmentId, transfers, batches, loaded);
    }

    protected MockHttpServletRequestBuilder recordReq(Long shipmentId, String idemKey, LocalDateTime measuredAt, String temperature) throws Exception {
        return temperatureRequest(carrierSession, shipmentId, idemKey, iso(measuredAt), temperature, "MANUAL", null);
    }

    protected JsonNode recordAt(Long shipmentId, LocalDateTime measuredAt, String temperature) throws Exception {
        return expect(recordReq(shipmentId, key("idem-temp"), measuredAt, temperature), 201).get("data");
    }

    /** 以指定到达时间确认到达（到达时间不能早于最新测量时间）。 */
    protected void arriveAt(Long shipmentId, LocalDateTime unloadedAt) throws Exception {
        expect(arriveReq(shipmentId, unloadedAt), 200);
    }

    protected MockHttpServletRequestBuilder arriveReq(Long shipmentId, LocalDateTime unloadedAt) throws Exception {
        return postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/arrive", key("idem-shp-a"),
                new ShipmentArriveRequest(unloadedAt.atOffset(ZoneOffset.UTC), shipmentVersion(shipmentId)));
    }

    // =========================================================================
    // 告警
    // =========================================================================

    protected List<Map<String, Object>> alertRows(Long shipmentId) {
        return jdbcTemplate.queryForList("SELECT * FROM alert WHERE shipment_id = ? ORDER BY id", shipmentId);
    }

    protected Long onlyAlertId(Long shipmentId) {
        List<Map<String, Object>> rows = alertRows(shipmentId);
        assertThat(rows).as("exactly one alert on shipment %s", shipmentId).hasSize(1);
        return ((Number) rows.get(0).get("id")).longValue();
    }

    protected JsonNode alertDetail(MockHttpSession session, Long alertId) throws Exception {
        return expect(getReq(session, "/api/v1/alerts/" + alertId), 200).get("data");
    }

    protected MockHttpServletRequestBuilder acknowledgeReq(MockHttpSession session, Long alertId, String note, String idemKey) throws Exception {
        return postJson(session, "/api/v1/alerts/" + alertId + "/acknowledge", idemKey, new AlertAcknowledgeRequest(note));
    }

    protected JsonNode acknowledge(Long alertId) throws Exception {
        return expect(acknowledgeReq(senderQm(), alertId, "发货方质量管理员确认在途超温异常", key("idem-alert-ack")), 200).get("data");
    }

    protected int alertLedgerRows(Long batchId, Long alertId) {
        return count("SELECT count(*) FROM batch_risk_transition WHERE batch_id = ? AND source_type = 'ALERT' AND source_alert_id = ?",
                batchId, alertId);
    }

    // =========================================================================
    // PB4：隔离收货、检验报告、告警放行与处置结论
    // =========================================================================

    /**
     * 发货方 {@code quantities.length} 个批次装载同一运输任务 → 承运商发运 → 允许越界时长 0 的规则下登记一条越界温度
     * （形成告警并自动冻结全部批次）→ 承运商确认到达。返回装载清单，告警 ID 通过 {@link #onlyAlertId} 获取。
     */
    protected Manifest deliveredAlertManifest(String tag, String... quantities) throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest(tag, LocalDateTime.now(ZoneOffset.UTC).minusHours(2), quantities);
        LocalDateTime measured = m.loadedAt().plusMinutes(5);
        recordAt(m.shipmentId(), measured, "-10.00");
        arriveAt(m.shipmentId(), measured.plusMinutes(1));
        return m;
    }

    protected MockHttpServletRequestBuilder quarantineReq(MockHttpSession session, Long transferId, String qty, String differenceReason,
                                                         Long siteId, String reason, String idemKey) throws Exception {
        return quarantineReq(session, transferId, qty, differenceReason, siteId, reason, idemKey, transferVersion(transferId));
    }

    /** 同上，显式指定期望版本（幂等重放必须原样重发首次请求的版本）。 */
    protected MockHttpServletRequestBuilder quarantineReq(MockHttpSession session, Long transferId, String qty, String differenceReason,
                                                         Long siteId, String reason, String idemKey, long expectedVersion) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("receivedQuantity", new BigDecimal(qty));
        body.put("unitCode", "kg");
        // 到货时间取运输到达时间后 1 秒：确定性（同一交接的重放语义一致）且不早于运输到达
        LocalDateTime unloadedAt = jdbcTemplate.queryForObject(
                "SELECT s.unloaded_at FROM transfer t JOIN shipment s ON s.id = t.shipment_id WHERE t.id = ?", LocalDateTime.class, transferId);
        body.put("occurredAt", iso((unloadedAt == null ? LocalDateTime.now(ZoneOffset.UTC) : unloadedAt).plusSeconds(1)));
        if (differenceReason != null) {
            body.put("differenceReason", differenceReason);
        }
        body.put("quarantineSiteId", siteId);
        body.put("reason", reason);
        body.put("expectedVersion", expectedVersion);
        return postJson(session, "/api/v1/transfers/" + transferId + "/quarantine", idemKey, body);
    }

    protected JsonNode quarantine(Long transferId, String qty) throws Exception {
        return expect(quarantineReq(receiverSession, transferId, qty, null, receiverSite.getId(), "到货随附持续超温告警，隔离待检",
                key("idem-trf-q")), 200).get("data");
    }

    protected MockHttpServletRequestBuilder inspectionReq(MockHttpSession session, Long batchId, String reportNo, String conclusion,
                                                          Long alertId, String idemKey) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("reportNo", reportNo);
        body.put("institutionName", "教学演示检测中心");
        body.put("inspectedAt", iso(testStartedAt.minusMinutes(1)));
        body.put("itemsSummary", "中心温度、感官与微生物抽检");
        body.put("conclusion", conclusion);
        body.put("dataSource", "SIMULATED");
        if (alertId != null) {
            body.put("alertId", alertId);
        }
        return postJson(session, "/api/v1/batches/" + batchId + "/inspection-reports", idemKey, body);
    }

    protected JsonNode inspect(MockHttpSession session, Long batchId, String reportNo, String conclusion, Long alertId) throws Exception {
        return expect(inspectionReq(session, batchId, reportNo, conclusion, alertId, key("idem-insp")), 201).get("data");
    }

    protected MockHttpServletRequestBuilder releaseReq(MockHttpSession session, Long alertId, Long batchId, String note, String idemKey) throws Exception {
        return postJson(session, "/api/v1/alerts/" + alertId + "/batches/" + batchId + "/release", idemKey,
                com.example.traceability.quality.dto.AlertDecisionRequest.release(note));
    }

    protected MockHttpServletRequestBuilder resolveReq(MockHttpSession session, Long alertId, String resolution, String idemKey) throws Exception {
        return postJson(session, "/api/v1/alerts/" + alertId + "/resolve", idemKey,
                com.example.traceability.quality.dto.AlertDecisionRequest.resolve(resolution));
    }
}
