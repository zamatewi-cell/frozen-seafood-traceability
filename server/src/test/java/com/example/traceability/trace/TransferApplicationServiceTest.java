package com.example.traceability.trace;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.application.TransferApplicationService;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferIdempotency;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferPatchRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import com.example.traceability.trace.dto.TransferResponse;
import com.example.traceability.trace.dto.TransferSubmitRequest;
import com.example.traceability.trace.mapper.TransferIdempotencyMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企业间整批交接应用服务单元契约测试 (TDD RED 目标测试)。
 * <p>
 * 验证核心业务规则：
 * 1. 创建仅限本组织 ACTIVE 批次，快照数量/单位，阻止已消耗批次，同组织幂等防重；
 * 2. 仅发送方 OPERATOR 可修改/删除/提交草稿；
 * 3. 仅接收方 OPERATOR / QUALITY_MANAGER 可接受/拒绝；
 * 4. 接受时差异数量必填 differenceReason，更新批次持有人（externalBatchNo 重复不再拦截接收），追加 ARRIVAL 事件与写审计；
 * 5. 拒绝时不转移持有人，不追加追溯事件，写审计；
 * 6. 跨组织数据隔离与权限越界拦截。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TransferApplicationServiceTest {

    @Mock
    private TransferMapper transferMapper;

    @Mock
    private TransferIdempotencyMapper idempotencyMapper;

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private BatchOperationItemMapper batchOperationItemMapper;

    @Mock
    private OrganizationMapper organizationMapper;

    @Mock
    private TraceEventApplicationService traceEventService;

    @Mock
    private AuditApplicationService auditService;

    @Mock
    private com.example.traceability.trace.mapper.PublicTraceCodeMapper publicTraceCodeMapper;

    private final tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();

    private TransferApplicationService transferService;

    private TraceSecurityPrincipal senderOperator;
    private TraceSecurityPrincipal senderQualityManager;
    private TraceSecurityPrincipal receiverOperator;
    private TraceSecurityPrincipal thirdPartyOperator;

    @BeforeEach
    void setUp() {
        transferService = new TransferApplicationService(
                transferMapper,
                idempotencyMapper,
                batchMapper,
                batchOperationItemMapper,
                organizationMapper,
                traceEventService,
                auditService,
                publicTraceCodeMapper,
                objectMapper
        );

        senderOperator = new TraceSecurityPrincipal(
                101L, "sender_op", "发货操作员", "hash",
                10L, "ORG-SENDER", "发送企业", "PROCESSOR",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );

        senderQualityManager = new TraceSecurityPrincipal(
                102L, "sender_qm", "发货质量员", "hash",
                10L, "ORG-SENDER", "发送企业", "PROCESSOR",
                List.of("QUALITY_MANAGER"), List.of("OWN_ORG"), true, true
        );

        receiverOperator = new TraceSecurityPrincipal(
                201L, "receiver_op", "接收操作员", "hash",
                20L, "ORG-RECEIVER", "接收企业", "WAREHOUSE",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );

        thirdPartyOperator = new TraceSecurityPrincipal(
                301L, "third_op", "第三方操作员", "hash",
                30L, "ORG-THIRD", "第三方企业", "DISTRIBUTOR",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );

        Organization receiverOrg = new Organization();
        receiverOrg.setId(20L);
        receiverOrg.setStatus("ACTIVE");
        receiverOrg.setIsDeleted(0);
        lenient().when(organizationMapper.selectById(20L)).thenReturn(receiverOrg);

        Organization senderOrg = new Organization();
        senderOrg.setId(10L);
        senderOrg.setStatus("ACTIVE");
        senderOrg.setIsDeleted(0);
        lenient().when(organizationMapper.selectById(10L)).thenReturn(senderOrg);
    }

    private Batch createTestBatch(Long batchId, Long orgId, String batchNo, String flowStatus, String riskStatus) {
        Batch batch = new Batch();
        batch.setId(batchId);
        batch.setOrgId(orgId);
        batch.setTraceBatchNo("TB-" + batchId);
        batch.setExternalBatchNo(batchNo);
        batch.setQuantity(new BigDecimal("500.000"));
        batch.setUnitCode("kg");
        batch.setFlowStatus(flowStatus);
        batch.setRiskStatus(riskStatus);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        return batch;
    }

    private Batch createTestBatch(Long batchId, Long orgId, String batchNo) {
        return createTestBatch(batchId, orgId, batchNo, BatchFlowStatus.ACTIVE.name(), BatchRiskStatus.NORMAL.name());
    }

    @Test
    @DisplayName("创建交接草稿：成功快照批次数量单位，保存DRAFT状态并记录审计")
    void createDraft_success() {
        Long batchId = 1001L;
        Long receiverOrgId = 20L;
        String idempotencyKey = "idem-create-0000001";

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-001");

        Organization receiverOrg = new Organization();
        receiverOrg.setId(receiverOrgId);
        receiverOrg.setStatus("ACTIVE");

        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(organizationMapper.selectById(receiverOrgId)).thenReturn(receiverOrg);
        when(transferMapper.countActiveTransfersByBatchId(batchId)).thenReturn(0);
        when(batchOperationItemMapper.countSubmittedInputUsageByBatchId(batchId)).thenReturn(0);
        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);
        when(transferMapper.insert(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer t = invocation.getArgument(0);
            t.setId(5001L);
            t.setVersion(0L);
            return 1;
        });

        TransferCreateRequest req = new TransferCreateRequest(batchId, receiverOrgId);
        TransferResponse resp = transferService.createDraft(req, idempotencyKey, senderOperator);

        assertThat(resp).isNotNull();
        assertThat(resp.batchId()).isEqualTo(batchId);
        assertThat(resp.senderOrgId()).isEqualTo(10L);
        assertThat(resp.receiverOrgId()).isEqualTo(receiverOrgId);
        assertThat(resp.quantity()).isEqualByComparingTo(new BigDecimal("500.000"));
        assertThat(resp.unitCode()).isEqualTo("kg");
        assertThat(resp.status()).isEqualTo(TransferStatus.DRAFT);

        verify(transferMapper).insert(any(Transfer.class));
        verify(idempotencyMapper).insert(any(TransferIdempotency.class));
        verify(auditService).recordAudit(eq(101L), eq(10L), eq("CREATE"), eq("TRANSFER"), any(), any(), eq("SUCCESS"), any());
    }

    @Test
    @DisplayName("创建交接草稿：非ACTIVE状态批次拒绝创建，抛出 409 BATCH_NOT_ACTIVE")
    void createDraft_nonActiveBatch_throwsException() {
        Long batchId = 1002L;
        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-002", BatchFlowStatus.DRAFT.name(), BatchRiskStatus.NORMAL.name());

        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);

        TransferCreateRequest req = new TransferCreateRequest(batchId, 20L);
        assertThatThrownBy(() -> transferService.createDraft(req, "idem-draft-0000002", senderOperator))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("BATCH_NOT_ACTIVE");
    }

    @Test
    @DisplayName("创建交接草稿：已被已提交批次操作消耗的批次拒绝创建，抛出 409 BATCH_ALREADY_CONSUMED")
    void createDraft_consumedBatch_throwsException() {
        Long batchId = 1003L;
        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-003");

        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(batchOperationItemMapper.countSubmittedInputUsageByBatchId(batchId)).thenReturn(1);

        TransferCreateRequest req = new TransferCreateRequest(batchId, 20L);
        assertThatThrownBy(() -> transferService.createDraft(req, "idem-draft-0000003", senderOperator))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("BATCH_ALREADY_CONSUMED");
    }

    @Test
    @DisplayName("提交交接：保存发货业务时间与提交系统时间，更新为PENDING状态")
    void submitTransfer_success() {
        Long transferId = 5002L;
        String idempotencyKey = "idem-submit-0000001";
        OffsetDateTime shippedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(1004L);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setVersion(0L);

        Batch batch = createTestBatch(1004L, 10L, "BATCH-2026-004");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(1004L)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("DRAFT"), eq(0L))).thenReturn(1);

        TransferSubmitRequest req = new TransferSubmitRequest(shippedAt, 0L);
        TransferResponse resp = transferService.submitTransfer(transferId, req, idempotencyKey, senderOperator);

        assertThat(resp.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(resp.shippedAt()).isNotNull();
        assertThat(resp.submittedRecordedAt()).isNotNull();
        assertThat(resp.submittedBy()).isEqualTo(101L);

        verify(transferMapper).updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("DRAFT"), eq(0L));
        verify(auditService).recordAudit(eq(101L), eq(10L), eq("SUBMIT"), eq("TRANSFER"), eq(transferId), any(), eq("SUCCESS"), any());
    }

    @Test
    @DisplayName("接收方接受：实收数量不一致强制填写differenceReason，更新batch.org_id，追加ARRIVAL追溯事件")
    void acceptTransfer_quantityDifference_requiresReason_andUpdatesBatchOrg() {
        Long transferId = 5003L;
        Long batchId = 1005L;
        String idempotencyKey = "idem-accept-0000001";
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("500.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-005");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);
        when(publicTraceCodeMapper.transferOrgScopeByBatchId(eq(batchId), eq(10L), eq(20L), any(), eq(201L))).thenReturn(1);

        // 实收495kg，差异缺少 reason 时报错
        TransferAcceptRequest reqWithoutReason = new TransferAcceptRequest(
                new BigDecimal("495.000"), "kg", receivedAt, null, 1L
        );
        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, reqWithoutReason, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("DIFFERENCE_REASON_REQUIRED");

        // 携带 differenceReason 正常通过
        TransferAcceptRequest reqWithReason = new TransferAcceptRequest(
                new BigDecimal("495.000"), "kg", receivedAt, "冷链运输解冻微量干耗损耗5kg", 1L
        );
        TransferResponse resp = transferService.acceptTransfer(transferId, reqWithReason, idempotencyKey, receiverOperator);

        assertThat(resp.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(resp.receivedQuantity()).isEqualByComparingTo(new BigDecimal("495.000"));
        assertThat(resp.differenceReason()).isEqualTo("冷链运输解冻微量干耗损耗5kg");
        assertThat(resp.decidedBy()).isEqualTo(201L);

        // 验证持有企业更新、公开追溯码归属转移与追溯事件写入
        verify(batchMapper).updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L);
        verify(publicTraceCodeMapper).transferOrgScopeByBatchId(eq(batchId), eq(10L), eq(20L), any(), eq(201L));
        verify(traceEventService).appendArrivalEvent(eq(batchId), eq(20L), eq(201L), eq(receivedAt), any(), eq(transferId), eq("冷链运输解冻微量干耗损耗5kg"));
        verify(auditService).recordAudit(eq(201L), eq(20L), eq("ACCEPT"), eq("TRANSFER"), eq(transferId), any(), eq("SUCCESS"), any());
    }

    @Test
    @DisplayName("接收方接受：接收组织已有同 externalBatchNo 仍接受成功")
    void acceptTransfer_receiverHasSameExternalBatchNo_success() {
        Long transferId = 5004L;
        Long batchId = 1006L;
        String idempotencyKey = "idem-accept-same-external-01";
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("500.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-EXISTING");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);
        when(publicTraceCodeMapper.transferOrgScopeByBatchId(eq(batchId), eq(10L), eq(20L), any(), eq(201L))).thenReturn(1);

        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("500.000"), "kg", receivedAt, null, 1L
        );
        TransferResponse resp = transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator);

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(TransferStatus.ACCEPTED);
        verify(traceEventService).appendArrivalEvent(eq(batchId), eq(20L), eq(201L), eq(receivedAt), any(), eq(transferId), any());
    }

    @Test
    @DisplayName("接收方拒收：保存拒收原因与决定时间，不转移持有组织，不写入追溯事件")
    void rejectTransfer_success_doesNotTransferOwnership_noTraceEvent() {
        Long transferId = 5005L;
        Long batchId = 1007L;
        String idempotencyKey = "idem-reject-0000001";
        OffsetDateTime rejectedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-REJECT");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);

        TransferRejectRequest req = new TransferRejectRequest("货物包装破损解冻变质", rejectedAt, 1L);
        TransferResponse resp = transferService.rejectTransfer(transferId, req, idempotencyKey, receiverOperator);

        assertThat(resp.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(resp.rejectionReason()).isEqualTo("货物包装破损解冻变质");
        assertThat(resp.decidedBy()).isEqualTo(201L);

        // 绝不转移所有权，不追加正式追溯事件
        verify(batchMapper, never()).updateOrgIdByIdAndVersion(any(), any(), any(), any(), any());
        verify(traceEventService, never()).appendArrivalEvent(any(), any(), any(), any(), any(), any(), any());
        verify(auditService).recordAudit(eq(201L), eq(20L), eq("REJECT"), eq("TRANSFER"), eq(transferId), any(), eq("SUCCESS"), any());
    }

    @Test
    @DisplayName("跨组织越权拦截：第三方组织无权查看或操作交接，抛出 404 或 403 异常")
    void thirdParty_accessDenied() {
        Long transferId = 5006L;
        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.PENDING);

        when(transferMapper.selectByIdAndOrgScope(transferId, 30L)).thenReturn(null);
        when(transferMapper.existsByIdIgnoreTenant(transferId)).thenReturn(0);

        assertThatThrownBy(() -> transferService.getTransferDetail(transferId, thirdPartyOperator))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transferMapper).existsByIdIgnoreTenant(transferId);
    }

    @Test
    @DisplayName("提交交接：当关联批次已被已提交批次操作作为INPUT消耗时拦截并抛出 409 BATCH_ALREADY_CONSUMED")
    void submitTransfer_whenBatchConsumedBySubmittedOperation_throwsConflict() {
        Long transferId = 6001L;
        Long batchId = 2001L;
        String idempotencyKey = "idem-submit-consumed-01";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setVersion(0L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-CONSUMED");

        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(batchOperationItemMapper.countSubmittedInputUsageByBatchId(batchId)).thenReturn(1);

        TransferSubmitRequest req = new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L);

        assertThatThrownBy(() -> transferService.submitTransfer(transferId, req, idempotencyKey, senderOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_ALREADY_CONSUMED");
                });
    }

    @Test
    @DisplayName("提交交接：当批次持有企业与交接发货方不一致时拦截并抛出 403 ORG_SCOPE_DENIED")
    void submitTransfer_whenBatchOrgIdNotEqualsSenderOrgId_throwsForbidden() {
        Long transferId = 6002L;
        Long batchId = 2002L;
        String idempotencyKey = "idem-submit-org-tampered";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setVersion(0L);

        Batch batch = createTestBatch(batchId, 99L, "BATCH-2026-ORG-99"); // 被并发篡改或归属不同

        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);

        TransferSubmitRequest req = new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), 0L);

        assertThatThrownBy(() -> transferService.submitTransfer(transferId, req, idempotencyKey, senderOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });
    }

    @Test
    @DisplayName("接受交接：当批次持有企业不再属于发送方时拦截并抛出 403 ORG_SCOPE_DENIED")
    void acceptTransfer_whenBatchOrgIdNotEqualsSenderOrgId_throwsForbidden() {
        Long transferId = 6003L;
        Long batchId = 2003L;
        String idempotencyKey = "idem-accept-org-tampered";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("100.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 99L, "BATCH-2026-ORG-99"); // 非原发货方

        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);

        TransferAcceptRequest req = new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });
    }

    @Test
    @DisplayName("接受交接：当实收单位与交接单位不一致时抛出 400 UNIT_CODE_MISMATCH")
    void acceptTransfer_whenUnitCodeMismatch_throwsBadRequest() {
        Long transferId = 6004L;
        Long batchId = 2004L;
        String idempotencyKey = "idem-accept-unit-mismatch";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("100.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-UNIT");

        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);

        TransferAcceptRequest req = new TransferAcceptRequest(new BigDecimal("100.000"), "box", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("UNIT_CODE_MISMATCH");
                });
    }

    @Test
    @DisplayName("接受交接：当实收数量小于等于0时抛出 400 INVALID_REQUEST")
    void acceptTransfer_whenReceivedQuantityNonPositive_throwsException() {
        Long transferId = 6005L;
        String idempotencyKey = "idem-accept-zero-qty";

        TransferAcceptRequest req = new TransferAcceptRequest(BigDecimal.ZERO, "kg", OffsetDateTime.now(ZoneOffset.UTC), "零数量", 1L);

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("并发同Key在态变后到达：锁定后通过当前读selectByOrgIdAndKeyForUpdate识别幂等并返回原实体")
    void submitTransfer_concurrentSameKeyAfterStateChanged_returnsExistingTransferViaSelectForUpdate() {
        Long transferId = 6006L;
        String idempotencyKey = "idem-submit-concurrent-same-key";
        OffsetDateTime shippedAt = OffsetDateTime.parse("2026-09-14T10:00:00Z");

        // 模拟普通快照预检未查出（穿透前为null）
        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);

        // 拿到锁时，transfer 已经被前一并发线程推到了 PENDING 状态
        Transfer transferInDb = new Transfer();
        transferInDb.setId(transferId);
        transferInDb.setSenderOrgId(10L);
        transferInDb.setReceiverOrgId(20L);
        transferInDb.setStatus(TransferStatus.PENDING);
        transferInDb.setVersion(1L);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transferInDb);

        // 锁定后当前读查出了前一线程落库的幂等记录，且语义哈希完全一致
        TransferIdempotency concurrentIdem = new TransferIdempotency();
        concurrentIdem.setOrgId(10L);
        concurrentIdem.setIdempotencyKey(idempotencyKey);
        concurrentIdem.setAction("SUBMIT");
        concurrentIdem.setTransferId(transferId);
        concurrentIdem.setRequestHash(computeHashForTest("SUBMIT", transferId, shippedAt.toInstant().toString(), 0L));
        when(idempotencyMapper.selectByOrgIdAndKeyForUpdate(10L, idempotencyKey)).thenReturn(concurrentIdem);
        when(transferMapper.selectById(transferId)).thenReturn(transferInDb);

        TransferSubmitRequest req = new TransferSubmitRequest(shippedAt, 0L);

        // 预期直接返回，不抛出 INVALID_STATE_TRANSITION
        TransferResponse resp = transferService.submitTransfer(transferId, req, idempotencyKey, senderOperator);
        assertThat(resp).isNotNull();
        assertThat(resp.id()).isEqualTo(transferId);
        assertThat(resp.status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("创建交接草稿：并发触发uk_transfer_open_batch唯一冲突时，当前读恢复非同Key则转为409而非冒泡500")
    void createDraft_whenOpenBatchConflict_recoversOrThrowsConflictInsteadOf500() {
        Long batchId = 2007L;
        Long receiverOrgId = 20L;
        String idempotencyKey = "idem-create-open-conflict";

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-007");

        Organization receiverOrg = new Organization();
        receiverOrg.setId(receiverOrgId);
        receiverOrg.setStatus("ACTIVE");

        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(organizationMapper.selectById(receiverOrgId)).thenReturn(receiverOrg);
        when(transferMapper.countActiveTransfersByBatchId(batchId)).thenReturn(0);
        when(batchOperationItemMapper.countSubmittedInputUsageByBatchId(batchId)).thenReturn(0);
        when(idempotencyMapper.selectByOrgIdAndKey(10L, idempotencyKey)).thenReturn(null);

        // 插入时因数据库 uk_transfer_open_batch 发生唯一约束冲突
        org.springframework.dao.DuplicateKeyException dupEx =
                new org.springframework.dao.DuplicateKeyException("Duplicate entry '2007' for key 'uk_transfer_open_batch'");
        when(transferMapper.insert(any(Transfer.class))).thenThrow(dupEx);

        TransferCreateRequest req = new TransferCreateRequest(batchId, receiverOrgId);

        assertThatThrownBy(() -> transferService.createDraft(req, idempotencyKey, senderOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_TRANSFER_CONFLICT");
                });
    }

    @Test
    @DisplayName("查询交接详情：当交接记录存在但属于其他企业组织时抛出 403 ORG_SCOPE_DENIED")
    void getTransferDetail_whenRecordExistsInOtherOrg_throwsForbidden() {
        Long transferId = 6008L;
        Transfer rawTransfer = new Transfer();
        rawTransfer.setId(transferId);
        rawTransfer.setSenderOrgId(10L);
        rawTransfer.setReceiverOrgId(20L);

        when(transferMapper.selectByIdAndOrgScope(transferId, 30L)).thenReturn(null);
        when(transferMapper.existsByIdIgnoreTenant(transferId)).thenReturn(1);

        assertThatThrownBy(() -> transferService.getTransferDetail(transferId, thirdPartyOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ORG_SCOPE_DENIED");
                });

        verify(transferMapper).existsByIdIgnoreTenant(transferId);
    }

    @Test
    @DisplayName("接受交接：并发触发批次更新唯一冲突时映射为 409 BATCH_CONCURRENT_CONFLICT 且事务回滚")
    void acceptTransfer_whenConcurrentDuplicateBatchNoConflict_rollsBackAndMapsConflict() {
        Long transferId = 6009L;
        Long batchId = 2009L;
        String idempotencyKey = "idem-accept-batch-conflict";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("100.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-CONFLICT-001");

        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_batch_trace_no"));

        TransferAcceptRequest req = new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L);

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_CONCURRENT_CONFLICT");
                });
    }

    @Test
    @DisplayName("哈希规范化测试：BigDecimal的 1.0 与 1.000 生成相同规范化请求哈希，避免伪冲突")
    void hashNormalization_forBigDecimalTrailingZeros_producesSameHash() {
        String hash1 = computeHashForTest("ACCEPT", 1L, new BigDecimal("100.0"));
        String hash2 = computeHashForTest("ACCEPT", 1L, new BigDecimal("100.000"));
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("审计日志JSON序列化安全：当原因中包含特殊字符和双引号时能够成功解析为合法JSON")
    void auditLog_whenReasonContainsSpecialChars_serializesValidJson() {
        Long transferId = 6010L;
        Long batchId = 2010L;
        String idempotencyKey = "idem-reject-json-escape";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);

        String trickyReason = "温度超标: \"-12℃\"，出现解冻；\n批注: \\特殊测试/\\";
        TransferRejectRequest req = new TransferRejectRequest(trickyReason, OffsetDateTime.now(ZoneOffset.UTC), 1L);

        transferService.rejectTransfer(transferId, req, idempotencyKey, receiverOperator);

        org.mockito.ArgumentCaptor<String> summaryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAudit(eq(201L), eq(20L), eq("REJECT"), eq("TRANSFER"), eq(transferId), any(), eq("SUCCESS"), summaryCaptor.capture());

        String jsonSummary = summaryCaptor.getValue();
        assertThat(jsonSummary).isNotNull();
        // 验证能被 Jackson 正常反序列化，证明没有破坏 JSON 结构
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> objectMapper.readTree(jsonSummary));
    }

    @Test
    @DisplayName("接收方接受：首次成功后同Key同其余字段但expectedVersion改变时，必须抛出 409 IDEMPOTENCY_CONFLICT")
    void acceptTransfer_whenSameKeyWithDifferentExpectedVersion_throwsIdempotencyConflict() {
        Long transferId = 7001L;
        Long batchId = 3001L;
        String idempotencyKey = "idem-accept-version-diff-01";
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-14T12:00:00Z");
        BigDecimal quantity = new BigDecimal("495.000");
        String unitCode = "kg";
        String diffReason = "冷链损耗5kg";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("500.000"));
        transfer.setUnitCode(unitCode);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-701");

        java.util.concurrent.atomic.AtomicReference<TransferIdempotency> savedIdem = new java.util.concurrent.atomic.AtomicReference<>();
        when(idempotencyMapper.selectByOrgIdAndKey(eq(20L), eq(idempotencyKey)))
                .thenAnswer(inv -> savedIdem.get());
        when(idempotencyMapper.insert(any(TransferIdempotency.class)))
                .thenAnswer(inv -> {
                    TransferIdempotency e = inv.getArgument(0);
                    savedIdem.set(e);
                    return 1;
                });
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);
        when(transferMapper.selectById(transferId)).thenReturn(transfer);

        // 1. 首次 ACCEPT 请求成功 (expectedVersion = 1L)
        TransferAcceptRequest req1 = new TransferAcceptRequest(quantity, unitCode, receivedAt, diffReason, 1L);
        TransferResponse resp1 = transferService.acceptTransfer(transferId, req1, idempotencyKey, receiverOperator);
        assertThat(resp1).isNotNull();
        assertThat(savedIdem.get()).isNotNull();

        // 2. 第二次 ACCEPT 请求：同 key，其余字段完全相同，但 expectedVersion 改变 (2L)
        TransferAcceptRequest req2 = new TransferAcceptRequest(quantity, unitCode, receivedAt, diffReason, 2L);
        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req2, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("接收方拒收：首次成功后同Key同其余字段但expectedVersion改变时，必须抛出 409 IDEMPOTENCY_CONFLICT")
    void rejectTransfer_whenSameKeyWithDifferentExpectedVersion_throwsIdempotencyConflict() {
        Long transferId = 7002L;
        Long batchId = 3002L;
        String idempotencyKey = "idem-reject-version-diff-01";
        OffsetDateTime rejectedAt = OffsetDateTime.parse("2026-09-14T13:00:00Z");
        String reason = "外包装严重破损";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        java.util.concurrent.atomic.AtomicReference<TransferIdempotency> savedIdem = new java.util.concurrent.atomic.AtomicReference<>();
        when(idempotencyMapper.selectByOrgIdAndKey(eq(20L), eq(idempotencyKey)))
                .thenAnswer(inv -> savedIdem.get());
        when(idempotencyMapper.insert(any(TransferIdempotency.class)))
                .thenAnswer(inv -> {
                    TransferIdempotency e = inv.getArgument(0);
                    savedIdem.set(e);
                    return 1;
                });
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(transferMapper.selectById(transferId)).thenReturn(transfer);

        // 1. 首次 REJECT 请求成功 (expectedVersion = 1L)
        TransferRejectRequest req1 = new TransferRejectRequest(reason, rejectedAt, 1L);
        TransferResponse resp1 = transferService.rejectTransfer(transferId, req1, idempotencyKey, receiverOperator);
        assertThat(resp1).isNotNull();
        assertThat(savedIdem.get()).isNotNull();

        // 2. 第二次 REJECT 请求：同 key，其余字段完全相同，但 expectedVersion 改变 (2L)
        TransferRejectRequest req2 = new TransferRejectRequest(reason, rejectedAt, 2L);
        assertThatThrownBy(() -> transferService.rejectTransfer(transferId, req2, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    @DisplayName("接收方接受：同Key同完整语义（含expectedVersion）重放返回原结果且不重复追加ARRIVAL事件")
    void acceptTransfer_whenSameKeyWithSameSemantics_replaysOriginalResponseAndNoDuplicateArrival() {
        Long transferId = 7003L;
        Long batchId = 3003L;
        String idempotencyKey = "idem-accept-same-semantic-01";
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-14T14:00:00Z");
        BigDecimal quantity = new BigDecimal("495.000");
        String unitCode = "kg";
        String diffReason = "冷链损耗5kg";

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("500.000"));
        transfer.setUnitCode(unitCode);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-703");

        java.util.concurrent.atomic.AtomicReference<TransferIdempotency> savedIdem = new java.util.concurrent.atomic.AtomicReference<>();
        when(idempotencyMapper.selectByOrgIdAndKey(eq(20L), eq(idempotencyKey)))
                .thenAnswer(inv -> savedIdem.get());
        when(idempotencyMapper.insert(any(TransferIdempotency.class)))
                .thenAnswer(inv -> {
                    TransferIdempotency e = inv.getArgument(0);
                    savedIdem.set(e);
                    return 1;
                });
        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);

        Transfer acceptedTransfer = new Transfer();
        acceptedTransfer.setId(transferId);
        acceptedTransfer.setStatus(TransferStatus.ACCEPTED);
        acceptedTransfer.setVersion(2L);
        when(transferMapper.selectById(transferId)).thenReturn(acceptedTransfer);

        TransferAcceptRequest req = new TransferAcceptRequest(quantity, unitCode, receivedAt, diffReason, 1L);

        // 首次请求
        TransferResponse resp1 = transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator);
        assertThat(resp1).isNotNull();

        // 相同完整语义（含 expectedVersion=1L）二次重试
        TransferResponse resp2 = transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator);
        assertThat(resp2).isNotNull();
        assertThat(resp2.id()).isEqualTo(transferId);

        // 验证 ARRIVAL 事件仅在首次调用时追加了 1 次，重放时没有追加第 2 次
        verify(traceEventService, org.mockito.Mockito.times(1)).appendArrivalEvent(
                eq(batchId), eq(20L), eq(201L), eq(receivedAt), any(), eq(transferId), eq(diffReason)
        );
    }

    @Test
    @DisplayName("创建交接草稿：支持最长 128 字符 Idempotency-Key 创建并在重放时安全幂等恢复")
    void createDraft_with128CharIdempotencyKey_successAndReplay() {
        Long batchId = 1099L;
        Long receiverOrgId = 20L;
        // 构造恰好 128 个字符的合法 Idempotency-Key
        String key128 = "idem-128-char-test-" + "x".repeat(109);
        assertThat(key128.length()).isEqualTo(128);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-128-KEY");

        java.util.concurrent.atomic.AtomicReference<TransferIdempotency> savedIdem = new java.util.concurrent.atomic.AtomicReference<>();
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(transferMapper.countActiveTransfersByBatchId(batchId)).thenReturn(0);
        when(batchOperationItemMapper.countSubmittedInputUsageByBatchId(batchId)).thenReturn(0);
        when(idempotencyMapper.selectByOrgIdAndKey(eq(10L), eq(key128)))
                .thenAnswer(inv -> savedIdem.get());
        when(idempotencyMapper.insert(any(TransferIdempotency.class)))
                .thenAnswer(inv -> {
                    TransferIdempotency e = inv.getArgument(0);
                    savedIdem.set(e);
                    return 1;
                });
        when(transferMapper.insert(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            t.setId(5099L);
            t.setVersion(0L);
            return 1;
        });

        TransferCreateRequest req = new TransferCreateRequest(batchId, receiverOrgId);
        // 首次创建
        TransferResponse resp1 = transferService.createDraft(req, key128, senderOperator);
        assertThat(resp1).isNotNull();
        assertThat(resp1.batchId()).isEqualTo(batchId);

        Transfer existingTransfer = new Transfer();
        existingTransfer.setId(5099L);
        existingTransfer.setBatchId(batchId);
        existingTransfer.setSenderOrgId(10L);
        existingTransfer.setReceiverOrgId(receiverOrgId);
        existingTransfer.setQuantity(new BigDecimal("100.000"));
        existingTransfer.setUnitCode("kg");
        existingTransfer.setStatus(TransferStatus.DRAFT);
        when(transferMapper.selectById(5099L)).thenReturn(existingTransfer);

        // 幂等重放
        TransferResponse resp2 = transferService.createDraft(req, key128, senderOperator);
        assertThat(resp2).isNotNull();
        assertThat(resp2.id()).isEqualTo(5099L);
    }

    @Test
    @DisplayName("接收方接受：关联公开追溯码原持有组织与发货方不一致时，整体回滚并抛出 409 TRACE_CODE_ORG_CONFLICT")
    void acceptTransfer_withPublicTraceCodeOrgMismatch_rollsBackAndThrowsConflict() {
        Long transferId = 5088L;
        Long batchId = 1088L;
        String idempotencyKey = "idem-accept-code-mismatch";
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("200.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-CODE-MISMATCH");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);
        // 模拟公开码转移因为组织不匹配影响 0 行
        when(publicTraceCodeMapper.transferOrgScopeByBatchId(eq(batchId), eq(10L), eq(20L), any(), eq(201L))).thenReturn(0);
        // 忽略租户统计该批次确实有关联公开码（说明存在脏数据/组织越权）
        when(publicTraceCodeMapper.countByBatchIdIgnoreTenant(batchId)).thenReturn(1);

        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("200.000"), "kg", receivedAt, null, 1L
        );

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("TRACE_CODE_ORG_CONFLICT");
                });

        // 验证决不写入 ARRIVAL 事件和审计记录
        verify(traceEventService, never()).appendArrivalEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("接收方接受：批次未生成公开追溯码时影响行数为0正常允许，成功完成接受")
    void acceptTransfer_withoutPublicTraceCode_succeeds() {
        Long transferId = 5089L;
        Long batchId = 1089L;
        String idempotencyKey = "idem-accept-no-code";
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("200.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-NO-CODE");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(1);
        // 批次未生成公开码，影响 0 行
        when(publicTraceCodeMapper.transferOrgScopeByBatchId(eq(batchId), eq(10L), eq(20L), any(), eq(201L))).thenReturn(0);
        // 数据库中确实不存在公开码
        when(publicTraceCodeMapper.countByBatchIdIgnoreTenant(batchId)).thenReturn(0);

        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("200.000"), "kg", receivedAt, null, 1L
        );

        TransferResponse resp = transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator);
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(TransferStatus.ACCEPTED);

        verify(traceEventService).appendArrivalEvent(eq(batchId), eq(20L), eq(201L), eq(receivedAt), any(), eq(transferId), any());
    }

    @Test
    @DisplayName("接收方接受：批次旧持有组织谓词不匹配导致更新0行时，抛出 409 BATCH_CONCURRENT_CONFLICT")
    void acceptTransfer_whenBatchOrgMismatch_throwsBatchConcurrentConflict() {
        Long transferId = 5090L;
        Long batchId = 1090L;
        String idempotencyKey = "idem-accept-batch-conflict";
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setBatchId(batchId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setQuantity(new BigDecimal("200.000"));
        transfer.setUnitCode("kg");
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setVersion(1L);

        Batch batch = createTestBatch(batchId, 10L, "BATCH-2026-ORG-DIFF");

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        when(batchMapper.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(idempotencyMapper.selectByOrgIdAndKey(20L, idempotencyKey)).thenReturn(null);
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("PENDING"), eq(1L))).thenReturn(1);
        // 模拟底层组织或版本冲突返回 0 行
        when(batchMapper.updateOrgIdByIdAndVersion(batchId, 10L, 20L, 0L, 201L)).thenReturn(0);

        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("200.000"), "kg", receivedAt, null, 1L
        );

        assertThatThrownBy(() -> transferService.acceptTransfer(transferId, req, idempotencyKey, receiverOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BATCH_CONCURRENT_CONFLICT");
                });
    }

    @Test
    @DisplayName("修改草稿：组织谓词或版本不匹配导致 updateByIdAndVersion 返回0行时抛出 409 VERSION_CONFLICT")
    void patchDraft_withOrgPredicateMismatch_throwsVersionConflict() {
        Long transferId = 5091L;

        Transfer transfer = new Transfer();
        transfer.setId(transferId);
        transfer.setSenderOrgId(10L);
        transfer.setReceiverOrgId(20L);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setVersion(0L);

        Organization newReceiverOrg = new Organization();
        newReceiverOrg.setId(25L);
        newReceiverOrg.setStatus("ACTIVE");
        when(organizationMapper.selectById(25L)).thenReturn(newReceiverOrg);

        when(transferMapper.selectByIdForUpdate(transferId)).thenReturn(transfer);
        // 模拟底层组织谓词或版本已被并发修改导致返回 0 行
        when(transferMapper.updateByIdAndVersion(any(Transfer.class), eq(10L), eq(20L), eq("DRAFT"), eq(0L))).thenReturn(0);

        TransferPatchRequest req = new TransferPatchRequest(25L, 0L);

        assertThatThrownBy(() -> transferService.patchDraft(transferId, req, senderOperator))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("VERSION_CONFLICT");
                });
    }

    private String computeHashForTest(String action, Object... params) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(action.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] delimiter = new byte[]{0x1F};
            for (Object param : params) {
                digest.update(delimiter);
                if (param != null) {
                    if (param instanceof BigDecimal bd) {
                        digest.update(bd.stripTrailingZeros().toPlainString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else if (param instanceof java.time.temporal.TemporalAccessor) {
                        digest.update(param.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        digest.update(param.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else {
                    digest.update(new byte[]{0});
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
