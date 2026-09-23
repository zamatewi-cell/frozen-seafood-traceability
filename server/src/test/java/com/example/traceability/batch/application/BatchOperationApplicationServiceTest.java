package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchOperation;
import com.example.traceability.batch.domain.BatchOperationItem;
import com.example.traceability.batch.domain.BatchRelation;
import com.example.traceability.batch.domain.TraceBatchNoGenerator;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.batch.mapper.BatchOperationMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.application.TraceEventApplicationService.ProcessProjection;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.example.traceability.sale.mapper.SaleMapper;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A Slice 3 批次操作应用服务单元测试：
 * 恰好一个 INPUT、全量消耗、服务端生成 OUTPUT 草稿、精确物料平衡、原子关闭 / 激活、PROCESS 自动事件、
 * SPLIT 不改变批次类型也不产生事件、写权限与历史只读权限。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("批次操作（PROCESS / SPLIT）应用服务单元测试")
class BatchOperationApplicationServiceTest {

    private static final String CREATE_KEY = "create-key-1234567890-abcdef";
    private static final String SUBMIT_KEY = "submit-key-1234567890-abcdef";
    private static final long PROCESSOR_ORG = 10L;
    private static final long OTHER_ORG = 20L;
    private static final long INPUT_ID = 500L;
    private static final long PRODUCT_ID = 7L;

    @Mock private BatchOperationMapper operationMapper;
    @Mock private BatchOperationItemMapper itemMapper;
    @Mock private BatchRelationMapper relationMapper;
    @Mock private BatchMapper batchMapper;
    @Mock private TransferMapper transferMapper;
    @Mock private ProductMapper productMapper;
    @Mock private TraceBatchNoGenerator traceBatchNoGenerator;
    @Mock private TraceEventApplicationService traceEventService;
    @Mock private SaleMapper saleMapper;

    private BatchOperationApplicationService service;

    private TraceSecurityPrincipal processorOperator;
    private final Map<Long, Batch> batches = new HashMap<>();
    private final List<Batch> insertedBatches = new ArrayList<>();
    private final AtomicLong batchIds = new AtomicLong(900);

    @BeforeEach
    void setUp() {
        service = new BatchOperationApplicationService(operationMapper, itemMapper, relationMapper, batchMapper, transferMapper,
                productMapper, traceBatchNoGenerator, traceEventService, new BatchQuantityService(itemMapper, saleMapper));
        processorOperator = principal(PROCESSOR_ORG, "PROCESSOR", List.of("OPERATOR"), List.of("ORG_ONLY"));
        batches.put(INPUT_ID, activeBatch(INPUT_ID, "1000.000", "SOURCE"));

        when(batchMapper.selectByIdIgnoreTenantForUpdate(anyLong())).thenAnswer(inv -> batches.get(inv.<Long>getArgument(0)));
        when(batchMapper.selectByIdIgnoreTenant(anyLong())).thenAnswer(inv -> batches.get(inv.<Long>getArgument(0)));
        when(batchMapper.selectByIdsIgnoreTenant(anyCollection())).thenAnswer(inv -> {
            List<Batch> result = new ArrayList<>();
            for (Object id : inv.<java.util.Collection<?>>getArgument(0)) {
                Batch b = batches.get((Long) id);
                if (b != null) {
                    result.add(b);
                }
            }
            return result;
        });
        doAnswer(inv -> {
            Batch b = inv.getArgument(0);
            b.setId(batchIds.incrementAndGet());
            batches.put(b.getId(), b);
            insertedBatches.add(b);
            return 1;
        }).when(batchMapper).insert(any(Batch.class));
        doAnswer(inv -> {
            BatchOperation op = inv.getArgument(0);
            op.setId(77L);
            return 1;
        }).when(operationMapper).insert(any(BatchOperation.class));
        when(traceBatchNoGenerator.generate()).thenAnswer(inv -> "TB-GEN-" + batchIds.get());
        when(itemMapper.sumSubmittedInputQuantityByBatchId(anyLong())).thenReturn(BigDecimal.ZERO);
        when(transferMapper.countActiveTransfersByBatchId(anyLong())).thenReturn(0);
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private static TraceSecurityPrincipal principal(long orgId, String orgType, List<String> roles, List<String> scopes) {
        return new TraceSecurityPrincipal(
                100L + orgId, "user" + orgId, "用户" + orgId, "{noop}pwd",
                orgId, "ORG_" + orgId, "组织" + orgId, orgType,
                roles, scopes, true, true
        );
    }

    private static Batch activeBatch(long id, String quantity, String batchType) {
        Batch b = new Batch();
        b.setId(id);
        b.setOrgId(PROCESSOR_ORG);
        b.setCreationOrgId(1L);
        b.setProductId(PRODUCT_ID);
        b.setTraceBatchNo("TB-" + id);
        b.setBatchType(batchType);
        b.setQuantity(new BigDecimal(quantity));
        b.setUnitCode("kg");
        b.setOriginType("DOMESTIC_CAPTURE");
        b.setOriginText("舟山渔场");
        b.setCaptureDate(java.time.LocalDate.of(2026, 9, 1));
        b.setFreezeDate(java.time.LocalDate.of(2026, 9, 2));
        b.setFlowStatus("ACTIVE");
        b.setRiskStatus("NORMAL");
        b.setVersion(3L);
        b.setIsDeleted(0);
        return b;
    }

    private static BatchOperationItemRequest input(long batchId, String qty) {
        return new BatchOperationItemRequest("INPUT", batchId, new BigDecimal(qty));
    }

    private static BatchOperationItemRequest output(String qty) {
        return new BatchOperationItemRequest("OUTPUT", null, new BigDecimal(qty));
    }

    private static BatchOperationItemRequest other(String role, String qty) {
        return new BatchOperationItemRequest(role, null, new BigDecimal(qty));
    }

    private static BatchOperationCreateRequest request(String type, BatchOperationItemRequest... items) {
        return new BatchOperationCreateRequest(type, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), "备注", List.of(items));
    }

