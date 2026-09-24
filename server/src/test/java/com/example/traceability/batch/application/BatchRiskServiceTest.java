package com.example.traceability.batch.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.application.BatchRiskService.ManualAction;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchRiskTransition;
import com.example.traceability.batch.dto.BatchRiskTransitionRequest;
import com.example.traceability.batch.dto.BatchRiskTransitionResponse;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchRiskStateMapper;
import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批次风险状态核心应用服务单元测试（Phase B PB1）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("批次风险状态核心：权限顺序、状态矩阵、台账字段、幂等、并发冲突与历史读取范围")
class BatchRiskServiceTest {

    private static final long PROCESSOR_ORG = 30L;
    private static final long RETAILER_ORG = 40L;
    private static final long OTHER_ORG = 50L;
    private static final long BATCH_ID = 3000L;
    private static final long QM_USER = 801L;
    private static final String KEY = "risk-key-00000000000001";

    @Mock private BatchMapper batchMapper;
    @Mock private BatchRiskStateMapper riskStateMapper;
    @Mock private BatchRiskTransitionMapper transitionMapper;
    @Mock private AuditApplicationService auditService;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private BatchRiskService service;
    private Batch batch;
    private TraceSecurityPrincipal qm;

    @BeforeEach
    void setUp() {
        service = new BatchRiskService(batchMapper, riskStateMapper, transitionMapper, auditService, objectMapper);
        qm = principal(QM_USER, PROCESSOR_ORG, List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
        batch = batch("ACTIVE", "NORMAL");
        when(batchMapper.selectByIdIgnoreTenantForUpdate(BATCH_ID)).thenAnswer(inv -> batch);
        when(batchMapper.selectByIdIgnoreTenant(BATCH_ID)).thenAnswer(inv -> batch);
        when(transitionMapper.insert(any(BatchRiskTransition.class))).thenAnswer(inv -> {
            inv.<BatchRiskTransition>getArgument(0).setId(7001L);
            return 1;
        });
        when(riskStateMapper.transitionRiskStatus(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
    }

    private static Batch batch(String flow, String risk) {
        Batch b = new Batch();
        b.setId(BATCH_ID);
        b.setOrgId(PROCESSOR_ORG);
        b.setTraceBatchNo("TB-B2");
        b.setFlowStatus(flow);
        b.setRiskStatus(risk);
        b.setVersion(4L);
        return b;
    }

    private static TraceSecurityPrincipal principal(long userId, long orgId, List<String> roles, List<String> scopes) {
        return new TraceSecurityPrincipal(userId, "user_" + userId, "用户", "hash",
                orgId, "ORG-" + orgId, "组织" + orgId, "PROCESSOR", roles, scopes, true, true);
    }

    private static BatchRiskTransitionRequest req(String reason) {
        return new BatchRiskTransitionRequest(reason);
    }

    private static void assertBusiness(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) ex;
        assertThat(be.getStatus()).isEqualTo(status);
        assertThat(be.getCode()).isEqualTo(code);
    }

    private void assertNoWrites() {
        verify(transitionMapper, never()).insert(any());
        verify(riskStateMapper, never()).transitionRiskStatus(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any());
        verifyNoInteractions(auditService);
    }

    private static BatchRiskTransition stored(ManualAction action, long batchId, String reason, long orgId) {
        BatchRiskTransition t = new BatchRiskTransition();
        t.setId(6001L);
        t.setBatchId(batchId);
        t.setOrgId(orgId);
        t.setFlowStatus("ACTIVE");
        t.setFromStatus(action == ManualAction.FREEZE ? "NORMAL" : "FROZEN");
        t.setToStatus(action == ManualAction.FREEZE ? "FROZEN" : "NORMAL");
        t.setSourceType("MANUAL");
        t.setActorUserId(QM_USER);
        t.setReason(reason);
        t.setIdempotencyKey(KEY);
        t.setRequestHash(BatchRiskService.computeRequestHash(action, batchId, reason));
        t.setOccurredAt(LocalDateTime.of(2026, 9, 23, 1, 2, 3, 456789000));
        return t;
    }

    @Nested
    @DisplayName("权限与请求校验（先于任何锁与写入）")
    class Access {

        @Test
        @DisplayName("未认证 401；平台 / 系统管理员 403 ADMIN_RESTRICTED；非 QUALITY_MANAGER（含 OPERATOR）403 ACCESS_DENIED")
        void roleOrder() {
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, null))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
            TraceSecurityPrincipal admin = principal(1L, 1L, List.of("SYSTEM_ADMIN", "QUALITY_MANAGER"), List.of("PLATFORM"));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, admin))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED"));
            TraceSecurityPrincipal platformQm = principal(2L, PROCESSOR_ORG, List.of("QUALITY_MANAGER"), List.of("PLATFORM"));
            assertThatThrownBy(() -> service.release(BATCH_ID, req("抽检"), KEY, platformQm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED"));
            TraceSecurityPrincipal operator = principal(3L, PROCESSOR_ORG, List.of("OPERATOR"), List.of("ORG_ONLY"));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, operator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ACCESS_DENIED"));
            verifyNoInteractions(batchMapper, transitionMapper);
            assertNoWrites();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "short-key", "SYS:ALERT:1:BATCH:3000", "sys:manual-000000000001", "含中文的幂等键0000000000"})
        @DisplayName("Idempotency-Key 缺失 / 过短 / 保留前缀 SYS:（大小写不敏感）/ 非法字符 → 400")
        void invalidKeys(String key) {
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), key.isEmpty() ? null : key, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            verifyNoInteractions(batchMapper, transitionMapper);
            assertNoWrites();
        }

        @Test
        @DisplayName("原因为空 / 全空白 / 超过 500 字符 / 携带未声明字段 → 400；恰好 500 字符（去除首尾空白后）允许")
        void reasonValidation() {
            for (BatchRiskTransitionRequest bad : List.of(
                    req(null), req("   \t "), req("x".repeat(501)),
                    new BatchRiskTransitionRequest("抽检", Map.of("occurredAt", "2020-01-01T00:00:00Z")),
                    new BatchRiskTransitionRequest("抽检", Map.of("targetStatus", "RECALLED")))) {
                assertThatThrownBy(() -> service.freeze(BATCH_ID, bad, KEY, qm))
                        .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            }
            assertThatThrownBy(() -> service.freeze(BATCH_ID, null, KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            assertNoWrites();

            BatchRiskTransitionResponse ok = service.freeze(BATCH_ID, req("  " + "y".repeat(500) + "  "), KEY, qm);
            assertThat(ok.reason()).hasSize(500);
        }

        @Test
        @DisplayName("批次不存在 404；其他组织（含已转出后的历史责任组织）的质量管理员 403 ORG_SCOPE_DENIED，且都不写入")
        void scope() {
            when(batchMapper.selectByIdIgnoreTenantForUpdate(BATCH_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, qm)).isInstanceOf(ResourceNotFoundException.class);
            when(batchMapper.selectByIdIgnoreTenantForUpdate(BATCH_ID)).thenAnswer(inv -> batch);
            TraceSecurityPrincipal otherQm = principal(9L, OTHER_ORG, List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, otherQm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
            batch.setOrgId(RETAILER_ORG);
            assertThatThrownBy(() -> service.release(BATCH_ID, req("复检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
            assertNoWrites();
        }
    }

    @Nested
    @DisplayName("状态矩阵")
    class Matrix {

        @ParameterizedTest(name = "{0}/{1} {2} → {3}")
        @CsvSource({
                "DRAFT,  NORMAL,   FREEZE,  409",
                "DRAFT,  NORMAL,   RELEASE, 409",
                "ACTIVE, NORMAL,   FREEZE,  FROZEN",
                "ACTIVE, NORMAL,   RELEASE, 409",
                "ACTIVE, FROZEN,   FREEZE,  409",
                "ACTIVE, FROZEN,   RELEASE, NORMAL",
                "ACTIVE, RECALLED, FREEZE,  409",
                "ACTIVE, RECALLED, RELEASE, 409",
                "CLOSED, NORMAL,   FREEZE,  FROZEN",
                "CLOSED, NORMAL,   RELEASE, 409",
                "CLOSED, FROZEN,   FREEZE,  409",
                "CLOSED, FROZEN,   RELEASE, NORMAL",
                "CLOSED, RECALLED, FREEZE,  409",
                "CLOSED, RECALLED, RELEASE, 409"
        })
        void matrix(String flow, String risk, ManualAction action, String expected) {
            batch = batch(flow, risk);
            if ("409".equals(expected)) {
                assertThatThrownBy(() -> run(action))
                        .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION"));
                assertNoWrites();
                return;
            }
            BatchRiskTransitionResponse r = run(action);
            assertThat(r.fromStatus()).isEqualTo(risk);
            assertThat(r.toStatus()).isEqualTo(expected);
            assertThat(r.flowStatus()).as("flow status snapshot, never changed").isEqualTo(flow);
            verify(riskStateMapper).transitionRiskStatus(eq(BATCH_ID), eq(PROCESSOR_ORG), eq(risk), eq(expected), eq(flow), any(), eq(QM_USER));
        }

        private BatchRiskTransitionResponse run(ManualAction action) {
            return action == ManualAction.FREEZE
                    ? service.freeze(BATCH_ID, req("抽检"), KEY, qm)
                    : service.release(BATCH_ID, req("复检合格"), KEY, qm);
        }
    }

    @Nested
    @DisplayName("成功转换：台账字段、批次条件更新与审计")
    class Success {

        @Test
        @DisplayName("冻结：台账行（责任组织 / 流转快照 / MANUAL / 操作人 / 去空白原因 / 服务端 UTC 时间 / 键与哈希）→ 条件更新 → RISK_FREEZE 审计")
        void freezeWritesLedgerThenBatchThenAudit() throws Exception {
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
            BatchRiskTransitionResponse r = service.freeze(BATCH_ID, req("  来料抽检异常，等待复检  "), " " + KEY + " ", qm);

            ArgumentCaptor<BatchRiskTransition> row = ArgumentCaptor.forClass(BatchRiskTransition.class);
            verify(transitionMapper).insert(row.capture());
            BatchRiskTransition t = row.getValue();
            assertThat(t.getBatchId()).isEqualTo(BATCH_ID);
            assertThat(t.getOrgId()).isEqualTo(PROCESSOR_ORG);
            assertThat(t.getFlowStatus()).isEqualTo("ACTIVE");
            assertThat(t.getFromStatus()).isEqualTo("NORMAL");
            assertThat(t.getToStatus()).isEqualTo("FROZEN");
            assertThat(t.getSourceType()).isEqualTo("MANUAL");
            assertThat(t.getActorUserId()).isEqualTo(QM_USER);
            assertThat(t.getReason()).isEqualTo("来料抽检异常，等待复检");
            assertThat(t.getIdempotencyKey()).isEqualTo(KEY);
            assertThat(t.getRequestHash()).isEqualTo(BatchRiskService.computeRequestHash(ManualAction.FREEZE, BATCH_ID, "来料抽检异常，等待复检"));
            assertThat(t.getOccurredAt()).isAfter(before).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
            assertThat(t.getOccurredAt().getNano() % 1000).as("microsecond precision").isZero();

            verify(riskStateMapper).transitionRiskStatus(BATCH_ID, PROCESSOR_ORG, "NORMAL", "FROZEN", "ACTIVE", t.getOccurredAt(), QM_USER);
            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(auditService).recordAudit(eq(QM_USER), eq(PROCESSOR_ORG), eq("RISK_FREEZE"), eq("BATCH"), eq(BATCH_ID),
                    eq(t.getOccurredAt()), eq("SUCCESS"), summary.capture());
            JsonNode json = objectMapper.readTree(summary.getValue());
            assertThat(json.get("transitionId").asLong()).isEqualTo(7001L);
            assertThat(json.get("fromStatus").asString()).isEqualTo("NORMAL");
            assertThat(json.get("toStatus").asString()).isEqualTo("FROZEN");
            assertThat(json.get("flowStatus").asString()).isEqualTo("ACTIVE");
            assertThat(json.get("sourceType").asString()).isEqualTo("MANUAL");
            assertThat(json.has("reason")).as("reason lives in the ledger, not the audit summary").isFalse();

            assertThat(r.id()).isEqualTo(7001L);
            assertThat(r.occurredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("解除冻结写 RISK_RELEASE 审计")
        void releaseAudit() {
            batch = batch("CLOSED", "FROZEN");
            service.release(BATCH_ID, req("复检合格"), KEY, qm);
            verify(auditService).recordAudit(eq(QM_USER), eq(PROCESSOR_ORG), eq("RISK_RELEASE"), eq("BATCH"), eq(BATCH_ID),
                    any(), eq("SUCCESS"), anyString());
        }

        @Test
        @DisplayName("批次条件更新影响 0 行（并发修改）→ 409 BATCH_CONCURRENT_CONFLICT，不写审计（台账行随事务回滚）")
        void conditionalUpdateMiss() {
            when(riskStateMapper.transitionRiskStatus(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(0);
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT"));
            verifyNoInteractions(auditService);
        }
    }

    @Nested
    @DisplayName("幂等")
    class Idempotency {

        @Test
        @DisplayName("预读命中同语义 → 原转换重放：不加锁、不校验当前状态 / 责任组织、不写入、不审计")
        void preReadReplay() {
            batch = batch("ACTIVE", "NORMAL");
            batch.setOrgId(RETAILER_ORG);
            when(transitionMapper.selectByOrgIdAndIdempotencyKey(PROCESSOR_ORG, KEY))
                    .thenReturn(stored(ManualAction.FREEZE, BATCH_ID, "抽检", PROCESSOR_ORG));
            BatchRiskTransitionResponse r = service.freeze(BATCH_ID, req(" 抽检 "), KEY, qm);
            assertThat(r.id()).isEqualTo(6001L);
            assertThat(r.toStatus()).isEqualTo("FROZEN");
            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(anyLong());
            assertNoWrites();
        }

        @Test
        @DisplayName("同键不同语义（原因不同 / 冻结键用于解除 / 同键不同批次）→ 409 IDEMPOTENCY_CONFLICT")
        void conflicts() {
            when(transitionMapper.selectByOrgIdAndIdempotencyKey(PROCESSOR_ORG, KEY))
                    .thenReturn(stored(ManualAction.FREEZE, BATCH_ID, "抽检", PROCESSOR_ORG));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("另一个原因"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));
            assertThatThrownBy(() -> service.release(BATCH_ID, req("抽检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));
            assertThatThrownBy(() -> service.freeze(3001L, req("抽检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));
            assertNoWrites();
        }

        @Test
        @DisplayName("持锁后复读命中（同键并发的后到者）→ 重放，不写入")
        void lockedReRead() {
            when(transitionMapper.selectByOrgIdAndIdempotencyKey(PROCESSOR_ORG, KEY))
                    .thenReturn(null)
                    .thenReturn(stored(ManualAction.FREEZE, BATCH_ID, "抽检", PROCESSOR_ORG));
            BatchRiskTransitionResponse r = service.freeze(BATCH_ID, req("抽检"), KEY, qm);
            assertThat(r.id()).isEqualTo(6001L);
            verify(batchMapper).selectByIdIgnoreTenantForUpdate(BATCH_ID);
            assertNoWrites();
        }

        @Test
        @DisplayName("插入唯一键冲突（另一批次上的并发同键请求已提交）→ 锁定复读：语义不同 409，语义相同重放且不更新批次、不审计")
        void duplicateKeyOnInsert() {
            when(transitionMapper.insert(any(BatchRiskTransition.class))).thenThrow(new DuplicateKeyException("uk_brt_org_idempotency"));
            when(transitionMapper.selectByOrgIdAndIdempotencyKeyForUpdate(PROCESSOR_ORG, KEY))
                    .thenReturn(stored(ManualAction.FREEZE, 3001L, "抽检", PROCESSOR_ORG));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));

            when(transitionMapper.selectByOrgIdAndIdempotencyKeyForUpdate(PROCESSOR_ORG, KEY))
                    .thenReturn(stored(ManualAction.FREEZE, BATCH_ID, "抽检", PROCESSOR_ORG));
            BatchRiskTransitionResponse r = service.freeze(BATCH_ID, req("抽检"), KEY, qm);
            assertThat(r.id()).isEqualTo(6001L);
            verify(riskStateMapper, never()).transitionRiskStatus(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("台账插入被选为 InnoDB 死锁牺牲者（三方同键且先插入者回滚）→ 可重试 409 BATCH_CONCURRENT_CONFLICT，不更新批次、不审计")
        void deadlockVictimOnInsert() {
            when(transitionMapper.insert(any(BatchRiskTransition.class)))
                    .thenThrow(new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null));
            assertThatThrownBy(() -> service.freeze(BATCH_ID, req("抽检"), KEY, qm))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT"));
            verify(riskStateMapper, never()).transitionRiskStatus(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("请求哈希：区分动作、批次与原因；原因去除首尾空白后参与哈希")
        void hash() {
            String h = BatchRiskService.computeRequestHash(ManualAction.FREEZE, BATCH_ID, "抽检");
            assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(BatchRiskService.computeRequestHash(ManualAction.RELEASE, BATCH_ID, "抽检")).isNotEqualTo(h);
            assertThat(BatchRiskService.computeRequestHash(ManualAction.FREEZE, 3001L, "抽检")).isNotEqualTo(h);
            assertThat(BatchRiskService.computeRequestHash(ManualAction.FREEZE, BATCH_ID, "抽检2")).isNotEqualTo(h);
            service.freeze(BATCH_ID, req("\t抽检  "), KEY, qm);
            ArgumentCaptor<BatchRiskTransition> row = ArgumentCaptor.forClass(BatchRiskTransition.class);
            verify(transitionMapper).insert(row.capture());
            assertThat(row.getValue().getRequestHash()).isEqualTo(h);
        }
    }

    @Nested
    @DisplayName("历史读取范围")
    class History {

        @BeforeEach
        void rows() {
            BatchRiskTransition a = stored(ManualAction.FREEZE, BATCH_ID, "加工企业冻结", PROCESSOR_ORG);
            BatchRiskTransition b = stored(ManualAction.RELEASE, BATCH_ID, "加工企业解除", PROCESSOR_ORG);
            b.setId(6002L);
            BatchRiskTransition c = stored(ManualAction.FREEZE, BATCH_ID, "零售企业冻结", RETAILER_ORG);
            c.setId(6003L);
            when(transitionMapper.selectByBatchId(BATCH_ID)).thenReturn(List.of(a, b, c));
            when(transitionMapper.selectByBatchIdAndOrgId(BATCH_ID, PROCESSOR_ORG)).thenReturn(List.of(a, b));
            when(transitionMapper.countByBatchIdAndOrgId(BATCH_ID, PROCESSOR_ORG)).thenReturn(2);
            when(transitionMapper.countByBatchIdAndOrgId(BATCH_ID, OTHER_ORG)).thenReturn(0);
            batch.setOrgId(RETAILER_ORG);
        }

        @Test
        @DisplayName("当前责任组织任意角色：完整历史；平台只读：完整历史")
        void currentAndPlatform() {
            TraceSecurityPrincipal retailerOperator = principal(5L, RETAILER_ORG, List.of("OPERATOR"), List.of("ORG_ONLY"));
            assertThat(service.listTransitions(BATCH_ID, retailerOperator)).extracting(BatchRiskTransitionResponse::id)
                    .containsExactly(6001L, 6002L, 6003L);
            TraceSecurityPrincipal platform = principal(1L, 1L, List.of("SYSTEM_ADMIN"), List.of("PLATFORM"));
            assertThat(service.listTransitions(BATCH_ID, platform)).hasSize(3);
        }

        @Test
        @DisplayName("历史参与组织：只含本组织登记的转换，看不到之后组织的转换 / 原因 / 操作人")
        void historicalOwnRowsOnly() {
            List<BatchRiskTransitionResponse> own = service.listTransitions(BATCH_ID, qm);
            assertThat(own).extracting(BatchRiskTransitionResponse::orgId).containsOnly(PROCESSOR_ORG);
            assertThat(own).extracting(BatchRiskTransitionResponse::reason).doesNotContain("零售企业冻结");
            verify(transitionMapper, never()).selectByBatchId(anyLong());
        }

        @Test
        @DisplayName("无关组织 403 ORG_SCOPE_DENIED；匿名 401；批次不存在 404")
        void denied() {
            TraceSecurityPrincipal other = principal(9L, OTHER_ORG, List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
            assertThatThrownBy(() -> service.listTransitions(BATCH_ID, other))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
            assertThatThrownBy(() -> service.listTransitions(BATCH_ID, null))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
            when(batchMapper.selectByIdIgnoreTenant(BATCH_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.listTransitions(BATCH_ID, qm)).isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
