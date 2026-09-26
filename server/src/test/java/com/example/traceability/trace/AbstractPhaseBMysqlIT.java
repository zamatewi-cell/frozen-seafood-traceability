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
}
