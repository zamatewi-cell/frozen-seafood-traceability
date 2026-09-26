package com.example.traceability.trace;

import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.trace.dto.ShipmentCancelRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase B PB2 Shipment 在途温度记录（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 覆盖：单点判定与规则环节 / 上下限快照、规则版本按测量业务时间的左闭右开选择与登记后不追溯改写、
 * Demo MVP 同一运输任务只装载同一产品（D1）与历史混装清单的 MISSING_CONTEXT、到达时间不早于最新测量时间（D2）、
 * 状态与测量时间窗口、权限矩阵、以规范化微秒时间为语义的幂等、同一测量时间允许多条记录与 (measuredAt, id) 稳定排序、
 * 与 PB1 风险冻结互不影响、匿名公开投影不变且不泄露任何温度数据。登记从不修改运输任务 / 交接 / 批次，不生成 TraceEvent，
 * 不写批次风险状态。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("Shipment 在途温度记录 MySQL 8.4 集成测试（PB2）")
class ShipmentTemperatureMysqlIntegrationTest extends AbstractBatchRiskMysqlIT {

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

    private static String iso(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).format(MICROS);
    }

    private String recordsUrl(Long shipmentId) {
        return "/api/v1/shipments/" + shipmentId + "/temperature-records";
    }

    private JsonNode record(Long shipmentId, LocalDateTime measuredAt, String temperature) throws Exception {
        return expect(temperatureRequest(carrierSession, shipmentId, key("idem-temp"), iso(measuredAt), temperature, "MANUAL", null), 201)
                .get("data");
    }

    private JsonNode list(MockHttpSession session, Long shipmentId) throws Exception {
        return expect(getReq(session, recordsUrl(shipmentId)), 200).get("data");
    }

    /** 以指定到达时间确认到达（在途测量时间可能晚于当前时钟，到达时间不能早于最新测量时间）。 */
    private void arriveAt(Long shipmentId, LocalDateTime unloadedAt) throws Exception {
        expect(postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/arrive", key("idem-shp-a"),
                new com.example.traceability.trace.dto.ShipmentArriveRequest(unloadedAt.atOffset(ZoneOffset.UTC), shipmentVersion(shipmentId))), 200);
    }

    private int records(Long shipmentId) {
        return count("SELECT count(*) FROM temperature_record WHERE shipment_id = ?", shipmentId);
    }

    /** 登记不得产生的副作用：运输任务 / 交接 / 批次行、追溯事件、风险台账与 Alert。 */
    private List<Object> sideEffectSnapshot(Handover h, Long batchId) {
        List<Object> snapshot = new ArrayList<>();
        snapshot.add(jdbcTemplate.queryForMap("SELECT status, version, loaded_at, unloaded_at FROM shipment WHERE id = ?", h.shipmentId()));
        snapshot.add(jdbcTemplate.queryForMap("SELECT status, version, shipment_id FROM transfer WHERE id = ?", h.transferId()));
        snapshot.add(batchRow(batchId));
        snapshot.add(businessSideEffects(batchId));
        snapshot.add(ledgerRows(batchId));
        snapshot.add(count("SELECT count(*) FROM alert"));
        return snapshot;
    }

    private Long activeBatch(String tag) throws Exception {
        return createAndSubmitActiveBatch(senderSession, "EXT-TEMP-" + tag + "-" + suffix, new BigDecimal("1000"));
    }

    // =========================================================================
    // 单点判定、规则版本与判定依据快照
    // =========================================================================

    @Test
    @DisplayName("单点判定：NORMAL / HIGH / LOW（上下限属于范围内），记录绑定运输任务并固定规则环节与上下限快照；不写运输任务 / 交接 / 批次 / 事件 / 风险台账")
    void singlePointEvaluation_persistsSnapshot_withoutSideEffects() throws Exception {
        Long stageId = publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long batchId = activeBatch("EVAL");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        List<Object> before = sideEffectSnapshot(h, batchId);

        JsonNode normal = record(h.shipmentId(), loadedAt.plusSeconds(10), "-18.5");
        JsonNode high = expect(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(loadedAt.plusSeconds(20)),
                "-12.50", "SIMULATED", "PROBE-01"), 201).get("data");
        JsonNode low = record(h.shipmentId(), loadedAt.plusSeconds(30), "-25.01");
        JsonNode atLower = record(h.shipmentId(), loadedAt.plusSeconds(40), "-25.00");
        JsonNode atUpper = record(h.shipmentId(), loadedAt.plusSeconds(50), "-15.00");

        assertThat(List.of(normal, high, low, atLower, atUpper)).extracting(n -> n.get("evaluation").asString())
                .containsExactly("NORMAL", "HIGH", "LOW", "NORMAL", "NORMAL");
        assertThat(high.get("dataSource").asString()).isEqualTo("SIMULATED");
        assertThat(high.get("deviceNo").asString()).isEqualTo("PROBE-01");
        assertThat(normal.get("stageCode").asString()).isEqualTo("TRANSPORT");
        assertThat(normal.get("unitCode").asString()).isEqualTo("CELSIUS");
        assertThat(normal.get("temperature").decimalValue()).isEqualByComparingTo("-18.50");
        assertThat(normal.get("rule").get("ruleStageId").asLong()).isEqualTo(stageId);
        assertThat(normal.get("rule").get("versionNo").asInt()).isEqualTo(1);
        assertThat(normal.get("rule").get("lowerLimit").decimalValue()).isEqualByComparingTo("-25.00");
        assertThat(normal.get("rule").get("upperLimit").decimalValue()).isEqualByComparingTo("-15.00");
        assertThat(normal.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(1800);
        assertThat(normal.get("orgId").asLong()).isEqualTo(carrierOrg.getId());
        assertThat(normal.get("actorUserId").asLong()).isEqualTo(carrierUser.getId());
        assertThat(normal.get("measuredAt").asString()).isEqualTo(iso(loadedAt.plusSeconds(10)));
        assertThat(normal.has("idempotencyKey")).isFalse();
        assertThat(normal.has("requestHash")).isFalse();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT org_id, actor_user_id, stage_code, unit_code, rule_stage_id, "
                + "rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, evaluation, batch_id, is_deleted "
                + "FROM temperature_record WHERE shipment_id = ? ORDER BY id",
                h.shipmentId());
        assertThat(rows).hasSize(5).allSatisfy(r -> {
            assertThat(((Number) r.get("org_id")).longValue()).isEqualTo(carrierOrg.getId());
            assertThat(((Number) r.get("actor_user_id")).longValue()).isEqualTo(carrierUser.getId());
            assertThat(r.get("stage_code")).isEqualTo("TRANSPORT");
            assertThat(r.get("unit_code")).isEqualTo("CELSIUS");
            assertThat(((Number) r.get("rule_stage_id")).longValue()).isEqualTo(stageId);
            assertThat((BigDecimal) r.get("rule_lower_limit")).isEqualByComparingTo("-25.00");
            assertThat((BigDecimal) r.get("rule_upper_limit")).isEqualByComparingTo("-15.00");
            assertThat(((Number) r.get("rule_allowed_duration_seconds")).intValue()).isEqualTo(1800);
            assertThat(r.get("batch_id")).isNull();
        });
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'TEMPERATURE_RECORD' AND object_type = 'SHIPMENT' AND object_id = ?",
                h.shipmentId())).isEqualTo(5);
        assertThat(sideEffectSnapshot(h, batchId)).as("recording never touches shipment / transfer / batch / events / risk / alert")
                .isEqualTo(before);
        assertThat(riskStatus(batchId)).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("规则版本：按测量业务时间左闭右开选择（v1 截止瞬间属于 v2）；没有适用规则为 MISSING_CONTEXT，之后发布的规则不追溯改写已登记判定")
    void ruleVersionSelection_andNoRetroactiveRewrite() throws Exception {
        Long batchId = activeBatch("RULE");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        LocalDateTime switchAt = loadedAt.plusSeconds(60);

        // 尚无规则：MISSING_CONTEXT，四项快照为空；DRAFT 规则不参与匹配
        jdbcTemplate.update("INSERT INTO temperature_rule (product_id, version_no, name, effective_from, status) VALUES (?, 99, ?, ?, 'DRAFT')",
                testProduct.getId(), "草稿规则-" + suffix, LONG_AGO);
        Long draftRuleId = jdbcTemplate.queryForObject("SELECT id FROM temperature_rule WHERE product_id = ? AND version_no = 99", Long.class, testProduct.getId());
        jdbcTemplate.update("INSERT INTO temperature_rule_stage (rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no) "
                + "VALUES (?, 'TRANSPORT', -30.00, 0.00, 'CELSIUS', 0, 1)", draftRuleId);
        JsonNode missing = record(h.shipmentId(), loadedAt.plusSeconds(5), "-18.00");
        assertThat(missing.get("evaluation").asString()).isEqualTo("MISSING_CONTEXT");
        assertThat(missing.has("rule")).isFalse();
        assertThat(jdbcTemplate.queryForMap("SELECT stage_code, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds "
                        + "FROM temperature_record WHERE id = ?", missing.get("id").asLong()))
                .containsEntry("stage_code", "TRANSPORT").containsEntry("rule_stage_id", null)
                .containsEntry("rule_lower_limit", null).containsEntry("rule_upper_limit", null)
                .containsEntry("rule_allowed_duration_seconds", null);

        // v1 [2020, switchAt) 下限 -25 上限 -15 允许 1800 秒；v2 [switchAt, ∞) 下限 -20 上限 -10 允许 600 秒
        // （均覆盖此前 MISSING_CONTEXT 的测量时间点之后发布）
        Long v1 = publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, switchAt);
        Long v2 = publishTransportRule(testProduct.getId(), "-20.00", "-10.00", switchAt, null, 600);
        JsonNode beforeSwitch = record(h.shipmentId(), switchAt.minusNanos(1_000), "-12.00");
        JsonNode atSwitch = record(h.shipmentId(), switchAt, "-12.00");
        assertThat(beforeSwitch.get("rule").get("ruleStageId").asLong()).isEqualTo(v1);
        assertThat(beforeSwitch.get("evaluation").asString()).isEqualTo("HIGH");
        assertThat(beforeSwitch.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(1800);
        assertThat(atSwitch.get("rule").get("ruleStageId").asLong()).isEqualTo(v2);
        assertThat(atSwitch.get("evaluation").asString()).isEqualTo("NORMAL");
        assertThat(atSwitch.get("rule").get("allowedDurationSeconds").asInt()).as("each version's own duration is snapshotted").isEqualTo(600);

        // 登记后判定固定：规则发布之后，此前的 MISSING_CONTEXT 记录读取时仍为 MISSING_CONTEXT
        JsonNode all = list(carrierSession, h.shipmentId());
        assertThat(all.get(0).get("id").asLong()).isEqualTo(missing.get("id").asLong());
        assertThat(all.get(0).get("evaluation").asString()).isEqualTo("MISSING_CONTEXT");
        assertThat(all.get(0).has("rule")).isFalse();
    }

    @Test
    @DisplayName("历史判定依据不漂移：登记后绕过应用直接改动被引用规则环节的允许越界时长与上下限，数据库快照、列表与幂等重放仍是登记时的值；之后的新登记按改动后的环节保存新快照")
    void historicalBasis_doesNotDriftWhenReferencedStageChanges() throws Exception {
        Long stageId = publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long batchId = activeBatch("DRIFT");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        String k = key("idem-temp-drift");
        String measuredAt = iso(loadedAt.plusSeconds(5));
        JsonNode original = expect(temperatureRequest(carrierSession, h.shipmentId(), k, measuredAt, "-12.00", "MANUAL", null), 201).get("data");
        long recordId = original.get("id").asLong();
        assertThat(original.get("evaluation").asString()).isEqualTo("HIGH");
        assertThat(original.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(1800);

        // 规则环节没有数据库级不可变约束：模拟正常应用流程之外的直接改动
        assertThat(jdbcTemplate.update("UPDATE temperature_rule_stage SET allowed_duration_seconds = 60, lower_limit = -40.00, "
                + "upper_limit = -5.00 WHERE id = ?", stageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT allowed_duration_seconds FROM temperature_rule_stage WHERE id = ?", Integer.class, stageId))
                .isEqualTo(60);

        // 1. 数据库中的历史快照不变（外键仍指向同一环节，只作来源追溯）
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT rule_stage_id, rule_lower_limit, rule_upper_limit, "
                + "rule_allowed_duration_seconds, evaluation FROM temperature_record WHERE id = ?", recordId);
        assertThat(((Number) row.get("rule_stage_id")).longValue()).isEqualTo(stageId);
        assertThat((BigDecimal) row.get("rule_lower_limit")).isEqualByComparingTo("-25.00");
        assertThat((BigDecimal) row.get("rule_upper_limit")).isEqualByComparingTo("-15.00");
        assertThat(((Number) row.get("rule_allowed_duration_seconds")).intValue()).isEqualTo(1800);
        assertThat(row.get("evaluation")).isEqualTo("HIGH");

        // 2. 读取已有记录的响应只来自快照，不重新读取当前规则环节
        JsonNode listed = list(receiverSession, h.shipmentId()).get(0);
        assertThat(listed.get("id").asLong()).isEqualTo(recordId);
        assertThat(listed.get("evaluation").asString()).isEqualTo("HIGH");
        assertThat(listed.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(1800);
        assertThat(listed.get("rule").get("lowerLimit").decimalValue()).isEqualByComparingTo("-25.00");
        assertThat(listed.get("rule").get("upperLimit").decimalValue()).isEqualByComparingTo("-15.00");
        assertThat(listed.get("rule")).isEqualTo(original.get("rule"));

        // 3. 幂等重放同样返回登记时的快照
        JsonNode replayed = expect(temperatureRequest(carrierSession, h.shipmentId(), k, measuredAt, "-12.00", "MANUAL", null), 201).get("data");
        assertThat(replayed.get("id").asLong()).isEqualTo(recordId);
        assertThat(replayed.get("rule")).isEqualTo(original.get("rule"));
        assertThat(replayed.get("evaluation").asString()).isEqualTo("HIGH");

        // 4. 之后的新登记按改动后的环节判定并保存它自己的快照
        JsonNode later = record(h.shipmentId(), loadedAt.plusSeconds(6), "-12.00");
        assertThat(later.get("evaluation").asString()).isEqualTo("NORMAL");
        assertThat(later.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(60);
        assertThat(later.get("rule").get("lowerLimit").decimalValue()).isEqualByComparingTo("-40.00");
        assertThat(records(h.shipmentId())).isEqualTo(2);
    }

    // =========================================================================
    // D1：Demo MVP 同一运输任务只装载同一产品
    // =========================================================================

    @Test
    @DisplayName("D1：不同产品的批次装载到同一运输任务 422 SHIPMENT_PRODUCT_MISMATCH（不绑定、版本不变）；同一产品第二个批次正常装载并按规则判定")
    void singleProductManifest_enforcedAtBind() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long a = activeBatch("D1A");
        Long sameProduct = activeBatch("D1B");
        Product other = createProduct("PRD_OTHER_" + suffix, "冷冻南美白虾-" + suffix);
        Long differentProduct = activeBatch("D1C");
        jdbcTemplate.update("UPDATE batch SET product_id = ? WHERE id = ?", other.getId(), differentProduct);

        Long shipmentId = createShipment(senderSession);
        Long tA = createDraftTransfer(senderSession, a, receiverOrg.getId());
        bind(shipmentId, tA);
        long versionBefore = shipmentVersion(shipmentId);

        Long tC = createDraftTransfer(senderSession, differentProduct, receiverOrg.getId());
        expectProblem(bindRequest(senderSession, shipmentId, tC, transferVersion(tC)), 422, "SHIPMENT_PRODUCT_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("SELECT shipment_id FROM transfer WHERE id = ?", Long.class, tC)).isNull();
        assertThat(shipmentVersion(shipmentId)).isEqualTo(versionBefore);

        Long tB = createDraftTransfer(senderSession, sameProduct, receiverOrg.getId());
        bind(shipmentId, tB);
        submit(tA);
        submit(tB);
        dispatch(shipmentId);
        JsonNode r = record(shipmentId, shipmentLoadedAt(shipmentId).plusSeconds(5), "-18.00");
        assertThat(r.get("evaluation").asString()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("D1 之前的历史混装清单（SQL 构造）：不猜测规则，记为 MISSING_CONTEXT")
    void legacyMixedProductManifest_missingContext() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long a = activeBatch("MIXA");
        Product other = createProduct("PRD_MIX_" + suffix, "冷冻鱿鱼-" + suffix);
        Long b = activeBatch("MIXB");
        jdbcTemplate.update("UPDATE batch SET product_id = ? WHERE id = ?", other.getId(), b);

        Long shipmentId = createShipment(senderSession);
        Long tA = createDraftTransfer(senderSession, a, receiverOrg.getId());
        bind(shipmentId, tA);
        Long tB = createDraftTransfer(senderSession, b, receiverOrg.getId());
        // 模拟 D1 之前已装载的混装清单：直接写入绑定关系
        jdbcTemplate.update("UPDATE transfer SET shipment_id = ?, version = version + 1 WHERE id = ?", shipmentId, tB);
        jdbcTemplate.update("UPDATE shipment SET version = version + 1 WHERE id = ?", shipmentId);
        submit(tA);
        submit(tB);
        dispatch(shipmentId);

        JsonNode r = record(shipmentId, shipmentLoadedAt(shipmentId).plusSeconds(5), "-18.00");
        assertThat(r.get("evaluation").asString()).isEqualTo("MISSING_CONTEXT");
        assertThat(r.has("rule")).isFalse();
    }

    // =========================================================================
    // D2：到达时间不早于最新测量时间
    // =========================================================================

    @Test
    @DisplayName("D2：到达时间早于最新在途温度测量时间 422 INVALID_BUSINESS_TIME（仍 IN_TRANSIT、无 ARRIVAL）；等于最新测量时间允许到达")
    void arrivalNotBeforeLatestMeasurement() throws Exception {
        Long batchId = activeBatch("D2");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        LocalDateTime latest = loadedAt.plusSeconds(90);
        record(h.shipmentId(), loadedAt.plusSeconds(30), "-18.00");
        record(h.shipmentId(), latest, "-18.00");
        record(h.shipmentId(), loadedAt.plusSeconds(60), "-18.00");

        expectProblem(postJson(carrierSession, "/api/v1/shipments/" + h.shipmentId() + "/arrive", key("idem-shp-a"),
                        new com.example.traceability.trace.dto.ShipmentArriveRequest(latest.minusNanos(1_000_000).atOffset(ZoneOffset.UTC),
                                shipmentVersion(h.shipmentId()))),
                422, "INVALID_BUSINESS_TIME");
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("IN_TRANSIT");
        assertThat(countEvents(batchId, "ARRIVAL")).isZero();

        JsonNode arrived = expect(postJson(carrierSession, "/api/v1/shipments/" + h.shipmentId() + "/arrive", key("idem-shp-a"),
                new com.example.traceability.trace.dto.ShipmentArriveRequest(latest.atOffset(ZoneOffset.UTC), shipmentVersion(h.shipmentId()))), 200)
                .get("data");
        assertThat(arrived.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
    }

    // =========================================================================
    // 状态与测量时间窗口
    // =========================================================================

    @Test
    @DisplayName("状态与时间窗口：PLANNED / DELIVERED / CANCELLED 409 SHIPMENT_NOT_IN_TRANSIT；早于装载发运时间或晚于当前时间 5 分钟以上 422；恰好等于装载发运时间允许")
    void stateAndMeasurementWindow() throws Exception {
        Long planned = createShipment(senderSession);
        expectProblem(temperatureRequest(carrierSession, planned, key("idem-temp"), iso(LocalDateTime.now(ZoneOffset.UTC)), "-18", "MANUAL", null),
                409, "SHIPMENT_NOT_IN_TRANSIT");
        expect(postJson(senderSession, "/api/v1/shipments/" + planned + "/cancel", key("idem-shp-x"),
                new ShipmentCancelRequest("计划取消", shipmentVersion(planned))), 200);
        expectProblem(temperatureRequest(carrierSession, planned, key("idem-temp"), iso(LocalDateTime.now(ZoneOffset.UTC)), "-18", "MANUAL", null),
                409, "SHIPMENT_NOT_IN_TRANSIT");

        Long batchId = activeBatch("WIN");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(loadedAt.minusNanos(1_000)), "-18", "MANUAL", null),
                422, "INVALID_BUSINESS_TIME");
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"),
                iso(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)), "-18", "MANUAL", null), 422, "INVALID_BUSINESS_TIME");
        JsonNode atLoaded = record(h.shipmentId(), loadedAt, "-18");
        assertThat(atLoaded.get("measuredAt").asString()).isEqualTo(iso(loadedAt));

        arrive(h.shipmentId());
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(loadedAt.plusSeconds(1)), "-18", "MANUAL", null),
                409, "SHIPMENT_NOT_IN_TRANSIT");
        assertThat(records(h.shipmentId())).isEqualTo(1);
        assertThat(records(planned)).isZero();
    }

    @Test
    @DisplayName("请求校验：IMPORT / DEVICE 422 TEMPERATURE_SOURCE_NOT_SUPPORTED；超过两位小数、超出范围、未声明字段 400；均不写入")
    void requestValidation() throws Exception {
        Long batchId = activeBatch("VAL");
        Handover h = prepareInTransitHandover(batchId);
        String t = iso(shipmentLoadedAt(h.shipmentId()).plusSeconds(5));
        for (String source : List.of("IMPORT", "DEVICE")) {
            expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), t, "-18", source, null), 422,
                    "TEMPERATURE_SOURCE_NOT_SUPPORTED");
        }
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), t, "-18.555", "MANUAL", null), 400, "INVALID_REQUEST");
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), t, "60.01", "MANUAL", null), 400, "INVALID_REQUEST");
        expectProblem(postJson(carrierSession, recordsUrl(h.shipmentId()), key("idem-temp"),
                Map.of("measuredAt", t, "temperature", -18, "dataSource", "MANUAL", "evaluation", "NORMAL")), 400, "INVALID_REQUEST");
        assertThat(records(h.shipmentId())).isZero();
    }

    // =========================================================================
    // 权限矩阵
    // =========================================================================

    @Test
    @DisplayName("权限：只有承运组织操作员可登记；发送方 / 接收方 / 无关组织 403 ORG_SCOPE_DENIED，承运质量管理员 403 ROLE_NOT_ALLOWED，平台 403 ADMIN_RESTRICTED，匿名 401，缺 CSRF 403，幂等键缺失或 SYS: 前缀 400；三方参与者（任意角色）与平台可读，无关组织 403，不存在 404")
    void authorizationMatrix() throws Exception {
        Long batchId = activeBatch("AUTH");
        Handover h = prepareInTransitHandover(batchId);
        String t = iso(shipmentLoadedAt(h.shipmentId()).plusSeconds(5));
        Organization unrelated = createOrg("ORG_U_" + suffix, "无关企业-" + suffix, "PROCESSOR");
        MockHttpSession unrelatedSession = newSession(unrelated, "unrel_op", "OPERATOR", "OWN_ORG");
        MockHttpSession carrierQm = newSession(carrierOrg, "car_qm", QM_ROLE, "OWN_ORG");
        MockHttpSession receiverQm = newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
        MockHttpSession platform = newSession(senderOrg, "plat_adm", "SYSTEM_ADMIN", "PLATFORM");

        for (MockHttpSession s : List.of(senderSession, receiverSession, unrelatedSession)) {
            expectProblem(temperatureRequest(s, h.shipmentId(), key("idem-temp"), t, "-18", "MANUAL", null), 403, "ORG_SCOPE_DENIED");
        }
        expectProblem(temperatureRequest(carrierQm, h.shipmentId(), key("idem-temp"), t, "-18", "MANUAL", null), 403, "ROLE_NOT_ALLOWED");
        expectProblem(temperatureRequest(platform, h.shipmentId(), key("idem-temp"), t, "-18", "MANUAL", null), 403, "ADMIN_RESTRICTED");
        expect(post(recordsUrl(h.shipmentId())).with(csrf()).header("Idempotency-Key", key("idem-temp"))
                .contentType("application/json").content("{\"measuredAt\":\"" + t + "\",\"temperature\":-18,\"dataSource\":\"MANUAL\"}"), 401);
        expect(post(recordsUrl(h.shipmentId())).session(carrierSession).header("Idempotency-Key", key("idem-temp"))
                .contentType("application/json").content("{\"measuredAt\":\"" + t + "\",\"temperature\":-18,\"dataSource\":\"MANUAL\"}"), 403);
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), null, t, "-18", "MANUAL", null), 400, "INVALID_IDEMPOTENCY_KEY");
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), "SYS:TEMP:" + suffix + "-000001", t, "-18", "MANUAL", null), 400,
                "INVALID_IDEMPOTENCY_KEY");
        assertThat(records(h.shipmentId())).isZero();

        JsonNode created = record(h.shipmentId(), shipmentLoadedAt(h.shipmentId()).plusSeconds(5), "-18");
        for (MockHttpSession reader : List.of(senderSession, carrierSession, receiverSession, carrierQm, receiverQm, platform)) {
            JsonNode rows = list(reader, h.shipmentId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("id").asLong()).isEqualTo(created.get("id").asLong());
        }
        expectProblem(getReq(unrelatedSession, recordsUrl(h.shipmentId())), 403, "ORG_SCOPE_DENIED");
        expect(getReq(senderSession, recordsUrl(999_999_999L)), 404);
        expect(get(recordsUrl(h.shipmentId())), 401);
    }

    // =========================================================================
    // 幂等：以规范化微秒测量时间为请求语义
    // =========================================================================

    @Test
    @DisplayName("幂等：同键同规范化请求重放原记录（不同偏移 / 纳秒尾数 / -18.5 与 -18.50 等价；到达之后仍重放）；同键相差 1 微秒 409；一行记录、一条审计")
    void idempotency_onCanonicalMicroseconds() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long batchId = activeBatch("IDEM");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime base = shipmentLoadedAt(h.shipmentId()).plusSeconds(7).withNano(123_456_000);
        String k = key("idem-temp");

        JsonNode first = expect(temperatureRequest(carrierSession, h.shipmentId(), k,
                base.withNano(123_456_789).atOffset(ZoneOffset.UTC).toString(), "-18.5", "MANUAL", null), 201).get("data");
        assertThat(first.get("measuredAt").asString()).as("stored at DATETIME(6) precision, truncated not rounded").isEqualTo(iso(base));
        assertThat(jdbcTemplate.queryForObject("SELECT measured_at FROM temperature_record WHERE id = ?", LocalDateTime.class,
                first.get("id").asLong())).isEqualTo(base);

        String shanghai = base.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX"));
        JsonNode replay = expect(temperatureRequest(carrierSession, h.shipmentId(), k, shanghai, "-18.50", "manual", null), 201).get("data");
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(replay.get("recordedAt").asString()).isEqualTo(first.get("recordedAt").asString());

        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), k, iso(base.plusNanos(1_000)), "-18.5", "MANUAL", null),
                409, "IDEMPOTENCY_CONFLICT");
        expectProblem(temperatureRequest(carrierSession, h.shipmentId(), k, iso(base), "-18.51", "MANUAL", null), 409, "IDEMPOTENCY_CONFLICT");

        arriveAt(h.shipmentId(), base.plusSeconds(1));
        JsonNode afterArrival = expect(temperatureRequest(carrierSession, h.shipmentId(), k, iso(base), "-18.5", "MANUAL", null), 201).get("data");
        assertThat(afterArrival.get("id").asLong()).isEqualTo(first.get("id").asLong());

        assertThat(records(h.shipmentId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'TEMPERATURE_RECORD' AND object_id = ?", h.shipmentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等键组织内唯一：另一个承运组织使用同一幂等键登记自己的运输任务互不影响")
    void idempotencyKeyScopedPerOrganization() throws Exception {
        Long a = activeBatch("ORGA");
        Handover h1 = prepareInTransitHandover(a);
        Organization carrier2 = createOrg("ORG_C2_" + suffix, "第二承运企业-" + suffix, "CARRIER");
        AppUser carrier2User = createUser(carrier2.getId(), "car2_op_" + suffix);
        bindUserRole(carrier2User.getId(), operatorRole.getId());
        MockHttpSession carrier2Session = login(carrier2User.getUsername());

        Long b = activeBatch("ORGB");
        Long t2 = createDraftTransfer(senderSession, b, receiverOrg.getId());
        JsonNode s2 = expect(createShipmentRequest(senderSession, key("idem-shp-c"), carrier2.getId(), senderSite.getId(), receiverSite.getId()), 201)
                .get("data");
        Long shipment2 = s2.get("id").asLong();
        createdShipmentIds.add(shipment2);
        bind(shipment2, t2);
        submit(t2);
        expect(dispatchRequest(carrier2Session, shipment2, shipmentVersion(shipment2), key("idem-shp-d")), 200);

        String shared = key("idem-temp-shared");
        JsonNode r1 = expect(temperatureRequest(carrierSession, h1.shipmentId(), shared, iso(shipmentLoadedAt(h1.shipmentId()).plusSeconds(3)),
                "-18", "MANUAL", null), 201).get("data");
        JsonNode r2 = expect(temperatureRequest(carrier2Session, shipment2, shared, iso(shipmentLoadedAt(shipment2).plusSeconds(3)),
                "-19", "MANUAL", null), 201).get("data");
        assertThat(r1.get("id").asLong()).isNotEqualTo(r2.get("id").asLong());
        assertThat(r2.get("orgId").asLong()).isEqualTo(carrier2.getId());
    }

    @Test
    @DisplayName("同一测量时间允许多条记录（不同幂等键，不做语义去重）；列表按 (measuredAt, id) 稳定排序，与登记顺序无关")
    void sameMeasurementTimeAllowed_orderedByMeasuredAtThenId() throws Exception {
        Long batchId = activeBatch("ORD");
        Handover h = prepareInTransitHandover(batchId);
        LocalDateTime loadedAt = shipmentLoadedAt(h.shipmentId());
        JsonNode late = record(h.shipmentId(), loadedAt.plusSeconds(30), "-18.00");
        JsonNode sameA = record(h.shipmentId(), loadedAt.plusSeconds(10), "-18.00");
        JsonNode sameB = expect(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(loadedAt.plusSeconds(10)),
                "-18.00", "MANUAL", null), 201).get("data");
        JsonNode mid = record(h.shipmentId(), loadedAt.plusSeconds(20), "-17.00");

        assertThat(sameB.get("id").asLong()).as("identical payload with a different key is a new record").isNotEqualTo(sameA.get("id").asLong());
        assertThat(count("SELECT count(*) FROM temperature_record WHERE shipment_id = ? AND measured_at = ?", h.shipmentId(),
                loadedAt.plusSeconds(10))).isEqualTo(2);
        JsonNode rows = list(receiverSession, h.shipmentId());
        List<Long> ids = new ArrayList<>();
        rows.forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).containsExactly(sameA.get("id").asLong(), sameB.get("id").asLong(), mid.get("id").asLong(), late.get("id").asLong());
    }

    // =========================================================================
    // 与 PB1 风险冻结互不影响；匿名公开投影不变
    // =========================================================================

    @Test
    @DisplayName("PB1 交互：批次在途期间被人工冻结，承运商仍可登记（单点 HIGH 不改变风险状态、不新增风险台账）；冻结不因登记解除，运输仍可到达")
    void frozenBatchInTransit_recordingIndependentOfRisk() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long batchId = activeBatch("PB1");
        Handover h = prepareInTransitHandover(batchId);
        MockHttpSession senderQm = newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
        freeze(senderQm, batchId, "在途抽检异常，等待调查");
        long versionAfterFreeze = batchVersion(batchId);

        JsonNode high = record(h.shipmentId(), shipmentLoadedAt(h.shipmentId()).plusSeconds(5), "-5.00");
        assertThat(high.get("evaluation").asString()).isEqualTo("HIGH");
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
        assertThat(ledgerRows(batchId)).isEqualTo(1);
        assertThat(batchVersion(batchId)).isEqualTo(versionAfterFreeze);
        assertLedgerConsistent(batchId);

        arriveAt(h.shipmentId(), shipmentLoadedAt(h.shipmentId()).plusSeconds(5));
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM alert")).isZero();
    }

    @Test
    @DisplayName("匿名公开投影：登记温度前后除 queriedAt 外完全一致，仍为 INSUFFICIENT_DATA 与新的说明文字；不含任何温度值、设备编号或规则信息；匿名不能读取温度记录")
    void publicProjectionUnchanged_andNeverExposesReadings() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null);
        Long batchId = activeBatch("PUB");
        Handover h = prepareInTransitHandover(batchId);
        JsonNode code = expect(activateCodeRequest(senderSession, batchId, key("idem-ptc")), 200).get("data");
        String publicId = code.get("publicId").asString();
        String url = "/api/public/v1/public/traces/" + publicId;
        JsonNode before = expect(get(url), 200).get("data");

        String sentinelDevice = "SENTINEL-PB2-" + suffix;
        expect(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(shipmentLoadedAt(h.shipmentId()).plusSeconds(5)),
                "-17.37", "SIMULATED", sentinelDevice), 201);
        expect(temperatureRequest(carrierSession, h.shipmentId(), key("idem-temp"), iso(shipmentLoadedAt(h.shipmentId()).plusSeconds(6)),
                "-3.21", "MANUAL", null), 201);

        MvcResult after = perform(get(url));
        String body = after.getResponse().getContentAsString();
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterData = objectMapper.readTree(body).get("data");
        ((ObjectNode) before).remove("queriedAt");
        ((ObjectNode) afterData).remove("queriedAt");
        assertThat(afterData).isEqualTo(before);
        assertThat(afterData.get("temperatureSummary").get("result").asString()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(afterData.get("temperatureSummary").get("ruleNote").asString())
                .isEqualTo("公开页面不展示冷链温度测量明细；本项目未接入实时温控采集，不构成本项目温控合规依据。");
        for (String secret : List.of(sentinelDevice, "-17.37", "-3.21", "PROBE", "HIGH", "MISSING_CONTEXT", "evaluation", "运输规则")) {
            assertThat(body).as("public body must not contain %s", secret).doesNotContain(secret);
        }
        expect(get(recordsUrl(h.shipmentId())), 401);
    }
}
