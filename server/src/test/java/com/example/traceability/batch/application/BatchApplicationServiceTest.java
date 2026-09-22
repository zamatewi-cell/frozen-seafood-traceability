package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.domain.BatchType;
import com.example.traceability.batch.domain.OriginType;
import com.example.traceability.batch.domain.TraceBatchNoGenerator;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchPatchRequest;
import com.example.traceability.batch.dto.BatchQueryCriteria;
import com.example.traceability.batch.dto.BatchResponse;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追溯批次应用服务业务逻辑单元测试。
 * <p>
 * 覆盖双状态（flowStatus / riskStatus）与双编号（traceBatchNo / externalBatchNo）正交契约，
 * 以及完整恢复的枚举校验、产品生命周期验证、幂等安全重放、DuplicateKey 异常分类、
 * 条件更新/提交受影响行数为 0 时的精准分类以及组织隔离。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("追溯批次应用服务业务逻辑单元测试 (双状态/双编号契约与完备校验)")
class BatchApplicationServiceTest {

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private ProductMapper productMapper;

    @Spy
    private TraceBatchNoGenerator traceBatchNoGenerator = new TraceBatchNoGenerator();

    @Mock
    private TraceEventApplicationService traceEventApplicationService;

    @InjectMocks
    private BatchApplicationService batchService;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal adminPrincipal;
    private TraceSecurityPrincipal otherOrgOperatorPrincipal;
    private TraceSecurityPrincipal processorOperatorPrincipal;
    private TraceSecurityPrincipal sourceViewerPrincipal;

    private Product activeProduct;
    private Product inactiveProduct;

    private static final String VALID_IDEMPOTENCY_KEY = "idem-key-1234567890-abcdef";

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "企业操作员", "{noop}pwd",
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        adminPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源管理中心", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        otherOrgOperatorPrincipal = new TraceSecurityPrincipal(
                202L, "operator2", "第二企业操作员", "{noop}pwd",
                20L, "ORG_FISHERY_02", "第二远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        processorOperatorPrincipal = new TraceSecurityPrincipal(
                303L, "processor1", "加工企业操作员", "{noop}pwd",
                30L, "ORG_PROC_01", "东海水产加工有限公司", "PROCESSOR",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        sourceViewerPrincipal = new TraceSecurityPrincipal(
                404L, "viewer1", "来源企业质检员", "{noop}pwd",
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"), true, true
        );

        activeProduct = new Product();
        activeProduct.setId(500L);
        activeProduct.setProductCode("PRD-FSH-001");
        activeProduct.setPublicName("舟山大黄鱼");
        activeProduct.setStatus("ACTIVE");

