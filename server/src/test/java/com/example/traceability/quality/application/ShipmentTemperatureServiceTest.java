package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.TemperatureRuleStageMatch;
import com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper;
import com.example.traceability.quality.domain.TemperatureRecord;
import com.example.traceability.quality.dto.ShipmentTemperatureRecordRequest;
import com.example.traceability.quality.dto.TemperatureRecordResponse;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.domain.DataSource;
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Shipment 在途温度记录应用服务单元测试（PB2）")
class ShipmentTemperatureServiceTest {

    private static final Long SHIPMENT_ID = 9001L;
    private static final Long CARRIER_ORG = 30L;
    private static final Long PRODUCT_ID = 7L;
    private static final String KEY = "temp-unit-key-000000000001";

    @Mock
    private ShipmentMapper shipmentMapper;
    @Mock
    private TransferMapper transferMapper;
    @Mock
    private BatchMapper batchMapper;
    @Mock
    private TemperatureRecordMapper recordMapper;
    @Mock
    private TemperatureRuleStageMapper ruleStageMapper;
    @Mock
    private AuditApplicationService auditService;

    private ShipmentTemperatureService service;
    private TraceSecurityPrincipal carrier;
    private LocalDateTime loadedAt;

    @BeforeEach
    void setUp() {
        service = new ShipmentTemperatureService(shipmentMapper, transferMapper, batchMapper, recordMapper, ruleStageMapper,
                auditService, new ObjectMapper());
        carrier = principal(901L, CARRIER_ORG, List.of("OPERATOR"), List.of("ORG_ONLY"));
        loadedAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(2).truncatedTo(ChronoUnit.SECONDS);
    }

    private static TraceSecurityPrincipal principal(Long userId, Long orgId, List<String> roles, List<String> scopes) {
        return new TraceSecurityPrincipal(userId, "u" + userId, "u" + userId, "hash",
                orgId, "ORG-" + orgId, "组织" + orgId, "CARRIER", roles, scopes, true, true);
    }

    private Shipment shipment(ShipmentStatus status) {
        Shipment s = new Shipment();
        s.setId(SHIPMENT_ID);
        s.setSenderOrgId(10L);
        s.setReceiverOrgId(20L);
        s.setCarrierOrgId(CARRIER_ORG);
        s.setStatus(status);
        s.setVersion(4L);
        s.setIsDeleted(0);
        if (status == ShipmentStatus.IN_TRANSIT || status == ShipmentStatus.DELIVERED) {
            s.setLoadedAt(loadedAt);
        }
        return s;
    }

    private static Transfer transfer(Long id, Long batchId) {
        Transfer t = new Transfer();
        t.setId(id);
        t.setBatchId(batchId);
        t.setShipmentId(SHIPMENT_ID);
        t.setStatus(TransferStatus.PENDING);
        return t;
    }

    private static Batch batch(Long id, Long productId) {
        Batch b = new Batch();
        b.setId(id);
        b.setProductId(productId);
        return b;
    }

    private static TemperatureRuleStageMatch match(String lower, String upper) {
        TemperatureRuleStageMatch m = new TemperatureRuleStageMatch();
        m.setRuleStageId(61L);
        m.setRuleId(51L);
        m.setRuleName("冷冻大黄鱼运输规则");
        m.setRuleVersionNo(2);
        m.setStageCode("TRANSPORT");
        m.setLowerLimit(new BigDecimal(lower));
        m.setUpperLimit(new BigDecimal(upper));
        m.setAllowedDurationSeconds(1800);
        return m;
    }

