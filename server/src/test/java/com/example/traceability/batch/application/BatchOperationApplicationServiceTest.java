package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchItemRole;
import com.example.traceability.batch.domain.BatchOperation;
import com.example.traceability.batch.domain.BatchOperationItem;
import com.example.traceability.batch.domain.BatchOperationStatus;
import com.example.traceability.batch.domain.BatchOperationType;
import com.example.traceability.batch.domain.BatchRelation;
import com.example.traceability.batch.domain.BatchRelationType;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.batch.mapper.BatchOperationMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

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
@DisplayName("批次操作与谱系边应用服务业务逻辑单元测试")
class BatchOperationApplicationServiceTest {

    @Mock
    private BatchOperationMapper operationMapper;

    @Mock
    private BatchOperationItemMapper itemMapper;

    @Mock
    private BatchRelationMapper relationMapper;

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private TransferMapper transferMapper;

    @InjectMocks
    private BatchOperationApplicationService operationService;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal adminPrincipal;
    private TraceSecurityPrincipal otherOrgPrincipal;

    private static final String VALID_IDEMPOTENCY_KEY = "idem-key-1234567890-abcdef";
    private static final String VALID_SUBMISSION_KEY = "subm-key-1234567890-abcdef";

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "操作员", "{noop}pwd",
                10L, "ORG_01", "第一加工厂", "PROCESSOR",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        adminPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源中心", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        otherOrgPrincipal = new TraceSecurityPrincipal(
                202L, "operator2", "其他操作员", "{noop}pwd",
                20L, "ORG_02", "第二加工厂", "PROCESSOR",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
    }

    // =========================================================================
    // 1. 创建批次操作草稿测试
    // =========================================================================

    @Test
    @DisplayName("非 OPERATOR 角色拒绝创建批次操作草稿 (403 ACCESS_DENIED)")
    void createDraft_nonOperator_forbidden() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "合并操作",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("INPUT", 102L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 103L, new BigDecimal("200.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, adminPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("缺失或过短幂等键拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_invalidIdempotencyKey_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("INPUT", 102L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 103L, new BigDecimal("200.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, "short-key", operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("不支持的操作类型拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_invalidOperationType_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "UNKNOWN_TYPE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("非 kg 单位拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_nonKgUnit_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "box"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("INPUT 缺失 batchId 拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_inputMissingBatchId_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", null, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("LOSS 角色关联 batchId 拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_lossWithBatchId_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("90.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", 103L, new BigDecimal("10.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("同一操作中引用重复 batchId 拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_duplicateBatchId_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 101L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("items 中包含 null 元素拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_nullItemInItems_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                Arrays.asList(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        null
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("MERGE 基数不合法（少于2个INPUT）拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_mergeCardinalityInvalid_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("SPLIT 基数不合法（少于2个OUTPUT）拒绝创建 (400 INVALID_REQUEST)")
    void createDraft_splitCardinalityInvalid_badRequest() {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "SPLIT",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("合法创建草稿成功，生成 DRAFT 操作与明细项目")
    void createDraft_success() {
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                occurredAt,
                "两批扇贝合并",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("600.000"), "kg"),
                        new BatchOperationItemRequest("INPUT", 102L, new BigDecimal("420.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 103L, new BigDecimal("480.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 104L, new BigDecimal("520.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("20.000"), "kg")
                )
        );

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);

        BatchOperationItem i1 = new BatchOperationItem();
        i1.setId(1L);
        i1.setRole("INPUT");
        i1.setBatchId(101L);
        i1.setQuantity(new BigDecimal("600.000"));
        i1.setUnitCode("kg");
        i1.setNormalizedQuantity(new BigDecimal("600.000"));

        BatchOperationItem i2 = new BatchOperationItem();
        i2.setId(2L);
        i2.setRole("OUTPUT");
        i2.setBatchId(103L);
        i2.setQuantity(new BigDecimal("480.000"));
        i2.setUnitCode("kg");
        i2.setNormalizedQuantity(new BigDecimal("480.000"));

        when(itemMapper.selectByOperationId(any())).thenReturn(List.of(i1, i2));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("DRAFT");
        assertThat(resp.operationType()).isEqualTo("MERGE");
        assertThat(resp.operationNo()).startsWith("OP");
        verify(operationMapper).insert((BatchOperation) any());
        verify(itemMapper).insertBatch(any());
    }

    @Test
    @DisplayName("创建不平衡草稿（输入100kg产出90kg）时 balanced 标识为 false")
    void createDraft_unbalanced_returnsBalancedFalse() {
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "不平衡草稿",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("90.000"), "kg")
                )
        );

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);

        BatchOperationItem i1 = new BatchOperationItem();
        i1.setId(1L);
        i1.setRole("INPUT");
        i1.setBatchId(101L);
        i1.setQuantity(new BigDecimal("100.000"));
        i1.setUnitCode("kg");
        i1.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem i2 = new BatchOperationItem();
        i2.setId(2L);
        i2.setRole("OUTPUT");
        i2.setBatchId(102L);
        i2.setQuantity(new BigDecimal("90.000"));
        i2.setUnitCode("kg");
        i2.setNormalizedQuantity(new BigDecimal("90.000"));

        when(itemMapper.selectByOperationId(any())).thenReturn(List.of(i1, i2));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.balanced()).isFalse();
    }

    @Test
    @DisplayName("创建平衡草稿（输入100kg产出90kg+损失10kg）时 balanced 标识为 true")
    void createDraft_balanced_returnsBalancedTrue() {
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "平衡草稿",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("90.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("10.000"), "kg")
                )
        );

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);

        BatchOperationItem i1 = new BatchOperationItem();
        i1.setId(1L);
        i1.setRole("INPUT");
        i1.setBatchId(101L);
        i1.setQuantity(new BigDecimal("100.000"));
        i1.setUnitCode("kg");
        i1.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem i2 = new BatchOperationItem();
        i2.setId(2L);
        i2.setRole("OUTPUT");
        i2.setBatchId(102L);
        i2.setQuantity(new BigDecimal("90.000"));
        i2.setUnitCode("kg");
        i2.setNormalizedQuantity(new BigDecimal("90.000"));

        BatchOperationItem i3 = new BatchOperationItem();
        i3.setId(3L);
        i3.setRole("LOSS");
        i3.setBatchId(null);
        i3.setQuantity(new BigDecimal("10.000"));
        i3.setUnitCode("kg");
        i3.setNormalizedQuantity(new BigDecimal("10.000"));

        when(itemMapper.selectByOperationId(any())).thenReturn(List.of(i1, i2, i3));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.balanced()).isTrue();
    }

    @Test
    @DisplayName("同组织相同幂等键载荷一致时直接重放原草稿")
    void createDraft_sameIdempotencySemantics_replay() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T10:00:00Z");
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "去壳加工",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("90.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("10.000"), "kg")
                )
        );

        BatchOperation existing = new BatchOperation();
        existing.setId(88L);
        existing.setOrgId(10L);
        existing.setOperationNo("OP-EXISTING-001");
        existing.setOperationType("PROCESS");
        existing.setOccurredAt(occurredAt.toLocalDateTime());
        existing.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
        existing.setStatus("DRAFT");
        existing.setNote("去壳加工");
        existing.setVersion(0L);

        BatchOperationItem ei1 = new BatchOperationItem();
        ei1.setId(1L);
        ei1.setRole("INPUT");
        ei1.setBatchId(101L);
        ei1.setQuantity(new BigDecimal("100.000"));
        ei1.setUnitCode("kg");
        ei1.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem ei2 = new BatchOperationItem();
        ei2.setId(2L);
        ei2.setRole("OUTPUT");
        ei2.setBatchId(102L);
        ei2.setQuantity(new BigDecimal("90.000"));
        ei2.setUnitCode("kg");
        ei2.setNormalizedQuantity(new BigDecimal("90.000"));

        BatchOperationItem ei3 = new BatchOperationItem();
        ei3.setId(3L);
        ei3.setRole("LOSS");
        ei3.setBatchId(null);
        ei3.setQuantity(new BigDecimal("10.000"));
        ei3.setUnitCode("kg");
        ei3.setNormalizedQuantity(new BigDecimal("10.000"));

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);
        when(itemMapper.selectByOperationId(88L)).thenReturn(List.of(ei1, ei2, ei3));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp.id()).isEqualTo(88L);
        assertThat(resp.operationNo()).isEqualTo("OP-EXISTING-001");
        verify(operationMapper, never()).insert((BatchOperation) any());
    }

    @Test
    @DisplayName("同组织相同幂等键但载荷不同抛出 409 IDEMPOTENCY_CONFLICT")
    void createDraft_differentIdempotencySemantics_conflict() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T10:00:00Z");
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "不同备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        BatchOperation existing = new BatchOperation();
        existing.setId(88L);
        existing.setOrgId(10L);
        existing.setOperationType("PROCESS");
        existing.setOccurredAt(occurredAt.toLocalDateTime());
        existing.setNote("原备注");

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);
        when(itemMapper.selectByOperationId(88L)).thenReturn(List.of());

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("items 乱序重排多重集合内容一致时成功幂等重放原草稿")
    void createDraft_reorderedItems_replay() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T10:00:00.123Z");
        // 请求中的 items 顺序颠倒：OUTPUT 在前，LOSS 居中，INPUT 在后
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "去壳加工",
                List.of(
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("90.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("10.000"), "kg"),
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg")
                )
        );

        BatchOperation existing = new BatchOperation();
        existing.setId(88L);
        existing.setOrgId(10L);
        existing.setOperationNo("OP-EXISTING-001");
        existing.setOperationType("PROCESS");
        existing.setOccurredAt(occurredAt.toLocalDateTime());
        existing.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
        existing.setStatus("DRAFT");
        existing.setNote("去壳加工");
        existing.setVersion(0L);

        // 库中已持久化的 items 顺序为常规顺序：INPUT, OUTPUT, LOSS
        BatchOperationItem ei1 = new BatchOperationItem();
        ei1.setId(1L);
        ei1.setRole("INPUT");
        ei1.setBatchId(101L);
        ei1.setQuantity(new BigDecimal("100.000"));
        ei1.setUnitCode("kg");
        ei1.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem ei2 = new BatchOperationItem();
        ei2.setId(2L);
        ei2.setRole("OUTPUT");
        ei2.setBatchId(102L);
        ei2.setQuantity(new BigDecimal("90.000"));
        ei2.setUnitCode("kg");
        ei2.setNormalizedQuantity(new BigDecimal("90.000"));

        BatchOperationItem ei3 = new BatchOperationItem();
        ei3.setId(3L);
        ei3.setRole("LOSS");
        ei3.setBatchId(null);
        ei3.setQuantity(new BigDecimal("10.000"));
        ei3.setUnitCode("kg");
        ei3.setNormalizedQuantity(new BigDecimal("10.000"));

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);
        when(itemMapper.selectByOperationId(88L)).thenReturn(List.of(ei1, ei2, ei3));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp.id()).isEqualTo(88L);
        assertThat(resp.operationNo()).isEqualTo("OP-EXISTING-001");
        verify(operationMapper, never()).insert((BatchOperation) any());
    }

    @Test
    @DisplayName("同组织相同幂等键但 occurredAt 相差 1ms 判定为冲突抛出 409 IDEMPOTENCY_CONFLICT")
    void createDraft_occurredAtDiffersByOneMilli_conflict() {
        OffsetDateTime originalOccurredAt = OffsetDateTime.parse("2026-09-09T10:00:00.123Z");
        OffsetDateTime diffOccurredAt = originalOccurredAt.plus(1, ChronoUnit.MILLIS); // 相差 1 毫秒

        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                diffOccurredAt,
                "去壳加工",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        BatchOperation existing = new BatchOperation();
        existing.setId(88L);
        existing.setOrgId(10L);
        existing.setOperationType("PROCESS");
        existing.setOccurredAt(originalOccurredAt.toLocalDateTime());
        existing.setNote("去壳加工");

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("occurredAt 纳秒不同但在毫秒截断后相同时成功幂等重放原草稿")
    void createDraft_occurredAtSameMilliDifferentNanos_replay() {
        // 原时间: .123000000 秒
        OffsetDateTime existingOccurredAt = OffsetDateTime.parse("2026-09-09T10:00:00.123Z");
        // 请求时间携带高精纳秒: .123456789 秒
        OffsetDateTime reqOccurredAt = existingOccurredAt.plusNanos(456789);

        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                reqOccurredAt,
                "去壳加工",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        BatchOperation existing = new BatchOperation();
        existing.setId(88L);
        existing.setOrgId(10L);
        existing.setOperationNo("OP-EXISTING-001");
        existing.setOperationType("PROCESS");
        existing.setOccurredAt(existingOccurredAt.toLocalDateTime());
        existing.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
        existing.setStatus("DRAFT");
        existing.setNote("去壳加工");
        existing.setVersion(0L);

        BatchOperationItem ei1 = new BatchOperationItem();
        ei1.setId(1L);
        ei1.setRole("INPUT");
        ei1.setBatchId(101L);
        ei1.setQuantity(new BigDecimal("100.000"));
        ei1.setUnitCode("kg");
        ei1.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem ei2 = new BatchOperationItem();
        ei2.setId(2L);
        ei2.setRole("OUTPUT");
        ei2.setBatchId(102L);
        ei2.setQuantity(new BigDecimal("100.000"));
        ei2.setUnitCode("kg");
        ei2.setNormalizedQuantity(new BigDecimal("100.000"));

        when(operationMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);
        when(itemMapper.selectByOperationId(88L)).thenReturn(List.of(ei1, ei2));

        BatchOperationResponse resp = operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp.id()).isEqualTo(88L);
        assertThat(resp.operationNo()).isEqualTo("OP-EXISTING-001");
        verify(operationMapper, never()).insert((BatchOperation) any());
    }

    // =========================================================================
    // 2. 提交批次操作测试
    // =========================================================================

    @Test
    @DisplayName("提交操作时非 OPERATOR 角色拒绝 (403 ACCESS_DENIED)")
    void submitOperation_nonOperator_forbidden() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, adminPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("提交操作时跨组织拒绝 (403 ORG_SCOPE_DENIED)")
    void submitOperation_otherOrg_forbidden() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(20L); // 属于 org 20
        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });
    }

    @Test
    @DisplayName("提交幂等已完成且 key 相同时直接重放返回原结果")
    void submitOperation_alreadySubmittedSameKey_replay() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("SUBMITTED");
        op.setSubmissionIdempotencyKey(VALID_SUBMISSION_KEY);
        op.setVersion(1L);

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of());
        when(relationMapper.selectByOperationId(100L)).thenReturn(List.of());

        BatchOperationResponse resp = operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal);

        assertThat(resp.id()).isEqualTo(100L);
        assertThat(resp.status()).isEqualTo("SUBMITTED");
        verify(operationMapper, never()).submitOperation(anyLong(), anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("提交幂等键被同组织其他操作占用时并发更新抛出 409 IDEMPOTENCY_CONFLICT 且不生成边")
    void submitOperation_submissionKeyOccupiedByOtherOperation_conflict() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(101L);
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(102L);
        outItem.setQuantity(new BigDecimal("100.000"));
        outItem.setNormalizedQuantity(new BigDecimal("100.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setBatchNo("B-101");
        b101.setQuantity(new BigDecimal("100.000"));
        b101.setStatus("ACTIVE");

        Batch b102 = new Batch();
        b102.setId(102L);
        b102.setOrgId(10L);
        b102.setBatchNo("B-102");
        b102.setQuantity(new BigDecimal("100.000"));
        b102.setStatus("ACTIVE");

        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(102L)).thenReturn(b102);
        when(relationMapper.countUpstreamRelationsByChildBatchId(102L)).thenReturn(0);
        when(itemMapper.sumSubmittedInputQuantityByBatchId(101L)).thenReturn(BigDecimal.ZERO);
        when(relationMapper.checkCycleWithCte(102L, 101L)).thenReturn(0);

        // 模拟并发更新时触发唯一键冲突 DuplicateKeyException
        when(operationMapper.submitOperation(eq(100L), eq(10L), eq(0L), eq(VALID_SUBMISSION_KEY), any(), anyLong()))
                .thenThrow(new DuplicateKeyException("uk_op_org_submission_idempotency conflict"));

        // 锁定读恢复：查到被另外一个操作 999L 占用了该提交幂等键
        BatchOperation otherOp = new BatchOperation();
        otherOp.setId(999L);
        otherOp.setOrgId(10L);
        otherOp.setStatus("SUBMITTED");
        otherOp.setSubmissionIdempotencyKey(VALID_SUBMISSION_KEY);
        when(operationMapper.selectByOrgIdAndSubmissionKeyForUpdate(10L, VALID_SUBMISSION_KEY)).thenReturn(otherOp);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });

        // 杜绝产生关系边
        verify(relationMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("涉及批次状态非 ACTIVE 拒绝流转 (422 BATCH_FLOW_BLOCKED)")
    void submitOperation_batchNotActive_blocked() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(101L);
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(102L);
        outItem.setQuantity(new BigDecimal("100.000"));
        outItem.setNormalizedQuantity(new BigDecimal("100.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setBatchNo("B-101");
        b101.setStatus("FROZEN"); // 冻结状态
        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_FLOW_BLOCKED");
                });
    }

    @Test
    @DisplayName("物料不平衡差值超过 0.001kg 拒绝流转 (422 BATCH_MASS_BALANCE_VIOLATION)")
    void submitOperation_massBalanceViolation_rejected() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        // 投入 100kg，产出 90kg，无损耗 -> 差 10kg
        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(101L);
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(102L);
        outItem.setQuantity(new BigDecimal("90.000"));
        outItem.setNormalizedQuantity(new BigDecimal("90.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setStatus("ACTIVE");
        Batch b102 = new Batch();
        b102.setId(102L);
        b102.setOrgId(10L);
        b102.setStatus("ACTIVE");
        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(102L)).thenReturn(b102);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_MASS_BALANCE_VIOLATION");
                });
    }

    @Test
    @DisplayName("输出批次已有上游谱系边拒绝流转 (422 BATCH_OUTPUT_ALREADY_PRODUCED)")
    void submitOperation_outputAlreadyProduced_rejected() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(101L);
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(102L);
        outItem.setQuantity(new BigDecimal("100.000"));
        outItem.setNormalizedQuantity(new BigDecimal("100.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setStatus("ACTIVE");
        Batch b102 = new Batch();
        b102.setId(102L);
        b102.setOrgId(10L);
        b102.setBatchNo("B-102");
        b102.setStatus("ACTIVE");
        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(102L)).thenReturn(b102);

        when(relationMapper.countUpstreamRelationsByChildBatchId(102L)).thenReturn(1); // 已有1条上游边

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_OUTPUT_ALREADY_PRODUCED");
                });
    }

    @Test
    @DisplayName("输入批次累计占用量超额拒绝流转 (422 BATCH_QUANTITY_EXCEEDED)")
    void submitOperation_inputQuantityExceeded_rejected() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(101L);
        inItem.setQuantity(new BigDecimal("60.000"));
        inItem.setNormalizedQuantity(new BigDecimal("60.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(102L);
        outItem.setQuantity(new BigDecimal("60.000"));
        outItem.setNormalizedQuantity(new BigDecimal("60.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setBatchNo("B-101");
        b101.setQuantity(new BigDecimal("100.000")); // 声明数量为 100kg
        b101.setStatus("ACTIVE");

        Batch b102 = new Batch();
        b102.setId(102L);
        b102.setOrgId(10L);
        b102.setBatchNo("B-102");
        b102.setQuantity(new BigDecimal("60.000"));
        b102.setStatus("ACTIVE");

        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(102L)).thenReturn(b102);

        when(relationMapper.countUpstreamRelationsByChildBatchId(102L)).thenReturn(0);
        // 历史已消耗 50kg，本次 60kg，50+60=110kg > 100kg
        when(itemMapper.sumSubmittedInputQuantityByBatchId(101L)).thenReturn(new BigDecimal("50.000"));

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_QUANTITY_EXCEEDED");
                });
    }

    @Test
    @DisplayName("recursive CTE 检测到有向环拒绝流转 (422 BATCH_RELATION_CYCLE)")
    void submitOperation_cycleDetected_rejected() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESS");

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setRole("INPUT");
        inItem.setBatchId(103L); // C 批次
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setRole("OUTPUT");
        outItem.setBatchId(101L); // A 批次 (形成 C -> A 闭环)
        outItem.setQuantity(new BigDecimal("100.000"));
        outItem.setNormalizedQuantity(new BigDecimal("100.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setBatchNo("B-101");
        b101.setQuantity(new BigDecimal("100.000"));
        b101.setStatus("ACTIVE");

        Batch b103 = new Batch();
        b103.setId(103L);
        b103.setOrgId(10L);
        b103.setBatchNo("B-103");
        b103.setQuantity(new BigDecimal("100.000"));
        b103.setStatus("ACTIVE");

        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(103L)).thenReturn(b103);

        when(relationMapper.countUpstreamRelationsByChildBatchId(101L)).thenReturn(0);
        when(itemMapper.sumSubmittedInputQuantityByBatchId(103L)).thenReturn(BigDecimal.ZERO);

        // 环检测：从 child 101 出发可达 parent 103 (返回 1)
        when(relationMapper.checkCycleWithCte(101L, 103L)).thenReturn(1);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_RELATION_CYCLE");
                });
    }

    @Test
    @DisplayName("标准链路提交成功：物料平衡、版本递增、生成笛卡尔积边")
    void submitOperation_success() {
        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("MERGE");

        BatchOperationItem i1 = new BatchOperationItem();
        i1.setId(1L);
        i1.setOperationId(100L);
        i1.setRole("INPUT");
        i1.setBatchId(101L);
        i1.setQuantity(new BigDecimal("600.000"));
        i1.setNormalizedQuantity(new BigDecimal("600.000"));

        BatchOperationItem i2 = new BatchOperationItem();
        i2.setId(2L);
        i2.setOperationId(100L);
        i2.setRole("INPUT");
        i2.setBatchId(102L);
        i2.setQuantity(new BigDecimal("420.000"));
        i2.setNormalizedQuantity(new BigDecimal("420.000"));

        BatchOperationItem i3 = new BatchOperationItem();
        i3.setId(3L);
        i3.setOperationId(100L);
        i3.setRole("OUTPUT");
        i3.setBatchId(103L);
        i3.setQuantity(new BigDecimal("480.000"));
        i3.setNormalizedQuantity(new BigDecimal("480.000"));

        BatchOperationItem i4 = new BatchOperationItem();
        i4.setId(4L);
        i4.setOperationId(100L);
        i4.setRole("OUTPUT");
        i4.setBatchId(104L);
        i4.setQuantity(new BigDecimal("520.000"));
        i4.setNormalizedQuantity(new BigDecimal("520.000"));

        BatchOperationItem i5 = new BatchOperationItem();
        i5.setId(5L);
        i5.setOperationId(100L);
        i5.setRole("LOSS");
        i5.setBatchId(null);
        i5.setQuantity(new BigDecimal("20.000"));
        i5.setNormalizedQuantity(new BigDecimal("20.000"));

        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);
        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(i1, i2, i3, i4, i5));

        Batch b101 = new Batch();
        b101.setId(101L);
        b101.setOrgId(10L);
        b101.setQuantity(new BigDecimal("600.000"));
        b101.setStatus("ACTIVE");

        Batch b102 = new Batch();
        b102.setId(102L);
        b102.setOrgId(10L);
        b102.setQuantity(new BigDecimal("420.000"));
        b102.setStatus("ACTIVE");

        Batch b103 = new Batch();
        b103.setId(103L);
        b103.setOrgId(10L);
        b103.setQuantity(new BigDecimal("480.000"));
        b103.setStatus("ACTIVE");

        Batch b104 = new Batch();
        b104.setId(104L);
        b104.setOrgId(10L);
        b104.setQuantity(new BigDecimal("520.000"));
        b104.setStatus("ACTIVE");

        when(batchMapper.selectByIdIgnoreTenantForUpdate(101L)).thenReturn(b101);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(102L)).thenReturn(b102);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(103L)).thenReturn(b103);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(104L)).thenReturn(b104);

        when(relationMapper.countUpstreamRelationsByChildBatchId(103L)).thenReturn(0);
        when(relationMapper.countUpstreamRelationsByChildBatchId(104L)).thenReturn(0);

        when(itemMapper.sumSubmittedInputQuantityByBatchId(101L)).thenReturn(BigDecimal.ZERO);
        when(itemMapper.sumSubmittedInputQuantityByBatchId(102L)).thenReturn(BigDecimal.ZERO);

        when(relationMapper.checkCycleWithCte(anyLong(), anyLong())).thenReturn(0);

        when(operationMapper.submitOperation(eq(100L), eq(10L), eq(0L), eq(VALID_SUBMISSION_KEY), any(), anyLong()))
                .thenReturn(1);

        BatchOperation submittedOp = new BatchOperation();
        submittedOp.setId(100L);
        submittedOp.setOrgId(10L);
        submittedOp.setStatus("SUBMITTED");
        submittedOp.setVersion(1L);
        submittedOp.setOperationType("MERGE");
        when(operationMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(submittedOp);

        BatchRelation r1 = new BatchRelation();
        r1.setId(1L);
        r1.setOperationId(100L);
        r1.setParentBatchId(101L);
        r1.setChildBatchId(103L);
        r1.setRelationType("MERGE");

        BatchRelation r2 = new BatchRelation();
        r2.setId(2L);
        r2.setOperationId(100L);
        r2.setParentBatchId(101L);
        r2.setChildBatchId(104L);
        r2.setRelationType("MERGE");

        BatchRelation r3 = new BatchRelation();
        r3.setId(3L);
        r3.setOperationId(100L);
        r3.setParentBatchId(102L);
        r3.setChildBatchId(103L);
        r3.setRelationType("MERGE");

        BatchRelation r4 = new BatchRelation();
        r4.setId(4L);
        r4.setOperationId(100L);
        r4.setParentBatchId(102L);
        r4.setChildBatchId(104L);
        r4.setRelationType("MERGE");

        when(relationMapper.selectByOperationId(100L)).thenReturn(List.of(r1, r2, r3, r4));

        BatchOperationResponse resp = operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal);

        assertThat(resp.status()).isEqualTo("SUBMITTED");
        assertThat(resp.version()).isEqualTo(1L);
        assertThat(resp.relations()).hasSize(4);
        verify(relationMapper).insertBatch(any());
    }

    @Test
    @DisplayName("提交批次操作：当输入批次存在 PENDING 状态在途交接时拒绝并抛出 409 BATCH_TRANSFER_PENDING")
    void submitOperation_whenInputBatchHasPendingTransfer_throwsConflict() {
        BatchOperation op = new BatchOperation();
        op.setId(100L);
        op.setOrgId(10L);
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setOperationType("PROCESSING");
        when(operationMapper.selectByIdIgnoreTenant(100L)).thenReturn(op);
        when(operationMapper.selectByOrgIdAndSubmissionKey(10L, VALID_SUBMISSION_KEY)).thenReturn(null);
        when(operationMapper.selectByIdForUpdate(100L)).thenReturn(op);

        BatchOperationItem inItem = new BatchOperationItem();
        inItem.setId(1L);
        inItem.setOperationId(100L);
        inItem.setBatchId(201L);
        inItem.setRole("INPUT");
        inItem.setQuantity(new BigDecimal("100.000"));
        inItem.setNormalizedQuantity(new BigDecimal("100.000"));
        inItem.setUnitCode("kg");

        BatchOperationItem outItem = new BatchOperationItem();
        outItem.setId(2L);
        outItem.setOperationId(100L);
        outItem.setBatchId(202L);
        outItem.setRole("OUTPUT");
        outItem.setQuantity(new BigDecimal("100.000"));
        outItem.setNormalizedQuantity(new BigDecimal("100.000"));
        outItem.setUnitCode("kg");

        when(itemMapper.selectByOperationId(100L)).thenReturn(List.of(inItem, outItem));

        Batch inBatch = new Batch();
        inBatch.setId(201L);
        inBatch.setOrgId(10L);
        inBatch.setBatchNo("BAT-IN-001");
        inBatch.setStatus("ACTIVE");
        inBatch.setUnitCode("kg");
        inBatch.setQuantity(new BigDecimal("100.000"));

        Batch outBatch = new Batch();
        outBatch.setId(202L);
        outBatch.setOrgId(10L);
        outBatch.setBatchNo("BAT-OUT-001");
        outBatch.setStatus("ACTIVE");
        outBatch.setUnitCode("kg");
        outBatch.setQuantity(new BigDecimal("100.000"));

        when(batchMapper.selectByIdIgnoreTenantForUpdate(201L)).thenReturn(inBatch);

        // 模拟输入批次存在 PENDING 状态交接
        when(transferMapper.countPendingTransfersByBatchId(201L)).thenReturn(1);

        BatchOperationSubmitRequest req = new BatchOperationSubmitRequest(0L);

        assertThatThrownBy(() -> operationService.submitOperation(100L, req, VALID_SUBMISSION_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_TRANSFER_PENDING");
                });
    }

    @Test
    @DisplayName("创建批次操作草稿：当引用的批次存在在途交接确认(PENDING)时拒绝创建并抛出 409 BATCH_TRANSFER_PENDING")
    void createDraftOperation_whenReferencedBatchInPendingTransfer_throwsConflict() {
        Batch inBatch = new Batch();
        inBatch.setId(201L);
        inBatch.setOrgId(10L);
        inBatch.setBatchNo("BAT-IN-001");
        inBatch.setStatus("ACTIVE");
        inBatch.setUnitCode("kg");
        inBatch.setQuantity(new BigDecimal("100.000"));

        Batch outBatch = new Batch();
        outBatch.setId(202L);
        outBatch.setOrgId(10L);
        outBatch.setBatchNo("BAT-OUT-001");
        outBatch.setStatus("ACTIVE");
        outBatch.setUnitCode("kg");
        outBatch.setQuantity(new BigDecimal("100.000"));

        when(batchMapper.selectByIdForUpdate(201L)).thenReturn(inBatch);
        when(transferMapper.countPendingTransfersByBatchId(201L)).thenReturn(1);

        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                OffsetDateTime.now(ZoneOffset.UTC),
                "测试在途批次阻断草稿创建",
                List.of(
                        new BatchOperationItemRequest("INPUT", 201L, new BigDecimal("100.000")),
                        new BatchOperationItemRequest("OUTPUT", 202L, new BigDecimal("100.000"))
                )
        );

        assertThatThrownBy(() -> operationService.createDraftOperation(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_TRANSFER_PENDING");
                });

        verify(operationMapper, never()).insert(any(BatchOperation.class));
    }
}
