package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.domain.BatchType;
import com.example.traceability.batch.domain.OriginType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("追溯批次应用服务业务逻辑单元测试")
class BatchApplicationServiceTest {

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private BatchApplicationService batchService;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal adminPrincipal;
    private TraceSecurityPrincipal otherOrgOperatorPrincipal;

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
    @DisplayName("创建批次草稿成功 - 完整合法参数与审计字段自动填充")
    void createDraftBatch_Success() {
        BatchCreateRequest req = new BatchCreateRequest(
                "BATCH-20260908-001",
                500L,
                "SOURCE",
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
        when(batchMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        when(batchMapper.selectByOrgIdAndBatchNo(10L, "BATCH-20260908-001")).thenReturn(null);
        when(batchMapper.insert(any(Batch.class))).thenAnswer(invocation -> {
            Batch b = invocation.getArgument(0);
            b.setId(1000L);
            return 1;
        });

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.id()).isEqualTo(1000L);
        assertThat(resp.orgId()).isEqualTo(10L);
        assertThat(resp.batchNo()).isEqualTo("BATCH-20260908-001");
        assertThat(resp.batchType()).isEqualTo("SOURCE");
        assertThat(resp.quantity()).isEqualByComparingTo("100.500");
        assertThat(resp.unitCode()).isEqualTo("kg");
        assertThat(resp.originType()).isEqualTo("DOMESTIC_CAPTURE");
        assertThat(resp.originText()).isEqualTo("东海舟山渔场3号海域");
        assertThat(resp.status()).isEqualTo("DRAFT");
        assertThat(resp.version()).isEqualTo(0L);
        assertThat(resp.createdBy()).isEqualTo(101L);
        assertThat(resp.updatedBy()).isEqualTo(101L);
        assertThat(resp.shelfLifeDays()).isEqualTo(180);

        verify(batchMapper).insert(any(Batch.class));
        verify(productMapper).selectByIdForUpdate(500L);
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("创建批次草稿失败 - 非 OPERATOR 角色抛出 403 ACCESS_DENIED (系统管理员亦无企业写权限)")
    void createDraftBatch_DeniedForNonOperator() {
        BatchCreateRequest req = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, adminPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });

        verify(batchMapper, never()).insert(any(Batch.class));
    }

