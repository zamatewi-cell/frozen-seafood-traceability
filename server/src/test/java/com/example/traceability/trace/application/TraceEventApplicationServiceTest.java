package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.domain.DataSource;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.domain.TraceEventStatus;
import com.example.traceability.trace.domain.TraceEventType;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TraceEventResponse;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("追溯事件应用服务业务逻辑与更正工作流单元测试")
class TraceEventApplicationServiceTest {

    @Mock
    private TraceEventMapper traceEventMapper;

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private SiteMapper siteMapper;

    private ObjectMapper objectMapper;
    private TraceEventApplicationService eventService;

    private TraceSecurityPrincipal operatorOrg1;
    private TraceSecurityPrincipal operatorOrg2;
    private TraceSecurityPrincipal platformAdmin;
    private TraceSecurityPrincipal auditorOrg1;

    private static final String VALID_KEY = "1234567890abcdef123456";
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventService = new TraceEventApplicationService(traceEventMapper, batchMapper, siteMapper, objectMapper);

        operatorOrg1 = new TraceSecurityPrincipal(
                100L, "operator1", "企业操作员1", "{noop}pwd",
                10L, "ORG001", "海产捕捞企业", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
        operatorOrg2 = new TraceSecurityPrincipal(
                200L, "operator2", "企业操作员2", "{noop}pwd",
                20L, "ORG002", "海产加工企业", "PROCESSOR",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
        platformAdmin = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                0L, "PLATFORM", "溯源监管中心", "REGULATOR",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );
        auditorOrg1 = new TraceSecurityPrincipal(
                101L, "auditor1", "审计员1", "{noop}pwd",
                10L, "ORG001", "海产捕捞企业", "SOURCE",
                List.of("AUDITOR"), List.of("ORG_ONLY"), true, true
        );
    }

    private Batch createBatch(Long id, Long orgId, String status) {
        Batch b = new Batch();
        b.setId(id);
        b.setOrgId(orgId);
        b.setBatchNo("BATCH-" + id);
        b.setStatus(status);
        b.setIsDeleted(0);
        return b;
    }

    private Site createSite(Long id, Long orgId, String status) {
        Site s = new Site();
        s.setId(id);
        s.setOrgId(orgId);
        s.setStatus(status);
        s.setIsDeleted(0);
        return s;
    }

    @Nested
    @DisplayName("普通追溯事件创建测试 (POST /api/v1/batches/{batchId}/events)")
    class CreateEventTests {

        @Test
        @DisplayName("合法创建普通事件成功：独立保存双时间、白名单输出、状态为 SUBMITTED")
        void createEvent_Success() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS",
                    OCCURRED_AT,
                    null,
                    "MANUAL",
                    "车间分级剖杀去头处理",
                    Map.of("lineId", "L1", "temperature", -18.5)
            );