    /** IN_TRANSIT 运输任务，装载两个同一产品批次，产品存在一条 [-25, -15] 的运输规则。 */
    private void stubInTransitWithRule() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(transfer(5001L, 1001L), transfer(5002L, 1002L)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1001L, PRODUCT_ID), batch(1002L, PRODUCT_ID)));
        when(ruleStageMapper.selectApplicableStages(eq(PRODUCT_ID), eq("TRANSPORT"), any())).thenReturn(List.of(match("-25.00", "-15.00")));
    }

    private OffsetDateTime inWindow() {
        return loadedAt.plusMinutes(30).atOffset(ZoneOffset.UTC);
    }

    private ShipmentTemperatureRecordRequest req(OffsetDateTime measuredAt, String temperature) {
        return new ShipmentTemperatureRecordRequest(measuredAt, new BigDecimal(temperature), "MANUAL", null);
    }

    private TemperatureRecord captureInsert() {
        ArgumentCaptor<TemperatureRecord> captor = ArgumentCaptor.forClass(TemperatureRecord.class);
        verify(recordMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static void assertBusiness(Throwable e, int status, String code) {
        assertThat(e).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) e;
        assertThat(be.getStatus().value()).isEqualTo(status);
        assertThat(be.getCode()).isEqualTo(code);
    }

    // =========================================================================
    // 登记与单点判定
    // =========================================================================

    @Test
    @DisplayName("登记：幂等预读 → 运输任务行锁 → 锁后复读 → 插入 → 审计；固定 TRANSPORT / CELSIUS、规则环节与上下限快照；只读装载清单，不写运输任务 / 交接 / 批次")
    void record_normal_persistsSnapshotInLockOrder() {
        stubInTransitWithRule();
        OffsetDateTime measuredAt = inWindow();

        TemperatureRecordResponse resp = service.record(SHIPMENT_ID,
                new ShipmentTemperatureRecordRequest(measuredAt, new BigDecimal("-18.5"), "manual", "  PROBE-01 "), KEY, carrier);

        TemperatureRecord saved = captureInsert();
        assertThat(saved.getShipmentId()).isEqualTo(SHIPMENT_ID);
        assertThat(saved.getOrgId()).isEqualTo(CARRIER_ORG);
        assertThat(saved.getActorUserId()).isEqualTo(901L);
        assertThat(saved.getStageCode()).isEqualTo("TRANSPORT");
        assertThat(saved.getUnitCode()).isEqualTo("CELSIUS");
        assertThat(saved.getTemperature()).isEqualByComparingTo("-18.50");
        assertThat(saved.getTemperature().scale()).isEqualTo(2);
        assertThat(saved.getDataSource()).isEqualTo("MANUAL");
        assertThat(saved.getDeviceNo()).isEqualTo("PROBE-01");
        assertThat(saved.getEvaluation()).isEqualTo("NORMAL");
        assertThat(saved.getRuleStageId()).isEqualTo(61L);
        assertThat(saved.getRuleLowerLimit()).isEqualByComparingTo("-25.00");
        assertThat(saved.getRuleUpperLimit()).isEqualByComparingTo("-15.00");
        assertThat(saved.getRuleAllowedDurationSeconds()).as("allowed-duration snapshot persisted with the record").isEqualTo(1800);
        assertThat(saved.getMeasuredAt()).isEqualTo(measuredAt.toLocalDateTime());
        assertThat(saved.getRecordedAt()).isNotNull();
        assertThat(saved.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(saved.getRequestHash()).hasSize(64);

        assertThat(resp.evaluation()).isEqualTo("NORMAL");
        assertThat(resp.rule()).isNotNull();
        assertThat(resp.rule().versionNo()).isEqualTo(2);
        assertThat(resp.rule().name()).isEqualTo("冷冻大黄鱼运输规则");
        assertThat(resp.rule().allowedDurationSeconds()).isEqualTo(1800);

        InOrder order = inOrder(recordMapper, shipmentMapper, ruleStageMapper, auditService);
        order.verify(recordMapper).selectByOrgIdAndIdempotencyKey(CARRIER_ORG, KEY);
        order.verify(shipmentMapper).selectByIdForUpdate(SHIPMENT_ID);
        order.verify(recordMapper).selectByOrgIdAndIdempotencyKey(CARRIER_ORG, KEY);
        order.verify(ruleStageMapper).selectApplicableStages(eq(PRODUCT_ID), eq("TRANSPORT"), eq(measuredAt.toLocalDateTime()));
        order.verify(recordMapper).insert(any(TemperatureRecord.class));
        order.verify(auditService).recordAudit(eq(901L), eq(CARRIER_ORG), eq("TEMPERATURE_RECORD"), eq("SHIPMENT"),
                eq(SHIPMENT_ID), any(), eq("SUCCESS"), anyString());

        verify(shipmentMapper, never()).updateLifecycleByIdAndVersion(any(), any(), any());
        verify(shipmentMapper, never()).bumpManifestVersion(anyLong(), anyLong());
        verify(transferMapper, never()).selectByShipmentIdForUpdate(any());
        verify(batchMapper, never()).selectByIdForUpdate(any());
        verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(any());
    }

    @Test
    @DisplayName("单点判定：高于上限 HIGH、低于下限 LOW，依然只是单点结果（同一请求路径，不做任何额外动作）")
    void record_highAndLow() {
        stubInTransitWithRule();
        assertThat(service.record(SHIPMENT_ID, req(inWindow(), "-12.50"), KEY, carrier).evaluation()).isEqualTo("HIGH");
        assertThat(service.record(SHIPMENT_ID, req(inWindow().plusMinutes(1), "-25.01"), "temp-unit-key-000000000002", carrier).evaluation())
                .isEqualTo("LOW");
    }

    @Test
    @DisplayName("MISSING_CONTEXT：产品在测量时没有已发布运输规则 → 规则环节、上下限与允许越界时长快照全部为空")
    void record_missingContext_noRule() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(transfer(5001L, 1001L)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1001L, PRODUCT_ID)));
        when(ruleStageMapper.selectApplicableStages(eq(PRODUCT_ID), eq("TRANSPORT"), any())).thenReturn(List.of());

        TemperatureRecordResponse resp = service.record(SHIPMENT_ID, req(inWindow(), "4.00"), KEY, carrier);

        TemperatureRecord saved = captureInsert();
        assertThat(saved.getEvaluation()).isEqualTo("MISSING_CONTEXT");
        assertThat(saved.getStageCode()).isEqualTo("TRANSPORT");
        assertThat(saved.getRuleStageId()).isNull();
        assertThat(saved.getRuleLowerLimit()).isNull();
        assertThat(saved.getRuleUpperLimit()).isNull();
        assertThat(saved.getRuleAllowedDurationSeconds()).isNull();
        assertThat(resp.rule()).isNull();
    }

    @Test
    @DisplayName("MISSING_CONTEXT：装载清单含多种产品（Demo MVP 限制之前的历史数据）时不猜测规则、不查询规则")
    void record_missingContext_mixedProducts() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(transfer(5001L, 1001L), transfer(5002L, 1002L)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1001L, PRODUCT_ID), batch(1002L, 8L)));

        service.record(SHIPMENT_ID, req(inWindow(), "-18.00"), KEY, carrier);

        assertThat(captureInsert().getEvaluation()).isEqualTo("MISSING_CONTEXT");
        verifyNoInteractions(ruleStageMapper);
    }

    @Test
    @DisplayName("规则区间重叠（多于一条匹配）失败关闭 500 TEMPERATURE_RULE_AMBIGUOUS，不任选其一、不插入")
    void record_ambiguousRule_failsClosed() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(transfer(5001L, 1001L)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1001L, PRODUCT_ID)));
        when(ruleStageMapper.selectApplicableStages(eq(PRODUCT_ID), eq("TRANSPORT"), any()))
                .thenReturn(List.of(match("-25.00", "-15.00"), match("-30.00", "-18.00")));

        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.00"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 500, "TEMPERATURE_RULE_AMBIGUOUS"));
        verify(recordMapper, never()).insert(any());
    }

    // =========================================================================
    // 测量时间规范化与请求哈希
    // =========================================================================

    @Test
    @DisplayName("measuredAt 只规范化一次：UTC + 截断到微秒，同一值用于持久化、规则匹配与响应（纳秒不被四舍五入）")
    void measuredAt_canonicalizedOnceToUtcMicros() {
        stubInTransitWithRule();
        LocalDateTime utc = loadedAt.plusMinutes(30);
        OffsetDateTime withNanosInShanghai = utc.withNano(123_456_999).atOffset(ZoneOffset.UTC)
                .withOffsetSameInstant(ZoneOffset.ofHours(8));

        TemperatureRecordResponse resp = service.record(SHIPMENT_ID, req(withNanosInShanghai, "-18.00"), KEY, carrier);

        LocalDateTime expected = utc.withNano(123_456_000);
        assertThat(captureInsert().getMeasuredAt()).isEqualTo(expected);
        verify(ruleStageMapper).selectApplicableStages(PRODUCT_ID, "TRANSPORT", expected);
        assertThat(resp.measuredAt()).isEqualTo(expected.atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("请求哈希覆盖规范化后的微秒时间：同一瞬间不同偏移 / 纳秒尾数、-18.5 与 -18.50 等价；相差 1 微秒或设备编号不同即不同")
    void requestHash_coversCanonicalMicroseconds() {
        OffsetDateTime base = OffsetDateTime.of(2026, 9, 24, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
        String h1 = hash(base, "-18.5", "MANUAL", null);
        assertThat(hash(base.withOffsetSameInstant(ZoneOffset.ofHours(8)), "-18.50", "manual", "  ")).isEqualTo(h1);
        assertThat(hash(base.plusNanos(999), "-18.5", "MANUAL", null)).as("sub-microsecond digits are truncated").isEqualTo(h1);
        assertThat(hash(base.plusNanos(1_000), "-18.5", "MANUAL", null)).as("1 microsecond later").isNotEqualTo(h1);
        assertThat(hash(base, "-18.51", "MANUAL", null)).isNotEqualTo(h1);
        assertThat(hash(base, "-18.5", "SIMULATED", null)).isNotEqualTo(h1);
        assertThat(hash(base, "-18.5", "MANUAL", "PROBE-01")).isNotEqualTo(h1);
        assertThat(ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID + 1,
                ShipmentTemperatureService.canonicalize(new ShipmentTemperatureRecordRequest(base, new BigDecimal("-18.5"), "MANUAL", null))))
                .as("different shipment").isNotEqualTo(h1);
    }

    private static String hash(OffsetDateTime measuredAt, String temperature, String source, String deviceNo) {
        return ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID, ShipmentTemperatureService.canonicalize(
                new ShipmentTemperatureRecordRequest(measuredAt, new BigDecimal(temperature), source, deviceNo)));
    }

    // =========================================================================
    // 幂等
    // =========================================================================

    private TemperatureRecord existing(String requestHash) {
        TemperatureRecord r = new TemperatureRecord();
        r.setId(7001L);
        r.setShipmentId(SHIPMENT_ID);
        r.setOrgId(CARRIER_ORG);
        r.setStageCode("TRANSPORT");
        r.setTemperature(new BigDecimal("-18.50"));
        r.setEvaluation("NORMAL");
        r.setRequestHash(requestHash);
        r.setMeasuredAt(inWindow().toLocalDateTime());
        return r;
    }

    @Test
    @DisplayName("幂等：同组织同键同语义在加锁前直接重放原记录（即使运输任务之后已到达），不加锁、不插入、不审计")
    void replay_beforeLock() {
        ShipmentTemperatureRecordRequest r = req(inWindow(), "-18.5");
        String h = ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID, ShipmentTemperatureService.canonicalize(r));
        TemperatureRecord stored = existing(h);
        stored.setRuleStageId(61L);
        stored.setRuleLowerLimit(new BigDecimal("-25.00"));
        stored.setRuleUpperLimit(new BigDecimal("-15.00"));
        stored.setRuleAllowedDurationSeconds(1800);
        when(recordMapper.selectByOrgIdAndIdempotencyKey(CARRIER_ORG, KEY)).thenReturn(stored);

        TemperatureRecordResponse replayed = service.record(SHIPMENT_ID, r, KEY, carrier);

        assertThat(replayed.id()).isEqualTo(7001L);
        // 重放的判定依据只来自已登记记录的快照，不重新读取规则环节
        assertThat(replayed.rule().allowedDurationSeconds()).isEqualTo(1800);
        assertThat(replayed.rule().lowerLimit()).isEqualByComparingTo("-25.00");
        verify(shipmentMapper, never()).selectByIdForUpdate(any());
        verify(recordMapper, never()).insert(any());
        verifyNoInteractions(auditService, ruleStageMapper);
    }

    @Test
    @DisplayName("幂等：同键不同语义（例如测量时间相差 1 微秒）409 IDEMPOTENCY_CONFLICT")
    void conflict_differentMicrosecond() {
        OffsetDateTime t = inWindow();
        String original = ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID, ShipmentTemperatureService.canonicalize(req(t, "-18.5")));
        when(recordMapper.selectByOrgIdAndIdempotencyKey(CARRIER_ORG, KEY)).thenReturn(existing(original));

        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t.plusNanos(1_000), "-18.5"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 409, "IDEMPOTENCY_CONFLICT"));
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("幂等：持锁后复读命中等待期间已提交的同键同语义记录 → 重放，不插入")
    void replay_afterLock() {
        ShipmentTemperatureRecordRequest r = req(inWindow(), "-18.5");
        String h = ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID, ShipmentTemperatureService.canonicalize(r));
        when(recordMapper.selectByOrgIdAndIdempotencyKey(CARRIER_ORG, KEY)).thenReturn(null, existing(h));
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));

        assertThat(service.record(SHIPMENT_ID, r, KEY, carrier).id()).isEqualTo(7001L);
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("并发同键：插入唯一键冲突后当前锁定读 → 同语义重放，不同语义 409")
    void duplicateKey_replayOrConflict() {
        stubInTransitWithRule();
        ShipmentTemperatureRecordRequest r = req(inWindow(), "-18.5");
        String h = ShipmentTemperatureService.computeRequestHash(SHIPMENT_ID, ShipmentTemperatureService.canonicalize(r));
        when(recordMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_temp_org_idempotency"));
        when(recordMapper.selectByOrgIdAndIdempotencyKeyForUpdate(CARRIER_ORG, KEY)).thenReturn(existing(h), existing("f".repeat(64)));

        assertThat(service.record(SHIPMENT_ID, r, KEY, carrier).id()).isEqualTo(7001L);
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, r, KEY, carrier))
                .satisfies(e -> assertBusiness(e, 409, "IDEMPOTENCY_CONFLICT"));
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("三方同键死锁牺牲者映射为可重试 409 TEMPERATURE_RECORD_CONCURRENT_CONFLICT，而不是 500")
    void deadlockVictim_retryableConflict() {
        stubInTransitWithRule();
        when(recordMapper.insert(any())).thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"));

        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 409, "TEMPERATURE_RECORD_CONCURRENT_CONFLICT"));
        verifyNoInteractions(auditService);
    }

    // =========================================================================
    // 权限、状态与时间窗口
    // =========================================================================

    @Test
    @DisplayName("权限：未认证 401；平台 / 系统管理员 403 ADMIN_RESTRICTED；非 OPERATOR（质量管理员）403 ROLE_NOT_ALLOWED；均在任何读取之前")
    void writeAccess() {
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, null))
                .satisfies(e -> assertBusiness(e, 401, "UNAUTHORIZED"));
        TraceSecurityPrincipal admin = principal(1L, 1L, List.of("SYSTEM_ADMIN"), List.of("PLATFORM"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, admin))
                .satisfies(e -> assertBusiness(e, 403, "ADMIN_RESTRICTED"));
        TraceSecurityPrincipal qm = principal(902L, CARRIER_ORG, List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, qm))
                .satisfies(e -> assertBusiness(e, 403, "ROLE_NOT_ALLOWED"));
        verifyNoInteractions(recordMapper, shipmentMapper);
    }

    @Test
    @DisplayName("权限：发送方 / 接收方组织的操作员 403 ORG_SCOPE_DENIED（在运输任务行锁下校验），不插入")
    void nonCarrier_forbidden() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        for (Long orgId : List.of(10L, 20L, 99L)) {
            TraceSecurityPrincipal other = principal(100L + orgId, orgId, List.of("OPERATOR"), List.of("ORG_ONLY"));
            assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, other))
                    .satisfies(e -> assertBusiness(e, 403, "ORG_SCOPE_DENIED"));
        }
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("状态：只有 IN_TRANSIT 可以登记；PLANNED / DELIVERED / CANCELLED 409 SHIPMENT_NOT_IN_TRANSIT；不存在 404")
    void onlyInTransit() {
        for (ShipmentStatus status : List.of(ShipmentStatus.PLANNED, ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED)) {
            when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(status));
            assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, carrier))
                    .satisfies(e -> assertBusiness(e, 409, "SHIPMENT_NOT_IN_TRANSIT"));
        }
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(inWindow(), "-18.5"), KEY, carrier))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("时间窗口：早于装载发运时间 422；晚于当前时间 5 分钟以上 422；恰好等于装载发运时间允许")
    void measurementWindow() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(loadedAt.minusNanos(1_000).atOffset(ZoneOffset.UTC), "-18.5"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 422, "INVALID_BUSINESS_TIME"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), "-18.5"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 422, "INVALID_BUSINESS_TIME"));
        verify(recordMapper, never()).insert(any());

        stubInTransitWithRule();
        service.record(SHIPMENT_ID, req(loadedAt.atOffset(ZoneOffset.UTC), "-18.5"), KEY, carrier);
        assertThat(captureInsert().getMeasuredAt()).isEqualTo(loadedAt);
    }

    // =========================================================================
    // 请求校验
    // =========================================================================

    @Test
    @DisplayName("请求校验：温度超过两位小数 / 超出 [-80, 60] / 未知来源 / 非法设备编号 / 未声明字段 400；IMPORT 与 DEVICE 422；幂等键缺失或 SYS: 前缀 400")
    void validation() {
        OffsetDateTime t = inWindow();
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t, "-18.555"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t, "-80.01"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t, "60.01"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID,
                new ShipmentTemperatureRecordRequest(t, new BigDecimal("-18"), "SENSOR", null), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        for (DataSource notOpen : List.of(DataSource.IMPORT, DataSource.DEVICE)) {
            assertThatThrownBy(() -> service.record(SHIPMENT_ID,
                    new ShipmentTemperatureRecordRequest(t, new BigDecimal("-18"), notOpen.name(), null), KEY, carrier))
                    .satisfies(e -> assertBusiness(e, 422, "TEMPERATURE_SOURCE_NOT_SUPPORTED"));
        }
        assertThatThrownBy(() -> service.record(SHIPMENT_ID,
                new ShipmentTemperatureRecordRequest(t, new BigDecimal("-18"), "MANUAL", "探头 1号"), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID,
                new ShipmentTemperatureRecordRequest(t, new BigDecimal("-18"), "MANUAL", null, Map.of("evaluation", "NORMAL")), KEY, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_REQUEST"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t, "-18"), null, carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_IDEMPOTENCY_KEY"));
        assertThatThrownBy(() -> service.record(SHIPMENT_ID, req(t, "-18"), "SYS:TEMP:0000000000001", carrier))
                .satisfies(e -> assertBusiness(e, 400, "INVALID_IDEMPOTENCY_KEY"));
        verifyNoInteractions(recordMapper, shipmentMapper);
    }

    @Test
    @DisplayName("温度边界值 -80.00 与 60.00 可以登记；空白设备编号视为未填写")
    void validation_edges() {
        assertThat(ShipmentTemperatureService.canonicalize(req(inWindow(), "-80")).temperature()).isEqualByComparingTo("-80.00");
        assertThat(ShipmentTemperatureService.canonicalize(req(inWindow(), "60.00")).temperature()).isEqualByComparingTo("60.00");
        assertThat(ShipmentTemperatureService.canonicalize(
                new ShipmentTemperatureRecordRequest(inWindow(), new BigDecimal("-18"), "SIMULATED", "   ")).deviceNo()).isNull();
    }

    // =========================================================================
    // 查询
    // =========================================================================

    @Test
    @DisplayName("查询：参与方范围读取并按 mapper 返回顺序（测量时间、主键）输出；平台只读使用全量读取；第三方 403；不存在 404")
    void list_scopes() {
        TemperatureRecord a = existing("a".repeat(64));
        TemperatureRecord b = existing("b".repeat(64));
        b.setId(7002L);
        when(shipmentMapper.selectByIdAndPartyScope(SHIPMENT_ID, 20L)).thenReturn(shipment(ShipmentStatus.DELIVERED));
        when(recordMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(a, b));
        TraceSecurityPrincipal receiverQm = principal(202L, 20L, List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
        assertThat(service.listRecords(SHIPMENT_ID, receiverQm)).extracting(TemperatureRecordResponse::id).containsExactly(7001L, 7002L);

        TraceSecurityPrincipal platform = principal(1L, 1L, List.of("SYSTEM_ADMIN"), List.of("PLATFORM"));
        when(shipmentMapper.selectById(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.DELIVERED));
        assertThat(service.listRecords(SHIPMENT_ID, platform)).hasSize(2);

        TraceSecurityPrincipal stranger = principal(990L, 99L, List.of("OPERATOR"), List.of("ORG_ONLY"));
        when(shipmentMapper.existsByIdIgnoreTenant(SHIPMENT_ID)).thenReturn(1);
        assertThatThrownBy(() -> service.listRecords(SHIPMENT_ID, stranger))
                .satisfies(e -> assertBusiness(e, 403, "ORG_SCOPE_DENIED"));
        when(shipmentMapper.existsByIdIgnoreTenant(404L)).thenReturn(0);
        assertThatThrownBy(() -> service.listRecords(404L, stranger)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listRecords(SHIPMENT_ID, null)).satisfies(e -> assertBusiness(e, 401, "UNAUTHORIZED"));
    }
}