    @Test
    @DisplayName("创建批次草稿失败 - 幂等键缺失或长度不足 16 位返回 400 INVALID_REQUEST")
    void createDraftBatch_InvalidIdempotencyKey() {
        BatchCreateRequest req = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        assertThatThrownBy(() -> batchService.createDraftBatch(req, "short-key", operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });

        assertThatThrownBy(() -> batchService.createDraftBatch(req, null, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("创建批次草稿失败 - 枚举非法或单位非 kg 返回 400 INVALID_REQUEST")
    void createDraftBatch_InvalidEnumsOrUnit() {
        // 非法 batchType
        BatchCreateRequest reqInvalidBatchType = new BatchCreateRequest(
                "BATCH-001", 500L, "UNKNOWN_TYPE", new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidBatchType, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 非法 originType
        BatchCreateRequest reqInvalidOriginType = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("10.000"), "kg",
                "UNKNOWN_ORIGIN", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidOriginType, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 非法 unitCode (非 kg)
        BatchCreateRequest reqInvalidUnit = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("10.000"), "g",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidUnit, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 数量 <= 0
        BatchCreateRequest reqZeroQty = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", BigDecimal.ZERO, "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqZeroQty, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));

        // 保质期天数 <= 0
        BatchCreateRequest reqInvalidShelfLife = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, 0
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(reqInvalidShelfLife, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("创建批次草稿失败 - 关联产品不存在 404 或非 ACTIVE 状态 422 PRODUCT_NOT_ACTIVE")
    void createDraftBatch_ProductValidation() {
        BatchCreateRequest reqNotFound = new BatchCreateRequest(
                "BATCH-001", 999L, "SOURCE", new BigDecimal("10.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> batchService.createDraftBatch(reqNotFound, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        BatchCreateRequest reqInactive = new BatchCreateRequest(
                "BATCH-002", 501L, "SOURCE", new BigDecimal("10.000"), "kg",
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
    @DisplayName("创建批次草稿 - 幂等重试同语义返回原批次；不同语义抛出 409 IDEMPOTENCY_CONFLICT")
    void createDraftBatch_IdempotencyScenarios() {
        Batch existing = new Batch();
        existing.setId(777L);
        existing.setOrgId(10L);
        existing.setProductId(500L);
        existing.setBatchNo("BATCH-001");
        existing.setBatchType("SOURCE");
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setUnitCode("kg");
        existing.setOriginType("DOMESTIC_CAPTURE");
        existing.setOriginText("舟山海域");
        existing.setStatus("DRAFT");
        existing.setVersion(0L);
        existing.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        // 幂等预检在产品校验之前，相同语义命中时无需查产品
        when(batchMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);

        // 1. 相同语义重试
        BatchCreateRequest sameReq = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        BatchResponse resp = batchService.createDraftBatch(sameReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp.id()).isEqualTo(777L);
        verify(batchMapper, never()).insert(any(Batch.class));
        verify(productMapper, never()).selectById(anyLong());

        // 2. 不同语义冲突 (数量不同)
        BatchCreateRequest differentReq = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("99.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(differentReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("创建批次草稿 - 即使产品在创建后变为 INACTIVE，相同幂等键及归一化载荷仍成功重放原批次；不同载荷仍报 409")
    void createDraftBatch_IdempotencyReplayEvenIfProductBecameInactive() {
        Batch existing = new Batch();
        existing.setId(777L);
        existing.setOrgId(10L);
        existing.setProductId(501L); // 关联已停用产品
        existing.setBatchNo("BATCH-INA-001");
        existing.setBatchType("SOURCE");
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setUnitCode("kg");
        existing.setOriginType("DOMESTIC_CAPTURE");
        existing.setOriginText("舟山海域");
        existing.setStatus("DRAFT");
        existing.setVersion(0L);
        existing.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        when(batchMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(existing);

        // 相同载荷重放 -> 即使产品停用也应成功重放，不抛 422
        BatchCreateRequest sameReq = new BatchCreateRequest(
                "BATCH-INA-001", 501L, "SOURCE", new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        BatchResponse resp = batchService.createDraftBatch(sameReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp).isNotNull();
        assertThat(resp.id()).isEqualTo(777L);
        assertThat(resp.productId()).isEqualTo(501L);
        verify(productMapper, never()).selectById(anyLong());

        // 不同载荷 -> 优先抛出 409 IDEMPOTENCY_CONFLICT，而非 422 PRODUCT_NOT_ACTIVE
        BatchCreateRequest diffReq = new BatchCreateRequest(
                "BATCH-INA-001", 501L, "SOURCE", new BigDecimal("60.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        assertThatThrownBy(() -> batchService.createDraftBatch(diffReq, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("创建批次草稿 - 并发 DuplicateKey 竞态安全恢复（验证使用当前锁定读穿透快照读）")
    void createDraftBatch_DuplicateKeyHandling() {
        BatchCreateRequest req = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        when(batchMapper.selectByOrgIdAndBatchNo(10L, "BATCH-001")).thenReturn(null);

        // 模拟并发插入触发 DuplicateKeyException
        when(batchMapper.insert(any(Batch.class))).thenThrow(new DuplicateKeyException("Duplicate entry"));

        // 并发当前读重查返回已插入的同语义批次
        Batch concurrentInserted = new Batch();
        concurrentInserted.setId(888L);
        concurrentInserted.setOrgId(10L);
        concurrentInserted.setProductId(500L);
        concurrentInserted.setBatchNo("BATCH-001");
        concurrentInserted.setBatchType("SOURCE");
        concurrentInserted.setQuantity(new BigDecimal("50.000"));
        concurrentInserted.setUnitCode("kg");
        concurrentInserted.setOriginType("DOMESTIC_CAPTURE");
        concurrentInserted.setOriginText("舟山海域");
        concurrentInserted.setStatus("DRAFT");
        concurrentInserted.setVersion(0L);
        concurrentInserted.setCreationIdempotencyKey(VALID_IDEMPOTENCY_KEY);

        // 关键断言准备：DuplicateKey 后必须使用当前锁定读 selectByOrgIdAndIdempotencyKeyForUpdate
        when(batchMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY))
                .thenReturn(concurrentInserted);

        BatchResponse resp = batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal);
        assertThat(resp.id()).isEqualTo(888L);
        verify(batchMapper).selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("创建批次草稿 - 并发 DuplicateKey 且非幂等键冲突时，当前锁定读查出批次号冲突 409 BATCH_NO_CONFLICT")
    void createDraftBatch_DuplicateKey_BatchNoConflict() {
        BatchCreateRequest req = new BatchCreateRequest(
                "BATCH-001", 500L, "SOURCE", new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山海域", null, null, null, null
        );
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.selectByOrgIdAndIdempotencyKey(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        when(batchMapper.selectByOrgIdAndBatchNo(10L, "BATCH-001")).thenReturn(null);
        when(batchMapper.insert(any(Batch.class))).thenThrow(new DuplicateKeyException("Duplicate entry"));

        // 当前锁定读查幂等键为 null，但查 batchNo 存在冲突批次
        when(batchMapper.selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY)).thenReturn(null);
        Batch conflictBatchNo = new Batch();
        conflictBatchNo.setId(999L);
        conflictBatchNo.setBatchNo("BATCH-001");
        when(batchMapper.selectByOrgIdAndBatchNoForUpdate(10L, "BATCH-001")).thenReturn(conflictBatchNo);

        assertThatThrownBy(() -> batchService.createDraftBatch(req, VALID_IDEMPOTENCY_KEY, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_NO_CONFLICT");
                });

        verify(batchMapper).selectByOrgIdAndIdempotencyKeyForUpdate(10L, VALID_IDEMPOTENCY_KEY);
        verify(batchMapper).selectByOrgIdAndBatchNoForUpdate(10L, "BATCH-001");
    }

    @Test
    @DisplayName("更新批次草稿成功 - 仅修改可变字段且版本原子自增")
    void patchDraftBatch_Success() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setBatchNo("BATCH-001");
        existing.setStatus("DRAFT");
        existing.setVersion(0L);
        existing.setQuantity(new BigDecimal("50.000"));
        existing.setOriginText("旧原产地描述");

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(1);

        Batch updated = new Batch();
        updated.setId(100L);
        updated.setOrgId(10L);
        updated.setBatchNo("BATCH-001");
        updated.setStatus("DRAFT");
        updated.setVersion(1L);
        updated.setQuantity(new BigDecimal("60.000"));
        updated.setOriginText("更新后原产地描述");
        when(batchMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(updated);

        BatchPatchRequest patchReq = new BatchPatchRequest(
                0L,
                new BigDecimal("60.000"),
                "更新后原产地描述",
                LocalDate.of(2026, 9, 5),
                null, null, 90
        );

        BatchResponse resp = batchService.patchDraftBatch(100L, patchReq, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.version()).isEqualTo(1L);
        assertThat(resp.quantity()).isEqualByComparingTo("60.000");
        assertThat(resp.originText()).isEqualTo("更新后原产地描述");
        verify(batchMapper).updateDraftBatch(any(Batch.class), eq(0L));
    }

    @Test
    @DisplayName("更新批次草稿失败 - 跨组织访问 403 ORG_SCOPE_DENIED / 非 DRAFT 409 / 版本冲突 409")
    void patchDraftBatch_ConflictScenarios() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setStatus("DRAFT");
        existing.setVersion(1L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);

        // 1. 跨组织越权更新 -> 403
        BatchPatchRequest patchReq = new BatchPatchRequest(1L, null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, otherOrgOperatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ORG_SCOPE_DENIED"));

        // 2. 版本不一致 -> 409 VERSION_CONFLICT
        BatchPatchRequest oldVersionReq = new BatchPatchRequest(0L, null, null, null, null, null, null);
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, oldVersionReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("VERSION_CONFLICT"));

        // 3. 非 DRAFT 状态批次 -> 409 INVALID_STATE_TRANSITION
        existing.setStatus("ACTIVE");
        assertThatThrownBy(() -> batchService.patchDraftBatch(100L, patchReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("更新批次草稿 - update 返回 0 且当前锁定读状态已变为 ACTIVE 时，精准报 409 INVALID_STATE_TRANSITION")
    void patchDraftBatch_AffectedRowsZero_StatusChanged_ThrowsInvalidStateTransition() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(0);

        // 当前锁定读重查：并发事务已将状态流转为 ACTIVE
        Batch concurrentActive = new Batch();
        concurrentActive.setId(100L);
        concurrentActive.setOrgId(10L);
        concurrentActive.setStatus("ACTIVE");
        concurrentActive.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentActive);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("60.000"), null, null, null, null, null);
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
    @DisplayName("更新批次草稿 - update 返回 0 且当前锁定读仍为 DRAFT 但版本递增时，精准报 409 VERSION_CONFLICT")
    void patchDraftBatch_AffectedRowsZero_VersionMismatch_ThrowsVersionConflict() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(batchMapper.updateDraftBatch(any(Batch.class), eq(0L))).thenReturn(0);

        // 当前锁定读重查：状态仍为 DRAFT，但版本号已被并发线程修改为 1
        Batch concurrentUpdated = new Batch();
        concurrentUpdated.setId(100L);
        concurrentUpdated.setOrgId(10L);
        concurrentUpdated.setStatus("DRAFT");
        concurrentUpdated.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentUpdated);

        BatchPatchRequest patchReq = new BatchPatchRequest(0L, new BigDecimal("60.000"), null, null, null, null, null);
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
    @DisplayName("提交批次草稿成功 - DRAFT 流转为 ACTIVE 且版本自增（使用 selectByIdForUpdate 加产品排他锁）")
    void submitDraftBatch_Success() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setProductId(500L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(1);

        Batch submitted = new Batch();
        submitted.setId(100L);
        submitted.setOrgId(10L);
        submitted.setProductId(500L);
        submitted.setStatus("ACTIVE");
        submitted.setVersion(1L);
        when(batchMapper.selectByIdAndOrgId(100L, 10L)).thenReturn(submitted);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        BatchResponse resp = batchService.submitDraftBatch(100L, submitReq, operatorPrincipal);

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.version()).isEqualTo(1L);
        verify(batchMapper).submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L));
        verify(productMapper).selectByIdForUpdate(500L);
    }

    @Test
    @DisplayName("提交批次草稿失败 - 产品停用 422 PRODUCT_NOT_ACTIVE / 重复提交 409 INVALID_STATE_TRANSITION")
    void submitDraftBatch_FailureScenarios() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setProductId(501L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(productMapper.selectByIdForUpdate(501L)).thenReturn(inactiveProduct);

        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);

        // 关联产品停用 -> 422
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("PRODUCT_NOT_ACTIVE");
                });

        // 已经是非 DRAFT 状态 (例如 ACTIVE) -> 409 INVALID_STATE_TRANSITION
        existing.setStatus("ACTIVE");
        assertThatThrownBy(() -> batchService.submitDraftBatch(100L, submitReq, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("提交批次草稿 - submit 返回 0 且当前锁定读状态已变为 ACTIVE 时，精准报 409 INVALID_STATE_TRANSITION")
    void submitDraftBatch_AffectedRowsZero_StatusChanged_ThrowsInvalidStateTransition() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setProductId(500L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(0);

        // 当前锁定读重查：并发线程已完成提交流转为 ACTIVE
        Batch concurrentSubmitted = new Batch();
        concurrentSubmitted.setId(100L);
        concurrentSubmitted.setOrgId(10L);
        concurrentSubmitted.setStatus("ACTIVE");
        concurrentSubmitted.setVersion(1L);
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
    }

    @Test
    @DisplayName("提交批次草稿 - submit 返回 0 且当前锁定读仍为 DRAFT 但版本递增时，精准报 409 VERSION_CONFLICT")
    void submitDraftBatch_AffectedRowsZero_VersionMismatch_ThrowsVersionConflict() {
        Batch existing = new Batch();
        existing.setId(100L);
        existing.setOrgId(10L);
        existing.setProductId(500L);
        existing.setStatus("DRAFT");
        existing.setVersion(0L);

        when(batchMapper.selectByIdIgnoreTenant(100L)).thenReturn(existing);
        when(productMapper.selectByIdForUpdate(500L)).thenReturn(activeProduct);
        when(batchMapper.submitDraftBatch(eq(100L), eq(10L), eq(0L), any(LocalDateTime.class), eq(101L))).thenReturn(0);

        // 当前锁定读重查：状态仍为 DRAFT，但版本已被并发线程更新
        Batch concurrentUpdated = new Batch();
        concurrentUpdated.setId(100L);
        concurrentUpdated.setOrgId(10L);
        concurrentUpdated.setStatus("DRAFT");
        concurrentUpdated.setVersion(1L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(100L)).thenReturn(concurrentUpdated);

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
    @DisplayName("详情查询 - 存在但跨组织访问 403 ORG_SCOPE_DENIED；不存在 404 RESOURCE_NOT_FOUND")
    void getBatchById_IsolationAndNotFound() {
        when(batchMapper.selectByIdIgnoreTenant(999L)).thenReturn(null);

        // 1. 批次不存在 -> 404
        assertThatThrownBy(() -> batchService.getBatchById(999L, operatorPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);

        // 2. 批次属于组织 20，组织 10 用户访问 -> 403
        Batch batchOrg20 = new Batch();
        batchOrg20.setId(200L);
        batchOrg20.setOrgId(20L);
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
    }

    @Test
    @DisplayName("分页查询 - 普通用户在 Mapper 层强制限定 orgId，PLATFORM 查全平台")
    void listBatches_ScopeIsolation() {
        BatchQueryCriteria criteria = new BatchQueryCriteria("DRAFT", 1, 10);

        when(batchMapper.countBatches(eq(10L), eq("DRAFT"))).thenReturn(1L);
        when(batchMapper.selectBatchesPage(eq(10L), eq("DRAFT"), eq(0L), eq(10))).thenReturn(List.of(new Batch()));

        SuccessEnvelope<List<BatchResponse>> operatorResult = batchService.listBatches(criteria, operatorPrincipal);
        assertThat(operatorResult.data()).hasSize(1);
        verify(batchMapper).countBatches(eq(10L), eq("DRAFT"));

        when(batchMapper.countBatches(eq(null), eq("DRAFT"))).thenReturn(5L);
        when(batchMapper.selectBatchesPage(eq(null), eq("DRAFT"), eq(0L), eq(10))).thenReturn(List.of(new Batch(), new Batch()));

        SuccessEnvelope<List<BatchResponse>> adminResult = batchService.listBatches(criteria, adminPrincipal);
        assertThat(adminResult.data()).hasSize(2);
        verify(batchMapper).countBatches(eq(null), eq("DRAFT"));
    }
}