            TraceEventResponse resp = eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1);

            assertThat(resp).isNotNull();
            assertThat(resp.batchId()).isEqualTo(1000L);
            assertThat(resp.orgId()).isEqualTo(10L);
            assertThat(resp.eventType()).isEqualTo("PROCESS");
            assertThat(resp.occurredAt()).isEqualTo(OCCURRED_AT);
            assertThat(resp.recordedAt()).isNotNull();
            assertThat(resp.operatorId()).isEqualTo(100L);
            assertThat(resp.dataSource()).isEqualTo("MANUAL");
            assertThat(resp.status()).isEqualTo("SUBMITTED");
            assertThat(resp.summary()).isEqualTo("车间分级剖杀去头处理");
            assertThat(resp.detailsJson()).containsEntry("lineId", "L1");
            assertThat(resp.correctsEventId()).isNull();
            assertThat(resp.correctionReason()).isNull();

            ArgumentCaptor<TraceEvent> captor = ArgumentCaptor.forClass(TraceEvent.class);
            verify(traceEventMapper).insert(captor.capture());
            TraceEvent saved = captor.getValue();
            assertThat(saved.getBatchId()).isEqualTo(1000L);
            assertThat(saved.getIdempotencyKey()).isEqualTo(VALID_KEY);
            assertThat(saved.getCorrectsEventId()).isNull();
            assertThat(saved.getCorrectionReason()).isNull();
            assertThat(saved.getStatus()).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("SIMULATED 数据源正常往返")
        void createEvent_SimulatedDataSource() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "FREEZE",
                    OCCURRED_AT,
                    null,
                    "SIMULATED",
                    "教学仿真速冻事件",
                    null
            );

            TraceEventResponse resp = eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1);
            assertThat(resp.dataSource()).isEqualTo("SIMULATED");
        }

        @Test
        @DisplayName("批次状态矩阵：DRAFT 批次禁止创建普通事件 (422 BATCH_FLOW_BLOCKED)")
        void createEvent_DraftBatch_Blocked() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.DRAFT.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "SOURCE", OCCURRED_AT, null, "MANUAL", "捕捞出海", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(be.getCode()).isEqualTo("BATCH_FLOW_BLOCKED");
                    });
        }

        @Test
        @DisplayName("批次状态矩阵：FROZEN/RECALLED/CLOSED 批次均禁止创建普通事件 (422 BATCH_FLOW_BLOCKED)")
        void createEvent_FrozenRecalledClosedBatch_Blocked() {
            for (BatchStatus status : List.of(BatchStatus.FROZEN, BatchStatus.RECALLED, BatchStatus.CLOSED)) {
                Batch batch = createBatch(1000L, 10L, status.name());
                when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

                CreateTraceEventRequest req = new CreateTraceEventRequest(
                        "SOURCE", OCCURRED_AT, null, "MANUAL", "操作测试", null
                );

                assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> {
                            BusinessException be = (BusinessException) e;
                            assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(be.getCode()).isEqualTo("BATCH_FLOW_BLOCKED");
                        });
            }
        }

        @Test
        @DisplayName("跨组织创建追溯事件被拒绝 (403 ORG_SCOPE_DENIED)")
        void createEvent_CrossOrg_Denied() {
            Batch batch = createBatch(1000L, 20L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PACK", OCCURRED_AT, null, "MANUAL", "包装装箱", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                    });
        }

        @Test
        @DisplayName("非 OPERATOR 角色或平台角色写操作被拦截 (403 ACCESS_DENIED)")
        void createEvent_NonOperator_Denied() {
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PACK", OCCURRED_AT, null, "MANUAL", "包装装箱", null
            );

            // 1. 平台管理员拦截
            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, platformAdmin))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });

            // 2. 企业内部非 OPERATOR (如 AUDITOR) 拦截
            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, auditorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });

            // 3. 平台 scope 用户即便具有 OPERATOR 角色亦严禁写企业事件
            TraceSecurityPrincipal platformOperator = new TraceSecurityPrincipal(
                    2L, "platform_op", "平台操作员", "{noop}pwd",
                    0L, "PLATFORM", "溯源监管中心", "REGULATOR",
                    List.of("OPERATOR"), List.of("PLATFORM"), true, true
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, platformOperator))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });
        }

        @Test
        @DisplayName("幂等键校验：相同组织相同键相同语义重放原结果，不重复插入")
        void createEvent_Idempotency_SamePayload_ReplaysOriginal() {
            TraceEvent existing = new TraceEvent();
            existing.setId(999L);
            existing.setBatchId(1000L);
            existing.setOrgId(10L);
            existing.setOperatorId(100L);
            existing.setEventType("SOURCE");
            existing.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            existing.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            existing.setSiteId(null);
            existing.setDataSource("MANUAL");
            existing.setStatus("SUBMITTED");
            existing.setSummary("东海初次捕捞");
            existing.setDetailsJson(null);
            existing.setCorrectsEventId(null);
            existing.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_KEY)).thenReturn(existing);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "SOURCE", OCCURRED_AT, null, "MANUAL", "东海初次捕捞", null
            );

            TraceEventResponse resp = eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1);

            assertThat(resp.id()).isEqualTo(999L);
            verify(traceEventMapper, never()).insert(any(TraceEvent.class));
        }

        @Test
        @DisplayName("幂等键校验：相同组织相同键不同语义返回 409 IDEMPOTENCY_CONFLICT")
        void createEvent_Idempotency_ConflictPayload() {
            TraceEvent existing = new TraceEvent();
            existing.setId(999L);
            existing.setBatchId(1000L);
            existing.setOrgId(10L);
            existing.setOperatorId(100L);
            existing.setEventType("SOURCE");
            existing.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            existing.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            existing.setSiteId(null);
            existing.setDataSource("MANUAL");
            existing.setStatus("SUBMITTED");
            existing.setSummary("东海初次捕捞");
            existing.setDetailsJson(null);
            existing.setCorrectsEventId(null);
            existing.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_KEY)).thenReturn(existing);

            // 不同摘要
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "SOURCE", OCCURRED_AT, null, "MANUAL", "不同摘要载荷", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    });
        }

        @Test
        @DisplayName("MySQL REPEATABLE READ 并发 DuplicateKeyException 当前读恢复成功")
        void createEvent_DuplicateKey_CurrentReadRecovery() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            // 首次快照读为 null
            when(traceEventMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_KEY)).thenReturn(null);

            // 插入抛出 DuplicateKeyException
            when(traceEventMapper.insert(any(TraceEvent.class))).thenThrow(new DuplicateKeyException("uk_trace_event_org_idempotency"));

            // 当前读 selectByOrgIdAndIdempotencyKeyForUpdate 查出并发插入行
            TraceEvent dup = new TraceEvent();
            dup.setId(888L);
            dup.setBatchId(1000L);
            dup.setOrgId(10L);
            dup.setOperatorId(100L);
            dup.setEventType("PROCESS");
            dup.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            dup.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            dup.setSiteId(null);
            dup.setDataSource("MANUAL");
            dup.setStatus("SUBMITTED");
            dup.setSummary("并发加工");
            dup.setDetailsJson(null);
            dup.setCorrectsEventId(null);
            dup.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_KEY)).thenReturn(dup);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "并发加工", null
            );

            TraceEventResponse resp = eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1);
            assertThat(resp.id()).isEqualTo(888L);
        }
    }

    @Nested
    @DisplayName("追加式追溯事件更正测试 (POST /api/v1/batches/{batchId}/events/{eventId}/corrections)")
    class CorrectEventTests {

        @Test
        @DisplayName("合法更正成功：新记录插入、指向原事件、旧记录状态原子流转为 CORRECTED")
        void correctEvent_Success() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.SUBMITTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);
            when(traceEventMapper.updateStatusToCorrected(eq(500L), eq(1000L), eq(10L), anyLong(), any())).thenReturn(1);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS",
                    OCCURRED_AT,
                    null,
                    "MANUAL",
                    "修正后的加工工艺摘要",
                    null,
                    "修正温度记录误差与工位号"
            );

            TraceEventResponse resp = eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1);

            assertThat(resp).isNotNull();
            assertThat(resp.correctsEventId()).isEqualTo(500L);
            assertThat(resp.correctionReason()).isEqualTo("修正温度记录误差与工位号");
            assertThat(resp.status()).isEqualTo("SUBMITTED");

            verify(traceEventMapper).insert(any(TraceEvent.class));
            verify(traceEventMapper).updateStatusToCorrected(eq(500L), eq(1000L), eq(10L), eq(100L), any());
        }

        @Test
        @DisplayName("批次状态矩阵：CLOSED 批次允许更正")
        void correctEvent_ClosedBatch_Allowed() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.CLOSED.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.SUBMITTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);
            when(traceEventMapper.updateStatusToCorrected(eq(500L), eq(1000L), eq(10L), anyLong(), any())).thenReturn(1);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "归档批次更正", null, "审计补充修正"
            );

            TraceEventResponse resp = eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1);
            assertThat(resp.correctsEventId()).isEqualTo(500L);
        }

        @Test
        @DisplayName("更正重试必须在状态检查前识别原结果：目标原事件已被更新为 CORRECTED 时仍能成功返回原更正记录")
        void correctEvent_IdempotentRetry_IdentifiedBeforeStateCheck() {
            // 已落库的更正记录（correctsEventId = 500L）
            TraceEvent existingCorrection = new TraceEvent();
            existingCorrection.setId(501L);
            existingCorrection.setBatchId(1000L);
            existingCorrection.setOrgId(10L);
            existingCorrection.setOperatorId(100L);
            existingCorrection.setEventType("PROCESS");
            existingCorrection.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            existingCorrection.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            existingCorrection.setSiteId(null);
            existingCorrection.setDataSource("MANUAL");
            existingCorrection.setStatus("SUBMITTED");
            existingCorrection.setSummary("更正摘要");
            existingCorrection.setDetailsJson(null);
            existingCorrection.setCorrectsEventId(500L);
            existingCorrection.setCorrectionReason("修正原因");
            existingCorrection.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_KEY)).thenReturn(existingCorrection);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "更正摘要", null, "修正原因"
            );

            // 即使未查 batch 和 targetEvent，也能直接识别原更正结果
            TraceEventResponse resp = eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1);

            assertThat(resp.id()).isEqualTo(501L);
            assertThat(resp.correctsEventId()).isEqualTo(500L);
            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(anyLong());
            verify(traceEventMapper, never()).selectByIdIgnoreTenantForUpdate(anyLong());
        }

        @Test
        @DisplayName("链式更正防分叉：对已是 CORRECTED 状态的旧版本再次发起更正，拒绝并返回 409 EVENT_ALREADY_CORRECTED")
        void correctEvent_TargetAlreadyCorrected_Rejected() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent oldEvent = new TraceEvent();
            oldEvent.setId(500L);
            oldEvent.setBatchId(1000L);
            oldEvent.setOrgId(10L);
            oldEvent.setStatus(TraceEventStatus.CORRECTED.name()); // 已经是历史旧版本
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(oldEvent);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "试图分叉更正", null, "非法分叉更正原因"
            );

            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(be.getCode()).isEqualTo("EVENT_ALREADY_CORRECTED");
                    });
        }

        @Test
        @DisplayName("并发更正竞争防分叉：旧版本状态并发流转导致受影响行数为 0 时抛出 409 异常并触发回滚")
        void correctEvent_ConcurrentConflict_ThrowsConflict() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.SUBMITTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);

            // 更新行数返回 0 (被并发修改)
            when(traceEventMapper.updateStatusToCorrected(eq(500L), eq(1000L), eq(10L), anyLong(), any())).thenReturn(0);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", null, "原因"
            );

            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(be.getCode()).isEqualTo("EVENT_ALREADY_CORRECTED");
                    });
        }

        @Test
        @DisplayName("唯一索引防分叉冲突：插入时触发 uk_trace_event_corrects 冲突，使用 selectByCorrectsEventIdForUpdate 当前读诊断并返回 409")
        void correctEvent_DuplicateKeyException_ForkDetected_UsesForUpdateCurrentRead() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.SUBMITTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);

            // 模拟 insert 抛出 DuplicateKeyException (uk_trace_event_corrects 冲突)
            DuplicateKeyException dupEx = new DuplicateKeyException("Duplicate entry '500' for key 'uk_trace_event_corrects'");
            org.mockito.Mockito.doThrow(dupEx).when(traceEventMapper).insert(any(TraceEvent.class));

            // 幂等键当前读未命中 (说明不是相同 idempotency_key，而是 corrects_event_id 冲突)
            when(traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_KEY)).thenReturn(null);

            // 目标更正记录当前读命中已提交的更正事件
            TraceEvent existingCorrection = new TraceEvent();
            existingCorrection.setId(502L);
            existingCorrection.setCorrectsEventId(500L);
            when(traceEventMapper.selectByCorrectsEventIdForUpdate(500L)).thenReturn(existingCorrection);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", null, "原因"
            );

            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(be.getCode()).isEqualTo("EVENT_ALREADY_CORRECTED");
                    });

            // 严格验证：必须通过 SELECT ... FOR UPDATE 当前读查询，防止 REPEATABLE READ 下快照读返回 null 误报 500
            verify(traceEventMapper).selectByCorrectsEventIdForUpdate(500L);
            verify(traceEventMapper, never()).selectByCorrectsEventId(anyLong());
        }

        @Test
        @DisplayName("并发锁后当前读幂等恢复：同 key 并发更正唤醒后，target 已是 CORRECTED，但当前读成功恢复原更正记录")
        void correctEvent_ConcurrentSameKeyWakeup_IdempotentRecovered() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            // 初始快照未查到，但获取排他锁后唤醒时，target 已被前一事务改为 CORRECTED
            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.CORRECTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);

            // 锁后当前读查询到了前一事务以相同 key 写入的更正记录
            TraceEvent existingCorrection = new TraceEvent();
            existingCorrection.setId(502L);
            existingCorrection.setBatchId(1000L);
            existingCorrection.setOrgId(10L);
            existingCorrection.setOperatorId(100L);
            existingCorrection.setEventType("PROCESS");
            existingCorrection.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            existingCorrection.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            existingCorrection.setSiteId(null);
            existingCorrection.setDataSource("MANUAL");
            existingCorrection.setStatus("SUBMITTED");
            existingCorrection.setSummary("更正摘要");
            existingCorrection.setDetailsJson(null);
            existingCorrection.setCorrectsEventId(500L);
            existingCorrection.setCorrectionReason("修正原因");
            existingCorrection.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_KEY)).thenReturn(existingCorrection);

            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "更正摘要", null, "修正原因"
            );

            TraceEventResponse resp = eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1);

            assertThat(resp.id()).isEqualTo(502L);
            assertThat(resp.correctsEventId()).isEqualTo(500L);
            verify(traceEventMapper, never()).insert(any(TraceEvent.class));
        }

        @Test
        @DisplayName("并发锁后当前读语义冲突：同 key 并发更正唤醒后当前读发现内容冲突，抛出 409 IDEMPOTENCY_KEY_CONFLICT")
        void correctEvent_ConcurrentSameKeyWakeup_SemanticConflict_ThrowsConflict() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);

            TraceEvent target = new TraceEvent();
            target.setId(500L);
            target.setBatchId(1000L);
            target.setOrgId(10L);
            target.setStatus(TraceEventStatus.CORRECTED.name());
            when(traceEventMapper.selectByIdIgnoreTenantForUpdate(500L)).thenReturn(target);

            TraceEvent existingCorrection = new TraceEvent();
            existingCorrection.setId(502L);
            existingCorrection.setBatchId(1000L);
            existingCorrection.setOrgId(10L);
            existingCorrection.setOperatorId(100L);
            existingCorrection.setEventType("PROCESS");
            existingCorrection.setOccurredAt(OCCURRED_AT.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            existingCorrection.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            existingCorrection.setSiteId(null);
            existingCorrection.setDataSource("MANUAL");
            existingCorrection.setStatus("SUBMITTED");
            existingCorrection.setSummary("更正摘要-原有");
            existingCorrection.setDetailsJson(null);
            existingCorrection.setCorrectsEventId(500L);
            existingCorrection.setCorrectionReason("原修正原因");
            existingCorrection.setIdempotencyKey(VALID_KEY);

            when(traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_KEY)).thenReturn(existingCorrection);

            // 新请求的摘要不同
            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "不同的更正摘要", null, "原修正原因"
            );

            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    });
        }

        @Test
        @DisplayName("非 OPERATOR 角色或平台角色更正操作被拦截 (403 ACCESS_DENIED)")
        void correctEvent_NonOperator_Denied() {
            CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "修正后的加工摘要", null, "补充说明温度"
            );

            // 1. 平台管理员拦截
            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, platformAdmin))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });

            // 2. 企业内部审计员拦截
            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, auditorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });

            // 3. 平台 scope 用户即便具有 OPERATOR 角色亦被拦截
            TraceSecurityPrincipal platformOperator = new TraceSecurityPrincipal(
                    2L, "platform_op", "平台操作员", "{noop}pwd",
                    0L, "PLATFORM", "溯源监管中心", "REGULATOR",
                    List.of("OPERATOR"), List.of("PLATFORM"), true, true
            );
            assertThatThrownBy(() -> eventService.correctEvent(1000L, 500L, req, VALID_KEY, platformOperator))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                    });
        }
    }

    @Nested
    @DisplayName("受控 detailsJson 边界约束单元测试")
    class DetailsJsonConstraintTests {

        @Test
        @DisplayName("属性数量超过 20 个拒绝 (400 INVALID_REQUEST)")
        void detailsJson_Exceeds20Properties_Rejected() {
            Map<String, Object> map = new HashMap<>();
            for (int i = 1; i <= 21; i++) {
                map.put("key" + i, "value" + i);
            }

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", map
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                    });
        }

        @Test
        @DisplayName("键名不符合 ^[a-z][A-Za-z0-9]{0,63}$ 正则拒绝 (400 INVALID_REQUEST)")
        void detailsJson_InvalidKeyRegex_Rejected() {
            // 大写开头
            Map<String, Object> mapUpper = Map.of("InvalidKey", "val");
            CreateTraceEventRequest reqUpper = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", mapUpper
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, reqUpper, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("detailsJson 属性键名不合规");

            // 下划线
            Map<String, Object> mapUnder = Map.of("line_number", "L1");
            CreateTraceEventRequest reqUnder = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", mapUnder
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, reqUnder, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("detailsJson 属性键名不合规");
        }

        @Test
        @DisplayName("键名占用核心系统/业务字段拒绝 (400 INVALID_REQUEST)")
        void detailsJson_ForbiddenKeys_Rejected() {
            Map<String, Object> map = Map.of("batchId", 123);
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", map
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("detailsJson 属性键名不能占用核心系统与业务字段");
        }

        @Test
        @DisplayName("包含嵌套对象或列表拒绝 (400 INVALID_REQUEST)")
        void detailsJson_NestedStructure_Rejected() {
            Map<String, Object> mapNested = Map.of("nested", Map.of("child", 1));
            CreateTraceEventRequest reqNested = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", mapNested
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, reqNested, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅支持单层标量属性");

            Map<String, Object> mapList = Map.of("items", List.of("a", "b"));
            CreateTraceEventRequest reqList = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", mapList
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, reqList, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅支持单层标量属性");
        }

        @Test
        @DisplayName("字符串属性值长度超过 500 拒绝 (400 INVALID_REQUEST)")
        void detailsJson_StringLengthExceeds500_Rejected() {
            Map<String, Object> map = Map.of("longText", "A".repeat(501));
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, null, "MANUAL", "摘要", map
            );
            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("长度不得超过 500 个字符");
        }
    }

    @Nested
    @DisplayName("场所 siteId 校验单元测试")
    class SiteValidationTests {

        @Test
        @DisplayName("site 不存在返回 404 RESOURCE_NOT_FOUND")
        void site_NotFound() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);
            when(siteMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, 999L, "MANUAL", "摘要", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("site 属于其他组织返回 403 ORG_SCOPE_DENIED")
        void site_CrossOrg() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);
            Site otherSite = createSite(888L, 20L, "ACTIVE");
            when(siteMapper.selectByIdIgnoreTenant(888L)).thenReturn(otherSite);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, 888L, "MANUAL", "摘要", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ORG_SCOPE_DENIED"));
        }

        @Test
        @DisplayName("site 处于停用状态返回 422 SITE_NOT_ACTIVE")
        void site_NotActive() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenantForUpdate(1000L)).thenReturn(batch);
            Site inactiveSite = createSite(888L, 10L, "INACTIVE");
            when(siteMapper.selectByIdIgnoreTenant(888L)).thenReturn(inactiveSite);

            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", OCCURRED_AT, 888L, "MANUAL", "摘要", null
            );

            assertThatThrownBy(() -> eventService.createEvent(1000L, req, VALID_KEY, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SITE_NOT_ACTIVE"));
        }
    }

    @Nested
    @DisplayName("追溯事件列表查询测试 (GET /api/v1/batches/{batchId}/events)")
    class ListEventsTests {

        @Test
        @DisplayName("本组织操作员查询返回稳定排序事件列表且包含已更正事件")
        void listEvents_Success() {
            Batch batch = createBatch(1000L, 10L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenant(1000L)).thenReturn(batch);

            TraceEvent e1 = new TraceEvent();
            e1.setId(1L);
            e1.setBatchId(1000L);
            e1.setOrgId(10L);
            e1.setStatus("CORRECTED");
            e1.setEventType("SOURCE");
            e1.setOccurredAt(LocalDateTime.of(2026, 9, 1, 8, 0));
            e1.setRecordedAt(LocalDateTime.of(2026, 9, 1, 8, 5));

            TraceEvent e2 = new TraceEvent();
            e2.setId(2L);
            e2.setBatchId(1000L);
            e2.setOrgId(10L);
            e2.setStatus("SUBMITTED");
            e2.setEventType("SOURCE");
            e2.setCorrectsEventId(1L);
            e2.setCorrectionReason("修正捕捞时间");
            e2.setOccurredAt(LocalDateTime.of(2026, 9, 1, 8, 0));
            e2.setRecordedAt(LocalDateTime.of(2026, 9, 1, 8, 10));

            when(traceEventMapper.selectByBatchIdAndOrgId(1000L, 10L)).thenReturn(List.of(e1, e2));

            List<TraceEventResponse> result = eventService.listEvents(1000L, operatorOrg1);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).status()).isEqualTo("CORRECTED");
            assertThat(result.get(1).status()).isEqualTo("SUBMITTED");
            assertThat(result.get(1).correctsEventId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("跨组织查询事件列表被拒绝 (403 ORG_SCOPE_DENIED)")
        void listEvents_CrossOrg_Denied() {
            Batch batch = createBatch(1000L, 20L, BatchStatus.ACTIVE.name());
            when(batchMapper.selectByIdIgnoreTenant(1000L)).thenReturn(batch);

            assertThatThrownBy(() -> eventService.listEvents(1000L, operatorOrg1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ORG_SCOPE_DENIED"));
        }

        @Test
        @DisplayName("批次不存在查询列表返回 404 RESOURCE_NOT_FOUND")
        void listEvents_BatchNotFound() {
            when(batchMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);

            assertThatThrownBy(() -> eventService.listEvents(999L, operatorOrg1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
