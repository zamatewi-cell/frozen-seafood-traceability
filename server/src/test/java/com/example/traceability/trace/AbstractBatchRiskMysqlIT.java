package com.example.traceability.trace;

import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchRiskTransitionRequest;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB1 批次风险状态真实 MySQL 8.4 集成测试共享夹具：质量管理员会话与风险冻结 / 解除 / 历史及 Phase A 守卫请求辅助。
 * <p>
 * 风险状态一律经真实 PB1 API 改变（不直接写 risk_status）；只有 RECALLED 夹具因 PB5 尚未实现仍使用 SQL。
 * </p>
 */
abstract class AbstractBatchRiskMysqlIT extends AbstractTransferShipmentMysqlIT {

    protected static final String QM_ROLE = "QUALITY_MANAGER";

    protected MockHttpServletRequestBuilder freezeRequest(MockHttpSession session, Long batchId, String reason, String idemKey) throws Exception {
        return postJson(session, "/api/v1/batches/" + batchId + "/risk/freeze", idemKey, new BatchRiskTransitionRequest(reason));
    }

    protected MockHttpServletRequestBuilder releaseRequest(MockHttpSession session, Long batchId, String reason, String idemKey) throws Exception {
        return postJson(session, "/api/v1/batches/" + batchId + "/risk/release", idemKey, new BatchRiskTransitionRequest(reason));
    }

    protected JsonNode freeze(MockHttpSession qm, Long batchId, String reason) throws Exception {
        return expect(freezeRequest(qm, batchId, reason, key("idem-risk-f")), 201).get("data");
    }

    protected JsonNode release(MockHttpSession qm, Long batchId, String reason) throws Exception {
        return expect(releaseRequest(qm, batchId, reason, key("idem-risk-r")), 201).get("data");
    }

    protected JsonNode history(MockHttpSession session, Long batchId) throws Exception {
        return expect(getReq(session, "/api/v1/batches/" + batchId + "/risk-transitions"), 200).get("data");
    }

    protected Map<String, Object> batchRow(Long batchId) {
        return jdbcTemplate.queryForMap("SELECT org_id, flow_status, risk_status, quantity, unit_code, product_id, trace_batch_no, "
                + "first_sale_id, consumed_by_operation_id, produced_by_operation_id, version FROM batch WHERE id = ?", batchId);
    }

    protected String riskStatus(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT risk_status FROM batch WHERE id = ?", String.class, batchId);
    }

    protected long batchVersion(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT version FROM batch WHERE id = ?", Long.class, batchId);
    }

    protected int ledgerRows(Long batchId) {
        return count("SELECT count(*) FROM batch_risk_transition WHERE batch_id = ?", batchId);
    }

    protected int auditRows(Long batchId, String action) {
        return count("SELECT count(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ? AND action = ?", batchId, action);
    }

    /** 除风险台账 / 审计以外的业务写入快照：风险转换不得产生任何此类副作用。 */
    protected List<Integer> businessSideEffects(Long batchId) {
        return List.of(
                count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId),
                count("SELECT count(*) FROM transfer WHERE batch_id = ?", batchId),
                count("SELECT count(*) FROM batch_relation WHERE parent_batch_id = ? OR child_batch_id = ?", batchId, batchId),
                count("SELECT count(*) FROM batch_operation_item WHERE batch_id = ?", batchId),
                count("SELECT count(*) FROM sale WHERE batch_id = ?", batchId),
                count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", batchId));
    }

    /**
     * 台账一致性：按登记顺序首尾相接（下一行 from = 上一行 to），批次当前风险状态等于最后一行 to，
     * 每行的流转快照等于转换时的批次流转状态，且台账行数与 RISK_FREEZE / RISK_RELEASE 审计数一一对应。
     */
    protected void assertLedgerConsistent(Long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT from_status, to_status, source_type, actor_user_id FROM batch_risk_transition WHERE batch_id = ? ORDER BY id", batchId);
        String previous = null;
        for (Map<String, Object> r : rows) {
            if (previous != null) {
                assertThat(r.get("from_status")).as("ledger chain continuity").isEqualTo(previous);
            }
            assertThat(r.get("source_type")).isEqualTo("MANUAL");
            assertThat(r.get("actor_user_id")).isNotNull();
            previous = (String) r.get("to_status");
        }
        if (previous != null) {
            assertThat(riskStatus(batchId)).as("batch.risk_status equals the newest ledger to_status").isEqualTo(previous);
        }
        long freezes = rows.stream().filter(r -> "FROZEN".equals(r.get("to_status"))).count();
        assertThat(auditRows(batchId, "RISK_FREEZE")).isEqualTo((int) freezes);
        assertThat(auditRows(batchId, "RISK_RELEASE")).isEqualTo(rows.size() - (int) freezes);
    }

    // =========================================================================
    // Phase A 守卫请求
    // =========================================================================

    protected MockHttpServletRequestBuilder createTransferRequest(MockHttpSession session, Long batchId, Long receiverOrgId, String idemKey) throws Exception {
        return postJson(session, "/api/v1/transfers", idemKey, new TransferCreateRequest(batchId, receiverOrgId));
    }

    protected MockHttpServletRequestBuilder acceptRequest(MockHttpSession session, Long transferId, BigDecimal qty, String idemKey) throws Exception {
        return postJson(session, "/api/v1/transfers/" + transferId + "/accept", idemKey,
                new TransferAcceptRequest(qty, "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(transferId)));
    }

    protected MockHttpServletRequestBuilder rejectRequest(MockHttpSession session, Long transferId, String idemKey) throws Exception {
        return postJson(session, "/api/v1/transfers/" + transferId + "/reject", idemKey,
                new TransferRejectRequest("到货拒收（风险冻结期间）", OffsetDateTime.now(ZoneOffset.UTC), transferVersion(transferId)));
    }

    protected MockHttpServletRequestBuilder warehouseRequest(MockHttpSession session, Long batchId, Site coldStore, String idemKey) throws Exception {
        return postJson(session, "/api/v1/batches/" + batchId + "/events", idemKey,
                new CreateTraceEventRequest("WAREHOUSE_IN", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), coldStore.getId(), "MANUAL", "冷库入库", null));
    }

    protected MockHttpServletRequestBuilder saleRequest(MockHttpSession session, Long batchId, Long storeId, String qty, String idemKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", storeId);
        body.put("quantity", new BigDecimal(qty));
        body.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).truncatedTo(ChronoUnit.MICROS).toString());
        return postJson(session, "/api/v1/batches/" + batchId + "/sales", idemKey, body);
    }

    protected MockHttpServletRequestBuilder activateCodeRequest(MockHttpSession session, Long batchId, String idemKey) throws Exception {
        return postJson(session, "/api/v1/batches/" + batchId + "/public-trace-code/activate", idemKey, null);
    }

    protected MockHttpServletRequestBuilder processRequest(MockHttpSession session, Long inputBatchId, String qty, String idemKey) throws Exception {
        String out = new BigDecimal(qty).subtract(BigDecimal.ONE).toPlainString();
        List<BatchOperationItemRequest> items = List.of(opInput(inputBatchId, qty), opOutput(out), opOther("LOSS", "1"));
        return createOperationRequest(session, idemKey, "PROCESS", items);
    }
}