        inactiveProduct = new Product();
        inactiveProduct.setId(501L);
        inactiveProduct.setProductCode("PRD-FSH-002");
        inactiveProduct.setPublicName("带鱼(停用)");
        inactiveProduct.setStatus("INACTIVE");
    }

    @Test
    @DisplayName("领域模型物理契约 - 旧 BatchStatus 与 batchNo/status 兼容 getter 彻底不复存在")
    void domainModel_ContractAbsenceVerification() {
        // 1. 验证旧 BatchStatus 不存在或类加载失败
        assertThatThrownBy(() -> Class.forName("com.example.traceability.batch.domain.BatchStatus"))
                .isInstanceOf(ClassNotFoundException.class);

        // 2. 验证 Batch 实体类中绝不包含 getBatchNo, setBatchNo, getStatus, setStatus
        Method[] methods = Batch.class.getDeclaredMethods();
        for (Method m : methods) {
            assertThat(m.getName()).isNotIn("getBatchNo", "setBatchNo", "getStatus", "setStatus");
        }

        // 3. 验证 Batch 实体类中存在双编号与双状态 getter
        assertThat(Batch.class).hasDeclaredMethods("getTraceBatchNo", "setTraceBatchNo",
                "getExternalBatchNo", "setExternalBatchNo",
                "getFlowStatus", "setFlowStatus",
                "getRiskStatus", "setRiskStatus");

        // 4. 验证 BatchCreateRequest 客户端 DTO 绝对不声明 traceBatchNo 字段
        for (var f : BatchCreateRequest.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotEqualTo("traceBatchNo");
        }

        // 5. creationOrgId 是服务端技术字段：实体必须有，客户端请求 DTO 与对外响应绝不暴露
        assertThat(Batch.class).hasDeclaredMethods("getCreationOrgId", "setCreationOrgId");
        for (var f : BatchCreateRequest.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotIn("creationOrgId", "creation_org_id");
        }
        for (var f : BatchPatchRequest.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotIn("creationOrgId", "creation_org_id");
        }
        for (var f : BatchResponse.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotIn("creationOrgId", "creation_org_id");
        }
        for (var f : PublicTraceProjectionResponse.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotIn("creationOrgId", "creation_org_id");
        }
        for (var f : PublicTraceProjectionResponse.BatchProjection.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotIn("creationOrgId", "creation_org_id");
        }
    }

    @Test
    @DisplayName("创建批次草稿 - creationOrgId 与 orgId 初始一致，且由服务端强制写入（客户端不可指定）")
    void createDraftBatch_SetsCreationOrgIdEqualToCurrentOrg() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-CREATION-ORG", 500L, new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "东海舟山渔场3号海域", LocalDate.of(2026, 9, 1), null, null, 180
        );

        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);

        ArgumentCaptor<Batch> captor = ArgumentCaptor.forClass(Batch.class);
        when(batchMapper.insert(any(Batch.class))).thenAnswer(invocation -> {
            Batch b = invocation.getArgument(0);
            b.setId(1234L);
            return 1;
        });

        batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        verify(batchMapper).insert(captor.capture());
        Batch persisted = captor.getValue();
        assertThat(persisted.getCreationOrgId()).isEqualTo(10L);
        assertThat(persisted.getOrgId()).isEqualTo(10L);
        assertThat(persisted.getCreationOrgId()).isEqualTo(persisted.getOrgId());
    }

    @Test
    @DisplayName("创建幂等作用域为不可变 creationOrgId - 批次已交接转出后重放原始创建请求命中原批次而不重复建批")
    void createDraftBatch_ReplayAfterTransfer_HitsOriginalBatchAndNeverInsertsAgain() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-REPLAY-001", 500L, new BigDecimal("300.000"), "kg",
                "DOMESTIC_CAPTURE", "东海舟山渔场3号海域", LocalDate.of(2026, 9, 1), null, null, 180
        );

        // 原批次由组织 10 创建，随后经 Transfer ACCEPTED 转出给组织 99：org_id 已变、creation_org_id 不变
        Batch transferredAway = new Batch();
        transferredAway.setId(4321L);
        transferredAway.setOrgId(99L);
        transferredAway.setCreationOrgId(10L);
        transferredAway.setProductId(500L);
        transferredAway.setTraceBatchNo("TB-AAAAAAAAAAAAAAAAAAAAAAAAAA");
        transferredAway.setExternalBatchNo("EXT-REPLAY-001");
        transferredAway.setBatchType("SOURCE");
        transferredAway.setQuantity(new BigDecimal("300.000"));
        transferredAway.setUnitCode("kg");
        transferredAway.setOriginType("DOMESTIC_CAPTURE");
        transferredAway.setOriginText("东海舟山渔场3号海域");
        transferredAway.setProductionDate(LocalDate.of(2026, 9, 1));
        transferredAway.setShelfLifeDays(180);
        transferredAway.setFlowStatus("ACTIVE");
        transferredAway.setRiskStatus("NORMAL");
        transferredAway.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);
        transferredAway.setVersion(1L);

        // 关键：按 creationOrgId=10 而不是当前 org_id=99 查询，才能命中原批次
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(transferredAway);

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp.id()).isEqualTo(4321L);
        // 如实返回该批次当前责任组织，绝不重复建批
        assertThat(resp.orgId()).isEqualTo(99L);
        verify(batchMapper, never()).insert(any(Batch.class));
    }

    @Test
    @DisplayName("创建幂等作用域 - 同 creationOrgId 同 key 但载荷不同仍返回 409 IDEMPOTENCY_KEY_REUSED")
    void createDraftBatch_ReplayAfterTransferWithDifferentPayload_StillConflicts() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-REPLAY-DIFFERENT", 500L, new BigDecimal("777.000"), "kg",
                "DOMESTIC_CAPTURE", "东海舟山渔场3号海域", LocalDate.of(2026, 9, 1), null, null, 180
        );

        Batch transferredAway = new Batch();
        transferredAway.setId(4321L);
        transferredAway.setOrgId(99L);
        transferredAway.setCreationOrgId(10L);
        transferredAway.setProductId(500L);
        transferredAway.setExternalBatchNo("EXT-REPLAY-001");
        transferredAway.setBatchType("SOURCE");
        transferredAway.setQuantity(new BigDecimal("300.000"));
        transferredAway.setUnitCode("kg");
        transferredAway.setOriginType("DOMESTIC_CAPTURE");
        transferredAway.setOriginText("东海舟山渔场3号海域");
        transferredAway.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(transferredAway);

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        verify(batchMapper, never()).insert(any(Batch.class));
    }

    @Test
    @DisplayName("创建批次草稿成功 - 服务端生成 traceBatchNo，初始化 DRAFT+NORMAL，externalBatchNo 空白规范化为 null")
    void createDraftBatch_Success_WithGeneratedTraceAndNormalizedExternal() {
        BatchCreateRequest req = new BatchCreateRequest(
                "   ", // 空白 externalBatchNo，应规范化为 null
                500L,
                new BigDecimal("100.500"),
                "kg",
                "DOMESTIC_CAPTURE",
                "东海舟山渔场3号海域",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                180
        );

        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        when(batchMapper.insert(any(Batch.class))).thenAnswer(invocation -> {
            Batch b = invocation.getArgument(0);
            b.setId(1000L);
            return 1;
        });

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.id()).isEqualTo(1000L);
        assertThat(resp.orgId()).isEqualTo(10L);
        // 服务端生成的 traceBatchNo 格式合规 (TB- 开头，总长 29 字符)
        assertThat(resp.traceBatchNo()).startsWith("TB-").hasSize(29);
        // 空白 externalBatchNo 规范化为 null
        assertThat(resp.externalBatchNo()).isNull();
        assertThat(resp.flowStatus()).isEqualTo("DRAFT");
        assertThat(resp.riskStatus()).isEqualTo("NORMAL");
        assertThat(resp.batchType()).isEqualTo("SOURCE");
        assertThat(resp.quantity()).isEqualByComparingTo("100.500");
        assertThat(resp.unitCode()).isEqualTo("kg");
        assertThat(resp.originType()).isEqualTo("DOMESTIC_CAPTURE");
        assertThat(resp.originText()).isEqualTo("东海舟山渔场3号海域");
        assertThat(resp.version()).isEqualTo(0L);
        assertThat(resp.createdBy()).isEqualTo(101L);
        assertThat(resp.updatedBy()).isEqualTo(101L);

        verify(batchMapper).insert(any(Batch.class));
        verify(productMapper).selectByIdForUpdate(500L);
    }

    @Test
    @DisplayName("创建批次两次返回由服务端生成的独立 traceBatchNo")
    void createDraftBatch_ServerGeneratesDifferentTraceBatchNos() {
        BatchCreateRequest req1 = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "海域1", null, null, null, null
        );
        BatchCreateRequest req2 = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "海域1", null, null, null, null
        );

        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.insert(any(Batch.class))).thenAnswer(invocation -> {
            Batch b = invocation.getArgument(0);
            b.setId(1001L);
            return 1;
        });

        BatchResponse resp1 = batchService.createDraftBatch(req1, "idem-key-111111111111", operatorPrincipal);
        BatchResponse resp2 = batchService.createDraftBatch(req2, "idem-key-222222222222", operatorPrincipal);

        assertThat(resp1.traceBatchNo()).isNotEqualTo(resp2.traceBatchNo());
        assertThat(resp1.externalBatchNo()).isEqualTo("EXT-001");
        assertThat(resp2.externalBatchNo()).isEqualTo("EXT-001");
    }

    @Test
    @DisplayName("创建批次草稿失败 - 非 OPERATOR 角色抛出 403 ACCESS_DENIED")
    void createDraftBatch_DeniedForNonOperator() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, adminPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });

        // 来源组织内非 OPERATOR 角色同样拒绝
        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, sourceViewerPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));

        verify(batchMapper, never()).insert(any(Batch.class));
    }

    @Test
    @DisplayName("创建来源批次失败 - 非 SOURCE 组织的 OPERATOR 返回 403 ORG_TYPE_NOT_ALLOWED，且不做幂等查询与落库")
    void createDraftBatch_DeniedForNonSourceOrganization() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, processorOperatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_TYPE_NOT_ALLOWED");
                });

        verify(batchMapper, never()).selectByCreationOrgIdAndIdempotencyKey(anyLong(), anyString());
        verify(batchMapper, never()).insert(any(Batch.class));
    }

    @Test
    @DisplayName("创建来源批次 - batchType 由服务端固定为 SOURCE，初始 DRAFT+NORMAL，单位固定 kg")
    void createDraftBatch_ServerFixesSourceTypeAndDraftNormal() {
        BatchCreateRequest req = new BatchCreateRequest(
                "SRC-2026-001", 500L, new BigDecimal("1000"), "KG",
                "IMPORT", "  进口原料  ", null, null, null, null
        );
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        ArgumentCaptor<Batch> captor = ArgumentCaptor.forClass(Batch.class);
        verify(batchMapper).insert(captor.capture());
        Batch inserted = captor.getValue();
        assertThat(inserted.getBatchType()).isEqualTo(BatchType.SOURCE.name());
        assertThat(inserted.getFlowStatus()).isEqualTo("DRAFT");
        assertThat(inserted.getRiskStatus()).isEqualTo("NORMAL");
        assertThat(inserted.getUnitCode()).isEqualTo("kg");
        assertThat(inserted.getOriginText()).isEqualTo("进口原料");
        assertThat(inserted.getOrgId()).isEqualTo(10L);
        assertThat(inserted.getCreationOrgId()).isEqualTo(10L);
        assertThat(resp.batchType()).isEqualTo("SOURCE");
        // 创建草稿不产生任何追溯事件
        verify(traceEventApplicationService, never()).appendSourceEvent(any(), anyLong(), any());
    }

    @Test
    @DisplayName("创建批次草稿失败 - 幂等键格式错误返回 400 INVALID_REQUEST")
    void createDraftBatch_InvalidIdempotencyKey() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        assertThatThrownBy(() -> batchService.createDraftBatch(req, "short-key", operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        assertThatThrownBy(() -> batchService.createDraftBatch(req, null, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("创建批次草稿失败 - 夹带服务端字段 / 非法 originType / unitCode / 数量及保质期返回 400 INVALID_REQUEST")
    void createDraftBatch_InvalidEnumsOrUnit() {
        // 客户端夹带 batchType / traceBatchNo / orgId / flowStatus 等服务端字段 -> 400，且不落库
        for (String forbidden : List.of("batchType", "traceBatchNo", "orgId", "creationOrgId", "flowStatus", "riskStatus", "status")) {
            BatchCreateRequest reqWithServerField = new BatchCreateRequest(
                    "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                    "DOMESTIC_CAPTURE", "来源说明", null, null, null, null,
                    Map.of(forbidden, "PROCESSING")
            );
            assertThatThrownBy(() -> batchService.createDraftBatch(reqWithServerField, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                        assertThat(be.getMessage()).contains(forbidden);
                    });
        }
        verify(batchMapper, never()).insert(any(Batch.class));

        // 非法 originType
        BatchCreateRequest reqInvalidOriginType = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "UNKNOWN_ORIGIN", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidOriginType, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 非法 unitCode (非 kg)
        BatchCreateRequest reqInvalidUnit = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "g",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidUnit, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 数量 <= 0
        BatchCreateRequest reqZeroQty = new BatchCreateRequest(
                "EXT-001", 500L, BigDecimal.ZERO, "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqZeroQty, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 数量小数位超过 3 位
        BatchCreateRequest reqScaleQty = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.1234"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqScaleQty, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 保质期天数 <= 0
        BatchCreateRequest reqInvalidShelfLife = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, 0
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidShelfLife, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("创建批次草稿失败 - 关联产品不存在 404 / 关联产品非 ACTIVE 422")
    void createDraftBatch_ProductValidation() {
        BatchCreateRequest reqNotFound = new BatchCreateRequest(
                "EXT-001", 999L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> batchService.createDraftBatch(reqNotFound, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        BatchCreateRequest reqInactive = new BatchCreateRequest(
                "EXT-002", 501L, new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(501L)).thenReturn(inactiveProduct);

        assertThatThrownBy(() -> batchService.createDraftBatch(reqInactive, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("PRODUCT_NOT_ACTIVE");
                });
    }

    @Test
    @DisplayName("创建批次草稿 - 幂等重试同语义返回原批次；不同 externalBatchNo 或载荷报 409 IDEMPOTENCY_KEY_REUSED")
    void createDraftBatch_IdempotencyScenarios() {
        Batch existing = new Batch();
        existing.setId(777L);
        existing.setOrgId(10L);
        existing.setProductId(500L);
        existing.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        existing.setExternalBatchNo("EXT-ORIGINAL");
        existing.setBatchType("SOURCE");
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setUnitCode("kg");
        existing.setOriginType("DOMESTIC_CAPTURE");
        existing.setOriginText("舟山海域");
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);
        existing.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);

        // 1. 相同语义重试 -> 返回原批次
        BatchCreateRequest sameReq = new BatchCreateRequest(
                "EXT-ORIGINAL", 500L, new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        BatchResponse resp = batchService.createDraftBatch(sameReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp.id()).isEqualTo(777L);
        assertThat(resp.traceBatchNo()).isEqualTo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        assertThat(resp.externalBatchNo()).isEqualTo("EXT-ORIGINAL");
        verify(batchMapper, never()).insert(any(Batch.class));

        // 2. externalBatchNo 变更 -> 报 IDEMPOTENCY_KEY_REUSED
        BatchCreateRequest diffExternalReq = new BatchCreateRequest(
                "EXT-CHANGED", 500L, new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(diffExternalReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        // 3. 数量不同 -> 报 IDEMPOTENCY_KEY_REUSED
        BatchCreateRequest diffQtyReq = new BatchCreateRequest(
                "EXT-ORIGINAL", 500L, new BigDecimal("99.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(diffQtyReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    @DisplayName("创建批次草稿 - 幂等重放优先于产品状态检查，即使关联产品事后变为 INACTIVE 仍可安全重放")
    void createDraftBatch_IdempotencyReplayEvenIfProductBecameInactive() {
        Batch existing = new Batch();
        existing.setId(777L);
        existing.setOrgId(10L);
        existing.setProductId(501L); // 关联已停用产品
        existing.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        existing.setExternalBatchNo("EXT-INA-001");
        existing.setBatchType("SOURCE");
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setUnitCode("kg");
        existing.setOriginType("DOMESTIC_CAPTURE");
        existing.setOriginText("舟山海域");
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);
        existing.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);

        // 相同载荷重放 -> 即使产品停用也应成功重放，不查产品锁，不报 422
        BatchCreateRequest sameReq = new BatchCreateRequest(
                "EXT-INA-001", 501L, new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        BatchResponse resp = batchService.createDraftBatch(sameReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp).isNotNull();
        assertThat(resp.id()).isEqualTo(777L);
        assertThat(resp.productId()).isEqualTo(501L);
        verify(productMapper, never()).selectByIdForUpdate(anyLong());

        // 不同载荷 -> 优先报 409 IDEMPOTENCY_KEY_REUSED，而非 422 PRODUCT_NOT_ACTIVE
        BatchCreateRequest diffReq = new BatchCreateRequest(
                "EXT-INA-001", 501L, new BigDecimal("60.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(diffReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    @DisplayName("创建批次草稿 - 并发 DuplicateKey 竞态安全恢复（同语义重放成功，不同语义报 IDEMPOTENCY_KEY_REUSED，非幂等键冲突报 DATA_CONFLICT）")
    void createDraftBatch_DuplicateKeyHandling() {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);

        // 模拟并发插入触发 DuplicateKeyException
        when(batchMapper.insert(any(Batch.class))).thenThrow(new DuplicateKeyException("Duplicate entry"));

        // 1. 并发当前读重查返回已插入的同语义批次 -> 成功重放
        Batch concurrentInserted = new Batch();
        concurrentInserted.setId(888L);
        concurrentInserted.setOrgId(10L);
        concurrentInserted.setProductId(500L);
        concurrentInserted.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        concurrentInserted.setExternalBatchNo("EXT-001");
        concurrentInserted.setBatchType("SOURCE");
        concurrentInserted.setQuantity(new BigDecimal("50.000"));
        concurrentInserted.setUnitCode("kg");
        concurrentInserted.setOriginType("DOMESTIC_CAPTURE");
        concurrentInserted.setOriginText("舟山海域");
        concurrentInserted.setFlowStatus("DRAFT");
        concurrentInserted.setRiskStatus("NORMAL");
        concurrentInserted.setVersion(0L);
        concurrentInserted.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByCreationOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY))
                .thenReturn(concurrentInserted);

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp.id()).isEqualTo(888L);
        verify(batchMapper).selectByCreationOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY);

        // 2. 并发当前读查出同 key 但不同载荷 -> 409 IDEMPOTENCY_KEY_REUSED
        Batch diffConcurrent = new Batch();
        diffConcurrent.setId(888L);
        diffConcurrent.setOrgId(10L);
        diffConcurrent.setProductId(500L);
        diffConcurrent.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        diffConcurrent.setExternalBatchNo("EXT-DIFFERENT");
        diffConcurrent.setBatchType("SOURCE");
        diffConcurrent.setQuantity(new BigDecimal("50.000"));
        diffConcurrent.setUnitCode("kg");
        diffConcurrent.setOriginType("DOMESTIC_CAPTURE");
        diffConcurrent.setOriginText("舟山海域");
        diffConcurrent.setFlowStatus("DRAFT");
        diffConcurrent.setRiskStatus("NORMAL");
        diffConcurrent.setVersion(0L);
        diffConcurrent.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByCreationOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY))
                .thenReturn(diffConcurrent);

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        // 3. 并发当前读查幂等键为 null（即非幂等键冲突，如 trace_batch_no 偶发碰撞） -> 409 DATA_CONFLICT
        when(batchMapper.selectByCreationOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY))
                .thenReturn(null);

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("DATA_CONFLICT");
                });
    }

    @Test
    @DisplayName("更新批次草稿成功 - 仅允许 DRAFT+NORMAL，可更新 externalBatchNo 与可变字段，绝不能修改 traceBatchNo")
    void patchDraftBatch_Success() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        existing.setExternalBatchNo("EXT-OLD");
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setOriginText("旧原产地描述");

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(1);

        Batch updated = new Batch();
        updated.setId(100L);
        updated.setOrgId(10L);
        updated.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        updated.setExternalBatchNo("EXT-NEW");
        updated.setFlowStatus("DRAFT");
        updated.setRiskStatus("NORMAL");
        updated.setVersion(1L);
        updated.setQuantity(new BigDecimal("60.000"));
        updated.setOriginText("更新后原产地描述");
        when(batchMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(updated);

        BatchPatchRequest patchReq = new BatchPatchRequest(
                0L,
                new BigDecimal("60.000"),
                "EXT-NEW",
                "更新后原产地描述",
                LocalDate.of(2026, 9, 5),
                null, null, 90
        );

        BatchResponse resp = batchService.patchDraftBatch(100L, patchReq, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.version()).isEqualTo(1L);
        assertThat(resp.traceBatchNo()).isEqualTo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        assertThat(resp.externalBatchNo()).isEqualTo("EXT-NEW");
        assertThat(resp.flowStatus()).isEqualTo("DRAFT");
        assertThat(resp.riskStatus()).isEqualTo("NORMAL");
        verify(batchMapper).updateDraftBatch(any(Batch.class), eq(0L));
    }

    @Test
    @DisplayName("更新批次草稿前置失败 - 不存在 404 / 跨组织 403 / 非 DRAFT+NORMAL 状态 409 / 版本冲突 409")
    void patchDraftBatch_PreconditionFailures() {
        // 1. 批次不存在 -> 404
        when(batchMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);
        BatchPatchRequest patchReq = new BatchPatchRequest(0L, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(999L, patchReq, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        // 2. 跨组织修改 -> 403 ORG_SCOPE_DENIED
        Batch existingOrg20 = new Batch();
        existingOrg20.setId(100L);
        existingOrg20.setOrgId(20L);
        existingOrg20.setFlowStatus("DRAFT");
        existingOrg20.setRiskStatus("NORMAL");
        existingOrg20.setVersion(0L);
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existingOrg20);

        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        // 3. 非法状态 (ACTIVE+NORMAL, CLOSED+NORMAL, ACTIVE+FROZEN 等) -> 409 INVALID_STATE_TRANSITION
        Batch existingActive = new Batch();
        existingActive.setId(100L);
        existingActive.setOrgId(10L);
        existingActive.setFlowStatus("ACTIVE");
        existingActive.setRiskStatus("NORMAL");
        existingActive.setVersion(0L);
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existingActive);

        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));

        // 4. 版本不一致 -> 409 VERSION_CONFLICT
        Batch existingDraft = new Batch();
        existingDraft.setId(100L);
        existingDraft.setOrgId(10L);
        existingDraft.setFlowStatus("DRAFT");
        existingDraft.setRiskStatus("NORMAL");
        existingDraft.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existingDraft);

        BatchPatchRequest oldVersionReq = new BatchPatchRequest(0L, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, oldVersionReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("更新批次草稿失败 - 处于 FROZEN/RECALLED/ACTIVE 等非 DRAFT+NORMAL 状态一律报 409 INVALID_STATE_TRANSITION")
    void patchDraftBatch_RejectNonDraftOrNonNormal() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setVersion(0L);

        // 1. ACTIVE + NORMAL -> 拒绝
        existing.setFlowStatus("ACTIVE");
        existing.setRiskStatus("NORMAL");
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));

        // 2. CLOSED + RECALLED -> 拒绝
        existing.setFlowStatus("CLOSED");
        existing.setRiskStatus("RECALLED");
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("更新批次草稿 - update 返回 0 且当前锁定读状态已变为 ACTIVE 或 FROZEN 时，精准报 409 INVALID_STATE_TRANSITION")
    void patchDraftBatch_AffectedRowsZero_StatusChanged_ThrowsInvalidStateTransition() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(0);

        // 当前锁定读重查：并发事务已将状态流转为 ACTIVE
        Batch concurrentActive = new Batch();
        concurrentActive.setId(100L);
        concurrentActive.setOrgId(10L);
        concurrentActive.setFlowStatus("ACTIVE");
        concurrentActive.setRiskStatus("NORMAL");
        concurrentActive.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentActive);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("60.000"), null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                });

        verify(batchMapper).selectByIdIgnoreTenantForUpdate(100L);
    }

    @Test
    @DisplayName("更新批次草稿 - update 返回 0 且当前锁定读仍为 DRAFT+NORMAL 但版本递增时，精准报 409 VERSION_CONFLICT")
    void patchDraftBatch_AffectedRowsZero_VersionMismatch_ThrowsVersionConflict() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(0);

        // 当前锁定读重查：状态仍为 DRAFT+NORMAL，但版本号已被并发线程修改为 1
        Batch concurrentUpdated = new Batch();
        concurrentUpdated.setId(100L);
        concurrentUpdated.setOrgId(10L);
        concurrentUpdated.setFlowStatus("DRAFT");
        concurrentUpdated.setRiskStatus("NORMAL");
        concurrentUpdated.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentUpdated);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("60.000"), null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("VERSION_CONFLICT");
                });

        verify(batchMapper).selectByIdIgnoreTenantForUpdate(100L);
    }

    @Test
    @DisplayName("更新批次草稿 - update 返回 0 且当前锁定读显示跨组织时，精准报 403 ORG_SCOPE_DENIED；被物理删除时报 404")
    void patchDraftBatch_AffectedRowsZero_CrossOrgAndNotFound() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setFlowStatus("DRAFT");
        existing.setRiskStatus("NORMAL");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(0);

        // 1. 并发锁定读查已被迁移/变更为其他组织 -> 403 ORG_SCOPE_DENIED
        Batch concurrentOtherOrg = new Batch();
        concurrentOtherOrg.setId(100L);
        concurrentOtherOrg.setOrgId(20L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentOtherOrg);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("60.000"), null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        // 2. 并发锁定读查为 null (被物理删除) -> 404 RESOURCE_NOT_FOUND
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** 构造本组织创建、仍由本组织负责、来源字段完整的 SOURCE 草稿批次。 */
    private Batch sourceDraft(Long id, Long orgId, Long productId, long version) {
        Batch b = new Batch();
        b.setId(id);
        b.setOrgId(orgId);
        b.setCreationOrgId(orgId);
        b.setProductId(productId);
        b.setTraceBatchNo("TB-ABCDEFGHJKLMNPQRSTUVWXYZ23");
        b.setExternalBatchNo("EXT-001");
        b.setBatchType("SOURCE");
        b.setQuantity(new BigDecimal("1000.000"));
        b.setUnitCode("kg");
        b.setOriginType("DOMESTIC_CAPTURE");
        b.setOriginText("东海舟山渔场");
        b.setFlowStatus("DRAFT");
        b.setRiskStatus("NORMAL");
        b.setVersion(version);
        return b;
    }

    @Test
    @DisplayName("提交来源批次成功 - DRAFT+NORMAL 原子流转为 ACTIVE+NORMAL，版本自增，并在同一事务内生成 SOURCE 事件")
    void submitDraftBatch_Success() {
        Batch existing = sourceDraft(100L, 10L, 500L, 0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(1);

        Batch submitted = sourceDraft(100L, 10L, 500L, 1L);
        submitted.setFlowStatus("ACTIVE");
        when(batchMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(submitted);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        BatchResponse resp = batchService.submitDraftBatch(100L, submitReq, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.flowStatus()).isEqualTo("ACTIVE");
        assertThat(resp.riskStatus()).isEqualTo("NORMAL");
        assertThat(resp.version()).isEqualTo(1L);
        verify(batchMapper).submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L));
        verify(productMapper).selectByIdForUpdate(500L);

        // SOURCE 事件使用提交后的最新批次行、当前提交人和与批次更新相同的激活时刻
        ArgumentCaptor<LocalDateTime> submitTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchMapper).submitDraftBatch(eq(100L), eq(10L), eq(0L), submitTime.capture(), eq(101L));
        verify(traceEventApplicationService).appendSourceEvent(submitted, 101L, submitTime.getValue());
    }

    @Test
    @DisplayName("提交来源批次 - SOURCE 事件写入失败时异常向上传播（由事务回滚批次激活）")
    void submitDraftBatch_SourceEventFailurePropagates() {
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 0L));
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(1);
        Batch submitted = sourceDraft(100L, 10L, 500L, 1L);
        submitted.setFlowStatus("ACTIVE");
        when(batchMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(submitted);
        doThrow(new BusinessException(HttpStatus.CONFLICT, "SOURCE_EVENT_CONFLICT", "来源事件冲突", "冲突"))
                .when(traceEventApplicationService).appendSourceEvent(any(), anyLong(), any());

        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, new BatchSubmitRequest(0L), operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("SOURCE_EVENT_CONFLICT"));
    }

    @Test
    @DisplayName("提交来源批次前置失败 - 非来源组织 / 非 OPERATOR 一律 403，且不读取批次")
    void submitDraftBatch_RejectsNonSourceOrgAndNonOperator() {
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, new BatchSubmitRequest(0L), processorOperatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_TYPE_NOT_ALLOWED");
                });
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, new BatchSubmitRequest(0L), sourceViewerPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(batchMapper, never()).selectByIdIgnoreTenant(anyLong());
        verify(traceEventApplicationService, never()).appendSourceEvent(any(), anyLong(), any());
    }

    @Test
    @DisplayName("提交来源批次前置失败 - 非 SOURCE 类型 422 / 来源字段不完整 422 / 责任组织已不是创建组织 403")
    void submitDraftBatch_RejectsNonSourceTypeIncompleteOriginAndForeignCreator() {
        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);

        Batch processing = sourceDraft(100L, 10L, 500L, 0L);
        processing.setBatchType("PROCESSING");
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(processing);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("BATCH_TYPE_NOT_SUBMITTABLE");
                });

        Batch incomplete = sourceDraft(100L, 10L, 500L, 0L);
        incomplete.setOriginText("  ");
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(incomplete);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("SOURCE_FIELDS_INCOMPLETE"));

        Batch foreignCreator = sourceDraft(100L, 10L, 500L, 0L);
        foreignCreator.setCreationOrgId(99L);
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(foreignCreator);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        verify(batchMapper, never()).submitDraftBatch(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(traceEventApplicationService, never()).appendSourceEvent(any(), anyLong(), any());
    }

    @Test
    @DisplayName("提交批次草稿前置失败 - 不存在 404 / 跨组织 403 / 非 DRAFT+NORMAL 409 / 版本冲突 409 / 产品停用 422")
    void submitDraftBatch_PreconditionFailures() {
        // 1. 批次不存在 -> 404
        when(batchMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);
        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        assertThatThrownBy(() -> batchService.submitDraftBatch(999L, submitReq, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        // 2. 跨组织越权提交 -> 403 ORG_SCOPE_DENIED
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 20L, 500L, 0L));

        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        // 3. 版本不一致 -> 409 VERSION_CONFLICT
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 1L));

        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("VERSION_CONFLICT"));

        // 4. 关联产品停用 -> 422 PRODUCT_NOT_ACTIVE
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 501L, 0L));
        when(productMapper.selectByIdForUpdate(501L)).thenReturn(inactiveProduct);

        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("PRODUCT_NOT_ACTIVE");
                });
        verify(traceEventApplicationService, never()).appendSourceEvent(any(), anyLong(), any());
    }

    @Test
    @DisplayName("提交批次草稿失败 - ACTIVE/FROZEN/RECALLED 等非 DRAFT+NORMAL 状态一律报 409 INVALID_STATE_TRANSITION")
    void submitDraftBatch_RejectsNonDraftNormal() {
        Batch existing = sourceDraft(100L, 10L, 500L, 0L);

        // ACTIVE + NORMAL -> 409
        existing.setFlowStatus("ACTIVE");
        existing.setRiskStatus("NORMAL");
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));

        // ACTIVE + FROZEN -> 409
        existing.setFlowStatus("ACTIVE");
        existing.setRiskStatus("FROZEN");
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("提交批次草稿 - submit 返回 0 且当前锁定读状态已变为 ACTIVE 时，精准报 409 INVALID_STATE_TRANSITION，且不生成 SOURCE")
    void submitDraftBatch_AffectedRowsZero_StatusChanged_ThrowsInvalidStateTransition() {
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 0L));
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(0);

        // 当前锁定读重查：并发线程已完成提交流转为 ACTIVE
        Batch concurrentSubmitted = sourceDraft(100L, 10L, 500L, 1L);
        concurrentSubmitted.setFlowStatus("ACTIVE");
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentSubmitted);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                });

        verify(batchMapper).selectByIdIgnoreTenantForUpdate(100L);
        verify(traceEventApplicationService, never()).appendSourceEvent(any(), anyLong(), any());
    }

    @Test
    @DisplayName("提交批次草稿 - submit 返回 0 且当前锁定读仍为 DRAFT+NORMAL 但版本递增时，精准报 409 VERSION_CONFLICT")
    void submitDraftBatch_AffectedRowsZero_VersionMismatch_ThrowsVersionConflict() {
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 0L));
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(0);

        // 当前锁定读重查：状态仍为 DRAFT+NORMAL，但版本已被并发线程更新
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 1L));

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("VERSION_CONFLICT");
                });

        verify(batchMapper).selectByIdIgnoreTenantForUpdate(100L);
    }

    @Test
    @DisplayName("提交批次草稿 - submit 返回 0 且当前锁定读显示跨组织时，精准报 403 ORG_SCOPE_DENIED；被物理删除时报 404")
    void submitDraftBatch_AffectedRowsZero_CrossOrgAndNotFound() {
        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(sourceDraft(100L, 10L, 500L, 0L));
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(0);

        // 1. 并发锁定读查已被修改为其他组织 -> 403 ORG_SCOPE_DENIED
        Batch concurrentOtherOrg = new Batch();
        concurrentOtherOrg.setId(100L);
        concurrentOtherOrg.setOrgId(20L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentOtherOrg);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        // 2. 并发锁定读查为 null -> 404 RESOURCE_NOT_FOUND
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(null);
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("详情查询 - 存在但跨组织访问 403 ORG_SCOPE_DENIED；不存在 404 RESOURCE_NOT_FOUND；PLATFORM scope 可跨组织查看")
    void getBatchById_IsolationAndNotFound() {
        when(batchMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);

        // 1. 批次不存在 -> 404
        assertThatThrownBy(() -> batchService.getBatchById(999L, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        // 2. 批次属于组织 20，组织 10 用户访问 -> 403 ORG_SCOPE_DENIED
        Batch batchOrg20 = new Batch();
        batchOrg20.setId(200L);
        batchOrg20.setOrgId(20L);
        batchOrg20.setFlowStatus("ACTIVE");
        batchOrg20.setRiskStatus("NORMAL");
        batchOrg20.setTraceBatchNo("TB-XYZABC1234567890ABCDEFGH");
        batchOrg20.setExternalBatchNo("EXT-BATCH-200");
        when(batchMapper.selectByIdIgnoreTenant(200L)).thenReturn(batchOrg20);

        assertThatThrownBy(() -> batchService.getBatchById(200L, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });

        // 3. 平台管理员 PLATFORM scope 可以跨组织查看
        BatchResponse adminResp = batchService.getBatchById(200L, adminPrincipal);
        assertThat(adminResp).isNotNull();
        assertThat(adminResp.id()).isEqualTo(200L);
        assertThat(adminResp.orgId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("详情查询 - 验证双状态与双编号白名单输出")
    void getBatchById_DualFields() {
        Batch batch = new Batch();
        batch.setId(200L);
        batch.setOrgId(10L);
        batch.setProductId(500L);
        batch.setTraceBatchNo("TB-XYZABC1234567890ABCDEFGH");
        batch.setExternalBatchNo("EXT-BATCH-200");
        batch.setFlowStatus("ACTIVE");
        batch.setRiskStatus("NORMAL");
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("100.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("舟山海域");
        batch.setVersion(1L);

        when(batchMapper.selectByIdIgnoreTenant(200L)).thenReturn(batch);

        BatchResponse resp = batchService.getBatchById(200L, operatorPrincipal);
        assertThat(resp).isNotNull();
        assertThat(resp.traceBatchNo()).isEqualTo("TB-XYZABC1234567890ABCDEFGH");
        assertThat(resp.externalBatchNo()).isEqualTo("EXT-BATCH-200");
        assertThat(resp.flowStatus()).isEqualTo("ACTIVE");
        assertThat(resp.riskStatus()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("分页查询 - 普通用户在 Mapper 层强制限定 orgId，PLATFORM 查全平台")
    void listBatches_ScopeIsolation() {
        BatchQueryCriteria criteria = new BatchQueryCriteria(null, null, "DRAFT", "NORMAL", 1, 10);

        when(batchMapper.countBatches(eq(10L), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"))).thenReturn(1L);
        when(batchMapper.selectBatchesPage(eq(10L), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"), eq(0L), eq(10)))
                .thenReturn(List.of(new Batch()));

        SuccessEnvelope<List<BatchResponse>> operatorResult = batchService.listBatches(criteria, operatorPrincipal);
        assertThat(operatorResult.data()).hasSize(1);
        verify(batchMapper).countBatches(eq(10L), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"));

        when(batchMapper.countBatches(eq(null), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"))).thenReturn(5L);
        when(batchMapper.selectBatchesPage(eq(null), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"), eq(0L), eq(10)))
                .thenReturn(List.of(new Batch(), new Batch()));

        SuccessEnvelope<List<BatchResponse>> adminResult = batchService.listBatches(criteria, adminPrincipal);
        assertThat(adminResult.data()).hasSize(2);
        verify(batchMapper).countBatches(eq(null), eq(null), eq(null), eq("DRAFT"), eq("NORMAL"));
    }

    @Test
    @DisplayName("分页查询 - 支持按双状态和双编号条件过滤")
    void listBatches_DualCriteriaFiltering() {
        BatchQueryCriteria criteria = new BatchQueryCriteria("TB-ABC", "EXT-123", "ACTIVE", "NORMAL", 1, 10);

        when(batchMapper.countBatches(eq(10L), eq("TB-ABC"), eq("EXT-123"), eq("ACTIVE"), eq("NORMAL"))).thenReturn(1L);
        when(batchMapper.selectBatchesPage(eq(10L), eq("TB-ABC"), eq("EXT-123"), eq("ACTIVE"), eq("NORMAL"), eq(0L), eq(10)))
                .thenReturn(List.of(new Batch()));

        SuccessEnvelope<List<BatchResponse>> result = batchService.listBatches(criteria, operatorPrincipal);
        assertThat(result.data()).hasSize(1);
        verify(batchMapper).countBatches(eq(10L), eq("TB-ABC"), eq("EXT-123"), eq("ACTIVE"), eq("NORMAL"));
    }
}