    private static BatchOperationCreateRequest processRequest() {
        return request("PROCESS", input(INPUT_ID, "1000"), output("960"), other("LOSS", "30"), other("SAMPLE", "10"));
    }

    private static void assertBusiness(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) ex;
        assertThat(be.getStatus()).isEqualTo(status);
        assertThat(be.getCode()).isEqualTo(code);
    }

    private void expectCreateFailure(BatchOperationCreateRequest req, TraceSecurityPrincipal who, HttpStatus status, String code) {
        assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, who))
                .satisfies(ex -> assertBusiness(ex, status, code));
        verify(operationMapper, never()).insert(any(BatchOperation.class));
        verify(batchMapper, never()).insert(any(Batch.class));
    }

    // =========================================================================
    // 创建：权限
    // =========================================================================

    @Nested
    @DisplayName("创建草稿：写权限")
    class CreatePermission {

        @Test
        @DisplayName("平台管理员不可代办 (403 ADMIN_RESTRICTED)")
        void admin_restricted() {
            TraceSecurityPrincipal admin = principal(1L, "PLATFORM", List.of("SYSTEM_ADMIN"), List.of("PLATFORM"));
            expectCreateFailure(processRequest(), admin, HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED");
        }

        @Test
        @DisplayName("仅质量管理员角色不可写 (403 ACCESS_DENIED)")
        void qualityManager_cannotWrite() {
            TraceSecurityPrincipal qm = principal(PROCESSOR_ORG, "PROCESSOR", List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));
            expectCreateFailure(processRequest(), qm, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"SOURCE", "CARRIER", "RETAILER"})
        @DisplayName("非 PROCESSOR 组织不可加工或拆分 (403 ORG_TYPE_NOT_ALLOWED)")
        void nonProcessorOrg_forbidden(String orgType) {
            TraceSecurityPrincipal op = principal(PROCESSOR_ORG, orgType, List.of("OPERATOR"), List.of("ORG_ONLY"));
            expectCreateFailure(processRequest(), op, HttpStatus.FORBIDDEN, "ORG_TYPE_NOT_ALLOWED");
        }

        @Test
        @DisplayName("输入批次不由本组织当前负责 (403 ORG_SCOPE_DENIED)")
        void inputHeldByOtherOrg_forbidden() {
            batches.get(INPUT_ID).setOrgId(OTHER_ORG);
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED");
        }

        @Test
        @DisplayName("缺失或过短幂等键 (400 INVALID_REQUEST)")
        void invalidKey_badRequest() {
            assertThatThrownBy(() -> service.createDraftOperation(processRequest(), "short", processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    // =========================================================================
    // 创建：类型、基数与明细形状
    // =========================================================================

    @Nested
    @DisplayName("创建草稿：类型、基数与明细形状")
    class CreateShape {

        @ParameterizedTest
        @ValueSource(strings = {"MERGE", "REPACK"})
        @DisplayName("MERGE / REPACK 在当前 Slice 不执行 (422 OPERATION_TYPE_NOT_SUPPORTED)")
        void mergeAndRepack_notSupported(String type) {
            BatchOperationCreateRequest req = request(type, input(INPUT_ID, "1000"), output("1000"));
            assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, processorOperator))
                    .satisfies(ex -> {
                        assertBusiness(ex, HttpStatus.UNPROCESSABLE_ENTITY, "OPERATION_TYPE_NOT_SUPPORTED");
                        assertThat(ex.getMessage()).contains("不是永久业务规则");
                    });
        }

        @Test
        @DisplayName("未知操作类型 (400)")
        void unknownType_badRequest() {
            expectCreateFailure(request("FILLET", input(INPUT_ID, "1000"), output("1000")),
                    processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("PROCESS 两个 INPUT 被拒绝：多输入合并属于 MERGE (400)")
        void process_twoInputs_rejected() {
            batches.put(501L, activeBatch(501L, "500", "SOURCE"));
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"), input(501L, "500"), output("1500"));
            assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, processorOperator))
                    .satisfies(ex -> {
                        assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
                        assertThat(ex.getMessage()).contains("恰好包含 1 个 INPUT").contains("MERGE");
                    });
        }

        @Test
        @DisplayName("SPLIT 两个 INPUT 被拒绝 (400)")
        void split_twoInputs_rejected() {
            expectCreateFailure(request("SPLIT", input(INPUT_ID, "1000"), input(501L, "500"), output("700"), output("800")),
                    processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("SPLIT 只有一个 OUTPUT 被拒绝 (400)")
        void split_singleOutput_rejected() {
            expectCreateFailure(request("SPLIT", input(INPUT_ID, "1000"), output("990"), other("LOSS", "10")),
                    processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("PROCESS 没有 INPUT 被拒绝 (400)")
        void process_noInput_rejected() {
            expectCreateFailure(request("PROCESS", output("960"), other("LOSS", "40")),
                    processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("OUTPUT 携带 batchId 被拒绝：输出批次由服务端生成 (400 OUTPUT_BATCH_SERVER_GENERATED)")
        void output_withBatchId_rejected() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"),
                    new BatchOperationItemRequest("OUTPUT", 999L, new BigDecimal("1000")));
            expectCreateFailure(req, processorOperator, HttpStatus.BAD_REQUEST, "OUTPUT_BATCH_SERVER_GENERATED");
        }

        @Test
        @DisplayName("LOSS 关联批次被拒绝 (400)")
        void loss_withBatchId_rejected() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"), output("970"),
                    new BatchOperationItemRequest("LOSS", 12L, new BigDecimal("30")));
            expectCreateFailure(req, processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("INPUT 携带产出专属字段被拒绝 (400)")
        void input_withOutputOnlyField_rejected() {
            BatchOperationCreateRequest req = request("PROCESS",
                    new BatchOperationItemRequest("INPUT", INPUT_ID, new BigDecimal("1000"), "kg", 8L, null, null),
                    output("1000"));
            expectCreateFailure(req, processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("业务发生时间晚于当前时间 (400)")
        void futureOccurredAt_rejected() {
            BatchOperationCreateRequest req = new BatchOperationCreateRequest("PROCESS",
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), null,
                    List.of(input(INPUT_ID, "1000"), output("1000")));
            expectCreateFailure(req, processorOperator, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    // =========================================================================
    // 创建：物料平衡与全量消耗
    // =========================================================================

    @Nested
    @DisplayName("创建草稿：精确物料平衡与全量消耗")
    class CreateQuantity {

        @Test
        @DisplayName("1000 ≠ 999.999：差 0.001 kg 也被拒绝 (422 BATCH_MASS_BALANCE_VIOLATION)")
        void unbalanced_byOneGram_rejected() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"), output("959.999"),
                    other("LOSS", "30"), other("SAMPLE", "10"));
            expectCreateFailure(req, processorOperator, HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_MASS_BALANCE_VIOLATION");
        }

        @Test
        @DisplayName("scale 不同但数值相等 (1000.0 = 960.000 + 30 + 10.00) 视为平衡")
        void balanced_withDifferentScales_accepted() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000.0"), output("960.000"),
                    other("LOSS", "30"), other("SAMPLE", "10.00"));
            BatchOperationResponse resp = service.createDraftOperation(req, CREATE_KEY, processorOperator);
            assertThat(resp.status()).isEqualTo("DRAFT");
            verify(operationMapper).insert(any(BatchOperation.class));
        }

        @Test
        @DisplayName("INPUT 等于剩余量但 scale 不同 (1000.000 vs 批次 1000.0) 视为全量")
        void inputEqualsRemaining_differentScale_accepted() {
            batches.get(INPUT_ID).setQuantity(new BigDecimal("1000.0"));
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000.000"), output("1000"));
            assertThat(service.createDraftOperation(req, CREATE_KEY, processorOperator).status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("部分 INPUT 被拒绝并提示先 SPLIT (422 PARTIAL_INPUT_NOT_ALLOWED)")
        void partialInput_rejected() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "500"), output("480"), other("LOSS", "20"));
            assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, processorOperator))
                    .satisfies(ex -> {
                        assertBusiness(ex, HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_INPUT_NOT_ALLOWED");
                        assertThat(ex.getMessage()).contains("SPLIT").contains("1000");
                    });
        }

        @Test
        @DisplayName("历史部分消耗批次必须恰好消耗剩余量")
        void legacyPartiallyConsumed_mustConsumeExactRemainder() {
            when(itemMapper.sumSubmittedInputQuantityByBatchId(INPUT_ID)).thenReturn(new BigDecimal("400"));
            expectCreateFailure(request("PROCESS", input(INPUT_ID, "1000"), output("1000")),
                    processorOperator, HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_INPUT_NOT_ALLOWED");
            BatchOperationCreateRequest ok = request("PROCESS", input(INPUT_ID, "600"), output("600"));
            assertThat(service.createDraftOperation(ok, CREATE_KEY, processorOperator).status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("已关闭或已被消耗的输入批次 (409 BATCH_ALREADY_CONSUMED)")
        void closedInput_rejected() {
            batches.get(INPUT_ID).setFlowStatus("CLOSED");
            batches.get(INPUT_ID).setConsumedByOperationId(66L);
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED");
        }

        @Test
        @DisplayName("冻结的输入批次 (422 BATCH_FLOW_BLOCKED)")
        void frozenInput_rejected() {
            batches.get(INPUT_ID).setRiskStatus("FROZEN");
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED");
        }

        @Test
        @DisplayName("Slice 5：已开始终端销售（first_sale_id 非空）的输入批次即使仍 ACTIVE 也拒绝 (409 BATCH_SALE_STARTED)")
        void saleStartedInput_rejected() {
            batches.get(INPUT_ID).setFirstSaleId(4242L);
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.CONFLICT, "BATCH_SALE_STARTED");
        }

        @Test
        @DisplayName("存在草稿或待接收交接的输入批次 (409 BATCH_TRANSFER_OPEN)")
        void openTransfer_rejected() {
            when(transferMapper.countActiveTransfersByBatchId(INPUT_ID)).thenReturn(1);
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.CONFLICT, "BATCH_TRANSFER_OPEN");
        }

        @Test
        @DisplayName("输入批次不存在 (404)")
        void missingInput_notFound() {
            BatchOperationCreateRequest req = request("PROCESS", input(4040L, "1000"), output("1000"));
            assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, processorOperator))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // 创建：服务端生成 OUTPUT 草稿
    // =========================================================================

    @Nested
    @DisplayName("创建草稿：服务端生成 OUTPUT 草稿批次")
    class CreateOutputs {

        @Test
        @DisplayName("PROCESS 产出 DRAFT+NORMAL / PROCESSING / 继承来源 / 不声明速冻，明细引用生成的批次")
        void process_generatesProcessingDraftOutput() {
            BatchOperationResponse resp = service.createDraftOperation(processRequest(), CREATE_KEY, processorOperator);

            assertThat(insertedBatches).hasSize(1);
            Batch out = insertedBatches.get(0);
            assertThat(out.getFlowStatus()).isEqualTo("DRAFT");
            assertThat(out.getRiskStatus()).isEqualTo("NORMAL");
            assertThat(out.getBatchType()).isEqualTo("PROCESSING");
            assertThat(out.getProducedByOperationId()).isEqualTo(77L);
            assertThat(out.getConsumedByOperationId()).isNull();
            assertThat(out.getOrgId()).isEqualTo(PROCESSOR_ORG);
            assertThat(out.getCreationOrgId()).isEqualTo(PROCESSOR_ORG);
            assertThat(out.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(out.getQuantity()).isEqualByComparingTo("960");
            assertThat(out.getOriginType()).isEqualTo("DOMESTIC_CAPTURE");
            assertThat(out.getOriginText()).isEqualTo("舟山渔场");
            assertThat(out.getCaptureDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
            assertThat(out.getFreezeDate()).as("普通 PROCESS 不推导速冻").isNull();
            assertThat(out.getCreationIdempotencyKey()).isNull();
            assertThat(out.getTraceBatchNo()).startsWith("TB-GEN-");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BatchOperationItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(itemMapper).insertBatch(captor.capture());
            List<BatchOperationItem> items = captor.getValue();
            assertThat(items).extracting(BatchOperationItem::getRole).containsExactly("INPUT", "OUTPUT", "LOSS", "SAMPLE");
            assertThat(items).extracting(BatchOperationItem::getBatchId).containsExactly(INPUT_ID, out.getId(), null, null);
            assertThat(resp.status()).isEqualTo("DRAFT");
            // 创建阶段不改变输入批次
            verify(batchMapper, never()).closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("PROCESS 可为产出指定其他 ACTIVE 产品；INACTIVE 产品被拒绝")
        void process_outputProduct() {
            Product active = new Product();
            active.setId(8L);
            active.setStatus("ACTIVE");
            when(productMapper.selectById(8L)).thenReturn(active);
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"),
                    new BatchOperationItemRequest("OUTPUT", null, new BigDecimal("1000"), "kg", 8L, "EXT-1", 365));
            service.createDraftOperation(req, CREATE_KEY, processorOperator);
            assertThat(insertedBatches.get(0).getProductId()).isEqualTo(8L);
            assertThat(insertedBatches.get(0).getExternalBatchNo()).isEqualTo("EXT-1");
            assertThat(insertedBatches.get(0).getShelfLifeDays()).isEqualTo(365);

            Product inactive = new Product();
            inactive.setId(9L);
            inactive.setStatus("INACTIVE");
            when(productMapper.selectById(9L)).thenReturn(inactive);
            BatchOperationCreateRequest bad = request("PROCESS", input(INPUT_ID, "1000"),
                    new BatchOperationItemRequest("OUTPUT", null, new BigDecimal("1000"), "kg", 9L, null, null));
            assertThatThrownBy(() -> service.createDraftOperation(bad, "create-key-other-1234567890", processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_ACTIVE"));
        }

        @Test
        @DisplayName("SPLIT 产出继承输入批次类型：SOURCE 输入 → SOURCE 产出（不声明加工）")
        void split_sourceInput_keepsSourceType() {
            service.createDraftOperation(request("SPLIT", input(INPUT_ID, "1000"), output("600"), output("400")),
                    CREATE_KEY, processorOperator);
            assertThat(insertedBatches).hasSize(2)
                    .allSatisfy(b -> {
                        assertThat(b.getBatchType()).isEqualTo("SOURCE");
                        assertThat(b.getProductId()).isEqualTo(PRODUCT_ID);
                        assertThat(b.getFlowStatus()).isEqualTo("DRAFT");
                        assertThat(b.getProducedByOperationId()).isEqualTo(77L);
                    });
        }

        @Test
        @DisplayName("SPLIT 产出继承输入批次类型：PROCESSING 输入 → PROCESSING 产出")
        void split_processingInput_keepsProcessingType() {
            batches.put(INPUT_ID, activeBatch(INPUT_ID, "960", "PROCESSING"));
            service.createDraftOperation(request("SPLIT", input(INPUT_ID, "960"), output("600"), output("360")),
                    CREATE_KEY, processorOperator);
            assertThat(insertedBatches).extracting(Batch::getBatchType).containsExactly("PROCESSING", "PROCESSING");
            assertThat(insertedBatches).extracting(b -> b.getQuantity().toPlainString()).containsExactly("600", "360");
        }

        @Test
        @DisplayName("数值相同的多个 OUTPUT（500 + 500）各自生成独立批次，明细一一对应")
        void split_equalOutputs_eachGetsOwnBatch() {
            service.createDraftOperation(request("SPLIT", input(INPUT_ID, "1000"), output("500"), output("500")),
                    CREATE_KEY, processorOperator);
            assertThat(insertedBatches).hasSize(2);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BatchOperationItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(itemMapper).insertBatch(captor.capture());
            assertThat(captor.getValue()).filteredOn(i -> "OUTPUT".equals(i.getRole()))
                    .extracting(BatchOperationItem::getBatchId)
                    .containsExactly(insertedBatches.get(0).getId(), insertedBatches.get(1).getId())
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("SPLIT 不改变产品：指定其他产品被拒绝 (400)")
        void split_otherProduct_rejected() {
            BatchOperationCreateRequest req = request("SPLIT", input(INPUT_ID, "1000"),
                    new BatchOperationItemRequest("OUTPUT", null, new BigDecimal("600"), "kg", 8L, null, null),
                    output("400"));
            assertThatThrownBy(() -> service.createDraftOperation(req, CREATE_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    // =========================================================================
    // 创建：幂等
    // =========================================================================

    @Nested
    @DisplayName("创建草稿：幂等")
    class CreateIdempotency {

        private BatchOperation existingDraft() {
            BatchOperation op = new BatchOperation();
            op.setId(55L);
            op.setOrgId(PROCESSOR_ORG);
            op.setOperationNo("OP-55");
            op.setOperationType("PROCESS");
            op.setStatus("DRAFT");
            op.setVersion(0L);
            op.setIsDeleted(0);
            op.setNote("备注");
            return op;
        }

        private List<BatchOperationItem> existingItems(long outputId) {
            return List.of(
                    item(1L, 55L, INPUT_ID, "INPUT", "1000.000"),
                    item(2L, 55L, outputId, "OUTPUT", "960.000"),
                    item(3L, 55L, null, "LOSS", "30.000"),
                    item(4L, 55L, null, "SAMPLE", "10.000"));
        }

        @Test
        @DisplayName("同键同语义重放原操作，不重复生成批次")
        void sameKeySameSemantics_replays() {
            BatchOperationCreateRequest req = processRequest();
            BatchOperation op = existingDraft();
            op.setOccurredAt(req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            Batch out = activeBatch(901L, "960", "PROCESSING");
            out.setFlowStatus("DRAFT");
            batches.put(901L, out);
            when(operationMapper.selectByOrgIdAndIdempotencyKeyIncludingDeleted(PROCESSOR_ORG, CREATE_KEY)).thenReturn(op);
            when(itemMapper.selectByOperationId(55L)).thenReturn(existingItems(901L));

            BatchOperationResponse resp = service.createDraftOperation(req, CREATE_KEY, processorOperator);

            assertThat(resp.id()).isEqualTo(55L);
            verify(operationMapper, never()).insert(any(BatchOperation.class));
            verify(batchMapper, never()).insert(any(Batch.class));
        }

        @Test
        @DisplayName("同键不同语义 (409 IDEMPOTENCY_CONFLICT)")
        void sameKeyDifferentSemantics_conflict() {
            BatchOperationCreateRequest req = request("PROCESS", input(INPUT_ID, "1000"), output("970"), other("LOSS", "30"));
            BatchOperation op = existingDraft();
            op.setOccurredAt(req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            batches.put(901L, activeBatch(901L, "960", "PROCESSING"));
            when(operationMapper.selectByOrgIdAndIdempotencyKeyIncludingDeleted(PROCESSOR_ORG, CREATE_KEY)).thenReturn(op);
            when(itemMapper.selectByOperationId(55L)).thenReturn(existingItems(901L));

            expectCreateFailure(req, processorOperator, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }

        @Test
        @DisplayName("幂等键已被已删除草稿占用 (409 IDEMPOTENCY_CONFLICT)")
        void keyUsedByDeletedDraft_conflict() {
            BatchOperation op = existingDraft();
            op.setIsDeleted(1);
            when(operationMapper.selectByOrgIdAndIdempotencyKeyIncludingDeleted(PROCESSOR_ORG, CREATE_KEY)).thenReturn(op);
            expectCreateFailure(processRequest(), processorOperator, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }
    }

    // =========================================================================
    // 提交
    // =========================================================================

    private static BatchOperationItem item(Long id, Long opId, Long batchId, String role, String qty) {
        BatchOperationItem i = new BatchOperationItem();
        i.setId(id);
        i.setOperationId(opId);
        i.setBatchId(batchId);
        i.setRole(role);
        i.setQuantity(new BigDecimal(qty));
        i.setNormalizedQuantity(new BigDecimal(qty));
        i.setUnitCode("kg");
        return i;
    }

    private static BatchOperation draftOp(long id, String type) {
        BatchOperation op = new BatchOperation();
        op.setId(id);
        op.setOrgId(PROCESSOR_ORG);
        op.setOperationNo("OP-" + id);
        op.setOperationType(type);
        op.setOccurredAt(LocalDateTime.of(2026, 9, 20, 8, 0));
        op.setStatus("DRAFT");
        op.setVersion(0L);
        op.setIsDeleted(0);
        return op;
    }

    private Batch draftOutput(long id, long opId, String qty, String type) {
        Batch b = activeBatch(id, qty, type);
        b.setFlowStatus("DRAFT");
        b.setProducedByOperationId(opId);
        b.setTraceBatchNo("TB-OUT-" + id);
        batches.put(id, b);
        return b;
    }

    /** 设定一个可提交的 DRAFT 操作及其明细；返回操作。 */
    private BatchOperation stubSubmittable(BatchOperation op, List<BatchOperationItem> items) {
        when(operationMapper.selectByIdIgnoreTenant(op.getId())).thenReturn(op);
        when(operationMapper.selectByIdForUpdate(op.getId())).thenReturn(op);
        when(operationMapper.selectByIdAndOrgId(op.getId(), PROCESSOR_ORG)).thenReturn(op);
        when(itemMapper.selectByOperationId(op.getId())).thenReturn(items);
        when(relationMapper.checkCycleWithCte(anyLong(), anyLong())).thenReturn(0);
        when(relationMapper.selectByOperationId(op.getId())).thenReturn(List.<BatchRelation>of());
        when(operationMapper.submitOperation(eq(op.getId()), eq(PROCESSOR_ORG), eq(0L), eq(SUBMIT_KEY), any(), anyLong())).thenReturn(1);
        when(batchMapper.closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(1);
        when(batchMapper.activateOperationOutput(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(1);
        return op;
    }

    private List<BatchOperationItem> processItems(long opId, long outputId) {
        return List.of(
                item(1L, opId, INPUT_ID, "INPUT", "1000.000"),
                item(2L, opId, outputId, "OUTPUT", "960.000"),
                item(3L, opId, null, "LOSS", "30.000"),
                item(4L, opId, null, "SAMPLE", "10.000"));
    }

    @Nested
    @DisplayName("提交：原子关闭 INPUT / 激活 OUTPUT / 谱系 / 事件")
    class Submit {

        @Test
        @DisplayName("PROCESS 提交：INPUT CLOSED、OUTPUT ACTIVE、TRANSFORM 谱系、每个 OUTPUT 一条 PROCESS 事件")
        void process_success() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));

            service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator);

            verify(batchMapper).closeConsumedInput(eq(INPUT_ID), eq(PROCESSOR_ORG), eq(77L), any(), anyLong());
            verify(batchMapper).activateOperationOutput(eq(901L), eq(PROCESSOR_ORG), eq(77L), any(), anyLong());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BatchRelation>> relations = ArgumentCaptor.forClass(List.class);
            verify(relationMapper).insertBatch(relations.capture());
            assertThat(relations.getValue()).singleElement().satisfies(r -> {
                assertThat(r.getParentBatchId()).isEqualTo(INPUT_ID);
                assertThat(r.getChildBatchId()).isEqualTo(901L);
                assertThat(r.getRelationType()).isEqualTo("TRANSFORM");
            });
            ArgumentCaptor<ProcessProjection> projection = ArgumentCaptor.forClass(ProcessProjection.class);
            verify(traceEventService, times(1)).appendProcessEvent(projection.capture(), eq(901L), anyLong(), any());
            ProcessProjection p = projection.getValue();
            assertThat(p.orgId()).isEqualTo(PROCESSOR_ORG);
            assertThat(p.inputTotal()).isEqualByComparingTo("1000");
            assertThat(p.lossQuantity()).isEqualByComparingTo("30");
            assertThat(p.wasteQuantity()).isEqualByComparingTo("0");
            assertThat(p.sampleQuantity()).isEqualByComparingTo("10");
            assertThat(p.outputs()).singleElement().satisfies(l -> assertThat(l.traceBatchNo()).isEqualTo("TB-OUT-901"));
        }

        @Test
        @DisplayName("SPLIT 提交：SPLIT 谱系、不产生 PACK / PROCESS 事件")
        void split_success_noEvent() {
            batches.put(INPUT_ID, activeBatch(INPUT_ID, "960", "PROCESSING"));
            draftOutput(902L, 78L, "600", "PROCESSING");
            draftOutput(903L, 78L, "360", "PROCESSING");
            stubSubmittable(draftOp(78L, "SPLIT"), List.of(
                    item(1L, 78L, INPUT_ID, "INPUT", "960"),
                    item(2L, 78L, 902L, "OUTPUT", "600"),
                    item(3L, 78L, 903L, "OUTPUT", "360")));

            service.submitOperation(78L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator);

            verify(batchMapper).closeConsumedInput(eq(INPUT_ID), eq(PROCESSOR_ORG), eq(78L), any(), anyLong());
            verify(batchMapper, times(2)).activateOperationOutput(anyLong(), eq(PROCESSOR_ORG), eq(78L), any(), anyLong());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BatchRelation>> relations = ArgumentCaptor.forClass(List.class);
            verify(relationMapper).insertBatch(relations.capture());
            assertThat(relations.getValue()).extracting(BatchRelation::getRelationType).containsOnly("SPLIT");
            assertThat(relations.getValue()).extracting(BatchRelation::getChildBatchId).containsExactly(902L, 903L);
            verify(traceEventService, never()).appendProcessEvent(any(), anyLong(), anyLong(), any());
            verify(traceEventService, never()).appendSourceEvent(any(), any(), any());
        }

        @Test
        @DisplayName("SPLIT SOURCE 批次：子批次随操作激活为 SOURCE 类型，但不生成 SOURCE / PROCESS / 任何事件（来源事实留在祖先批次，谱系由 BatchRelation 表达）")
        void split_sourceInput_activatesSourceTypedChildrenWithoutSourceEvent() {
            // 输入 B0 为 SOURCE 类型（setUp 默认），产出继承 SOURCE
            draftOutput(904L, 79L, "700", "SOURCE");
            draftOutput(905L, 79L, "300", "SOURCE");
            stubSubmittable(draftOp(79L, "SPLIT"), List.of(
                    item(1L, 79L, INPUT_ID, "INPUT", "1000"),
                    item(2L, 79L, 904L, "OUTPUT", "700"),
                    item(3L, 79L, 905L, "OUTPUT", "300")));

            service.submitOperation(79L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator);

            verify(batchMapper).activateOperationOutput(eq(904L), eq(PROCESSOR_ORG), eq(79L), any(), anyLong());
            verify(batchMapper).activateOperationOutput(eq(905L), eq(PROCESSOR_ORG), eq(79L), any(), anyLong());
            verify(traceEventService, never()).appendSourceEvent(any(), any(), any());
            verify(traceEventService, never()).appendProcessEvent(any(), anyLong(), anyLong(), any());
            verify(traceEventService, never()).appendShipmentTransportEvent(any(), any(), any(), any(), any(), any());
            verify(traceEventService, never()).appendShipmentArrivalEvent(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Slice 5（防御性）：提交时锁定的输入批次已有 first_sale_id → 409 BATCH_SALE_STARTED，不关闭、不激活、不写谱系与事件")
        void submit_whenInputSaleStarted_rejected() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            batches.get(INPUT_ID).setFirstSaleId(4243L);

            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_SALE_STARTED"));
            verify(batchMapper, never()).closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong());
            verify(batchMapper, never()).activateOperationOutput(anyLong(), anyLong(), anyLong(), any(), anyLong());
            verify(relationMapper, never()).insertBatch(any());
            verify(traceEventService, never()).appendProcessEvent(any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("已用同一提交键提交过：重放，不重复关闭 / 激活 / 写事件")
        void replay_sameKey() {
            BatchOperation op = draftOp(77L, "PROCESS");
            op.setStatus("SUBMITTED");
            op.setSubmissionIdempotencyKey(SUBMIT_KEY);
            op.setVersion(1L);
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            when(itemMapper.selectByOperationId(77L)).thenReturn(processItems(77L, 901L));

            BatchOperationResponse resp = service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator);

            assertThat(resp.status()).isEqualTo("SUBMITTED");
            verify(batchMapper, never()).closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong());
            verify(traceEventService, never()).appendProcessEvent(any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("已提交后换键再提交 (409 INVALID_STATE_TRANSITION)")
        void resubmit_differentKey_conflict() {
            BatchOperation op = draftOp(77L, "PROCESS");
            op.setStatus("SUBMITTED");
            op.setSubmissionIdempotencyKey("another-key-1234567890");
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION"));
        }

        @Test
        @DisplayName("版本号不匹配 (409 VERSION_CONFLICT)")
        void versionMismatch_conflict() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(5L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "VERSION_CONFLICT"));
            verify(batchMapper, never()).closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("其他组织的操作 (403 ORG_SCOPE_DENIED)")
        void otherOrgOperation_forbidden() {
            BatchOperation op = draftOp(77L, "PROCESS");
            op.setOrgId(OTHER_ORG);
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
        }

        @Test
        @DisplayName("提交时输入批次已被部分消耗：锁内复核拒绝 (422 PARTIAL_INPUT_NOT_ALLOWED)")
        void remainingChangedBeforeSubmit_rejected() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            when(itemMapper.sumSubmittedInputQuantityByBatchId(INPUT_ID)).thenReturn(new BigDecimal("100"));
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_INPUT_NOT_ALLOWED"));
            verify(operationMapper, never()).submitOperation(anyLong(), anyLong(), anyLong(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("提交时输入批次已转出给其他组织 (403 ORG_SCOPE_DENIED)")
        void inputTransferredAway_rejected() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            batches.get(INPUT_ID).setOrgId(OTHER_ORG);
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
        }

        @Test
        @DisplayName("提交时存在未结束交接 (409 BATCH_TRANSFER_OPEN)")
        void openTransferAtSubmit_rejected() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            when(transferMapper.countActiveTransfersByBatchId(INPUT_ID)).thenReturn(1);
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_TRANSFER_OPEN"));
        }

        @Test
        @DisplayName("OUTPUT 不是本操作产出的草稿 (409 OPERATION_OUTPUT_STATE_INVALID)")
        void outputNotDraft_rejected() {
            Batch out = draftOutput(901L, 77L, "960", "PROCESSING");
            out.setFlowStatus("ACTIVE");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "OPERATION_OUTPUT_STATE_INVALID"));
        }

        @Test
        @DisplayName("关闭输入的条件更新未命中：抛出冲突，不激活输出、不写事件（事务整体回滚）")
        void closeInputMiss_rollsBack() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), processItems(77L, 901L));
            when(batchMapper.closeConsumedInput(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(0);
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED"));
            verify(batchMapper, never()).activateOperationOutput(anyLong(), anyLong(), anyLong(), any(), anyLong());
            verify(relationMapper, never()).insertBatch(anyList());
            verify(traceEventService, never()).appendProcessEvent(any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("提交时持久化明细物料不平衡 (422)")
        void persistedItemsUnbalanced_rejected() {
            draftOutput(901L, 77L, "960", "PROCESSING");
            stubSubmittable(draftOp(77L, "PROCESS"), List.of(
                    item(1L, 77L, INPUT_ID, "INPUT", "1000"),
                    item(2L, 77L, 901L, "OUTPUT", "960")));
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_MASS_BALANCE_VIOLATION"));
        }

        @Test
        @DisplayName("平台管理员不可提交 (403 ADMIN_RESTRICTED)")
        void admin_cannotSubmit() {
            TraceSecurityPrincipal admin = principal(1L, "PLATFORM", List.of("SYSTEM_ADMIN"), List.of("PLATFORM"));
            assertThatThrownBy(() -> service.submitOperation(77L, new BatchOperationSubmitRequest(0L), SUBMIT_KEY, admin))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED"));
        }
    }

    // =========================================================================
    // 删除草稿
    // =========================================================================

    @Nested
    @DisplayName("删除草稿")
    class Delete {

        @Test
        @DisplayName("删除草稿同时逻辑删除明细与 OUTPUT 草稿批次")
        void delete_success() {
            BatchOperation op = draftOp(77L, "PROCESS");
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            when(operationMapper.selectByIdForUpdate(77L)).thenReturn(op);
            when(operationMapper.softDeleteDraft(eq(77L), eq(PROCESSOR_ORG), eq(0L), any(), anyLong())).thenReturn(1);

            service.deleteDraftOperation(77L, 0L, processorOperator);

            verify(itemMapper).softDeleteByOperationId(eq(77L), any(), anyLong());
            verify(batchMapper).softDeleteOperationDraftOutputs(eq(77L), eq(PROCESSOR_ORG), any(), anyLong());
        }

        @Test
        @DisplayName("已提交的操作不可删除 (409)")
        void delete_submitted_conflict() {
            BatchOperation op = draftOp(77L, "PROCESS");
            op.setStatus("SUBMITTED");
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            when(operationMapper.selectByIdForUpdate(77L)).thenReturn(op);
            assertThatThrownBy(() -> service.deleteDraftOperation(77L, 0L, processorOperator))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION"));
            verify(batchMapper, never()).softDeleteOperationDraftOutputs(anyLong(), anyLong(), any(), anyLong());
        }
    }

    // =========================================================================
    // 读取：历史只读权限
    // =========================================================================

    @Nested
    @DisplayName("读取：本组织历史只读，其他组织拒绝")
    class Read {

        @Test
        @DisplayName("批次已转出后，原加工企业质量管理员仍可读取本组织创建的操作")
        void historicalOrg_canReadOwnOperation() {
            BatchOperation op = draftOp(77L, "PROCESS");
            op.setStatus("SUBMITTED");
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            when(itemMapper.selectByOperationId(77L)).thenReturn(processItems(77L, 901L));
            batches.get(INPUT_ID).setOrgId(OTHER_ORG);
            TraceSecurityPrincipal qm = principal(PROCESSOR_ORG, "PROCESSOR", List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"));

            BatchOperationResponse resp = service.getOperation(77L, qm);

            assertThat(resp.id()).isEqualTo(77L);
            assertThat(resp.inputTotal()).isEqualByComparingTo("1000");
            assertThat(resp.balanced()).isTrue();
        }

        @Test
        @DisplayName("其他组织读取操作 (403)；不存在 (404)")
        void otherOrg_forbidden_missing_notFound() {
            BatchOperation op = draftOp(77L, "PROCESS");
            when(operationMapper.selectByIdIgnoreTenant(77L)).thenReturn(op);
            TraceSecurityPrincipal other = principal(OTHER_ORG, "RETAILER", List.of("OPERATOR"), List.of("ORG_ONLY"));
            assertThatThrownBy(() -> service.getOperation(77L, other))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
            assertThatThrownBy(() -> service.getOperation(78L, other)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("按批次列表：历史参与组织只返回本组织操作（按组织过滤）")
        void listByBatch_historicalOrg_ownOnly() {
            batches.get(INPUT_ID).setOrgId(OTHER_ORG);
            BatchOperation op = draftOp(77L, "PROCESS");
            when(operationMapper.countByBatchId(INPUT_ID, PROCESSOR_ORG)).thenReturn(1L);
            when(operationMapper.selectPageByBatchId(INPUT_ID, PROCESSOR_ORG, 0L, 20)).thenReturn(List.of(op));
            when(itemMapper.selectByOperationId(77L)).thenReturn(processItems(77L, 901L));

            SuccessEnvelope<List<BatchOperationResponse>> page = service.listOperationsByBatch(INPUT_ID, 1, 20, processorOperator);

            assertThat(page.data()).extracting(BatchOperationResponse::id).containsExactly(77L);
            verify(operationMapper).selectPageByBatchId(INPUT_ID, PROCESSOR_ORG, 0L, 20);
        }

        @Test
        @DisplayName("按批次列表：当前责任组织无操作时返回空列表")
        void listByBatch_holderWithoutOperations_empty() {
            when(operationMapper.countByBatchId(INPUT_ID, PROCESSOR_ORG)).thenReturn(0L);
            when(operationMapper.selectPageByBatchId(INPUT_ID, PROCESSOR_ORG, 0L, 20)).thenReturn(List.of());
            assertThat(service.listOperationsByBatch(INPUT_ID, 1, 20, processorOperator).data()).isEmpty();
        }

        @Test
        @DisplayName("按批次列表：无关组织 (403)")
        void listByBatch_unrelatedOrg_forbidden() {
            TraceSecurityPrincipal other = principal(OTHER_ORG, "RETAILER", List.of("OPERATOR"), List.of("ORG_ONLY"));
            when(operationMapper.countByBatchId(INPUT_ID, OTHER_ORG)).thenReturn(0L);
            assertThatThrownBy(() -> service.listOperationsByBatch(INPUT_ID, 1, 20, other))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
        }

        @Test
        @DisplayName("按批次列表：平台只读不限组织")
        void listByBatch_platform_unscoped() {
            TraceSecurityPrincipal platform = principal(1L, "PLATFORM", List.of("AUDITOR"), List.of("PLATFORM"));
            when(operationMapper.countByBatchId(eq(INPUT_ID), isNull())).thenReturn(0L);
            when(operationMapper.selectPageByBatchId(eq(INPUT_ID), isNull(), eq(0L), eq(20))).thenReturn(List.of());
            assertThat(service.listOperationsByBatch(INPUT_ID, 1, 20, platform).data()).isEmpty();
        }
    }
}
