package com.example.traceability.trace.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.ShipmentIdempotency;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.dto.ShipmentBindTransferRequest;
import com.example.traceability.trace.dto.ShipmentCancelRequest;
import com.example.traceability.trace.dto.ShipmentCreateRequest;
import com.example.traceability.trace.dto.ShipmentDispatchRequest;
import com.example.traceability.trace.mapper.ShipmentIdempotencyMapper;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冷链运输任务应用服务单元契约测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("冷链运输任务应用服务单元测试")
class ShipmentApplicationServiceTest {

    private static final Long SHIPMENT_ID = 9001L;

    @Mock
    private ShipmentMapper shipmentMapper;
    @Mock
    private ShipmentIdempotencyMapper idempotencyMapper;
    @Mock
    private TransferMapper transferMapper;
    @Mock
    private BatchMapper batchMapper;
    @Mock
    private BatchOperationItemMapper batchOperationItemMapper;
    @Mock
    private OrganizationMapper organizationMapper;
    @Mock
    private SiteMapper siteMapper;
    @Mock
    private TraceEventApplicationService traceEventService;
    @Mock
    private AuditApplicationService auditService;

    private ShipmentApplicationService service;
    private TraceSecurityPrincipal sender;
    private TraceSecurityPrincipal carrier;
    private TraceSecurityPrincipal receiver;

    @BeforeEach
    void setUp() {
        service = new ShipmentApplicationService(shipmentMapper, idempotencyMapper, transferMapper, batchMapper,
                batchOperationItemMapper, organizationMapper, siteMapper, traceEventService, auditService, new ObjectMapper());
        sender = principal(101L, 10L, "SOURCE");
        receiver = principal(201L, 20L, "PROCESSOR");
        carrier = principal(301L, 30L, "CARRIER");

        lenient().when(organizationMapper.selectById(30L)).thenReturn(org(30L, "CARRIER"));
        lenient().when(organizationMapper.selectById(20L)).thenReturn(org(20L, "PROCESSOR"));
        lenient().when(siteMapper.selectByIdIgnoreTenant(1L)).thenReturn(site(1L, 10L));
        lenient().when(siteMapper.selectByIdIgnoreTenant(2L)).thenReturn(site(2L, 20L));
        lenient().when(shipmentMapper.selectById(SHIPMENT_ID)).thenAnswer(inv -> shipment(ShipmentStatus.PLANNED));
    }

    private static TraceSecurityPrincipal principal(Long userId, Long orgId, String orgType) {
        return new TraceSecurityPrincipal(userId, "u" + userId, "u" + userId, "hash",
                orgId, "ORG-" + orgId, "组织" + orgId, orgType, List.of("OPERATOR"), List.of("OWN_ORG"), true, true);
    }

    private static Organization org(Long id, String type) {
        Organization o = new Organization();
        o.setId(id);
        o.setName("组织" + id);
        o.setOrgType(type);
        o.setStatus("ACTIVE");
        return o;
    }

    private static Site site(Long id, Long orgId) {
        Site s = new Site();
        s.setId(id);
        s.setOrgId(orgId);
        s.setName("场所" + id);
        s.setStatus("ACTIVE");
        return s;
    }

    private static Shipment shipment(ShipmentStatus status) {
        Shipment s = new Shipment();
        s.setId(SHIPMENT_ID);
        s.setShipmentNo("SHP-1");
        s.setSenderOrgId(10L);
        s.setReceiverOrgId(20L);
        s.setCarrierOrgId(30L);
        s.setOriginSiteId(1L);
        s.setDestinationSiteId(2L);
        s.setVehicleOrContainerNo("浙A-1");
        s.setStatus(status);
        s.setVersion(3L);
        if (status != ShipmentStatus.PLANNED && status != ShipmentStatus.CANCELLED) {
            s.setLoadedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        }
        return s;
    }

    private static Transfer transfer(Long id, Long batchId, TransferStatus status) {
        Transfer t = new Transfer();
        t.setId(id);
        t.setTransferNo("TRF-" + id);
        t.setBatchId(batchId);
        t.setSenderOrgId(10L);
        t.setReceiverOrgId(20L);
        t.setQuantity(new BigDecimal("1000.000"));
        t.setUnitCode("kg");
        t.setStatus(status);
        t.setVersion(0L);
        if (status != TransferStatus.DRAFT) {
            t.setShipmentId(SHIPMENT_ID);
        }
        return t;
    }

    private static Batch batch(Long id) {
        Batch b = new Batch();
        b.setId(id);
        b.setOrgId(10L);
        b.setTraceBatchNo("TB-" + id);
        b.setFlowStatus("ACTIVE");
        b.setRiskStatus("NORMAL");
        b.setQuantity(new BigDecimal("1000.000"));
        b.setVersion(1L);
        return b;
    }

    @Test
    @DisplayName("创建：承运组织不是 CARRIER 时 422 CARRIER_ORG_INVALID，不写入运输任务")
    void create_carrierMustBeCarrierType() {
        when(organizationMapper.selectById(20L)).thenReturn(org(20L, "PROCESSOR"));
        assertThatThrownBy(() -> service.createShipment(new ShipmentCreateRequest(20L, 1L, 2L, "浙A-1"), "idem-create-00000001", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("CARRIER_ORG_INVALID");
        verify(shipmentMapper, never()).insert(any(Shipment.class));
    }

    @Test
    @DisplayName("创建：承运组织不能作为发货方创建运输任务（Demo MVP Phase A 限制）")
    void create_carrierCannotBeSender() {
        assertThatThrownBy(() -> service.createShipment(new ShipmentCreateRequest(30L, 1L, 2L, "浙A-1"), "idem-create-00000002", carrier))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ROLE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("创建：接收组织由目的场所所属组织确定，状态为 PLANNED 且 loadedAt 为空")
    void create_derivesReceiverFromDestinationSite() {
        when(shipmentMapper.insert(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(SHIPMENT_ID);
            return 1;
        });
        service.createShipment(new ShipmentCreateRequest(30L, 1L, 2L, " 浙A-1 "), "idem-create-00000003", sender);

        org.mockito.ArgumentCaptor<Shipment> captor = org.mockito.ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getReceiverOrgId()).isEqualTo(20L);
        assertThat(captor.getValue().getSenderOrgId()).isEqualTo(10L);
        assertThat(captor.getValue().getStatus()).isEqualTo(ShipmentStatus.PLANNED);
        assertThat(captor.getValue().getLoadedAt()).isNull();
        assertThat(captor.getValue().getVehicleOrContainerNo()).isEqualTo("浙A-1");
        verify(idempotencyMapper).insert(any(ShipmentIdempotency.class));
    }

    @Test
    @DisplayName("绑定：按 shipment → transfer → batch 顺序加锁并递增运输任务版本")
    void bind_locksInOrderAndBumpsManifestVersion() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByIdForUpdate(5001L)).thenReturn(transfer(5001L, 1001L, TransferStatus.DRAFT));
        when(batchMapper.selectByIdForUpdate(1001L)).thenReturn(batch(1001L));
        when(transferMapper.bindShipment(5001L, SHIPMENT_ID, 0L, 101L)).thenReturn(1);
        when(shipmentMapper.bumpManifestVersion(SHIPMENT_ID, 101L)).thenReturn(1);

        service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5001L, 0L), "idem-bind-000000001", sender);

        InOrder order = inOrder(shipmentMapper, transferMapper, batchMapper);
        order.verify(shipmentMapper).selectByIdForUpdate(SHIPMENT_ID);
        order.verify(transferMapper).selectByIdForUpdate(5001L);
        order.verify(batchMapper).selectByIdForUpdate(1001L);
        order.verify(transferMapper).bindShipment(5001L, SHIPMENT_ID, 0L, 101L);
        order.verify(shipmentMapper).bumpManifestVersion(SHIPMENT_ID, 101L);
    }

    @Test
    @DisplayName("绑定：交接接收方与运输任务不一致时 422 SHIPMENT_PARTY_MISMATCH")
    void bind_receiverMismatch() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        Transfer t = transfer(5002L, 1002L, TransferStatus.DRAFT);
        t.setReceiverOrgId(99L);
        when(transferMapper.selectByIdForUpdate(5002L)).thenReturn(t);

        assertThatThrownBy(() -> service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5002L, 0L), "idem-bind-000000002", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SHIPMENT_PARTY_MISMATCH");
        verify(transferMapper, never()).bindShipment(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("绑定：数据库 uk_transfer_shipment_batch 冲突映射为 409 SHIPMENT_BATCH_DUPLICATE")
    void bind_duplicateKeyMapped() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByIdForUpdate(5003L)).thenReturn(transfer(5003L, 1003L, TransferStatus.DRAFT));
        when(batchMapper.selectByIdForUpdate(1003L)).thenReturn(batch(1003L));
        when(transferMapper.bindShipment(5003L, SHIPMENT_ID, 0L, 101L)).thenThrow(new DuplicateKeyException("uk_transfer_shipment_batch"));

        assertThatThrownBy(() -> service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5003L, 0L), "idem-bind-000000003", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SHIPMENT_BATCH_DUPLICATE");
    }

    @Test
    @DisplayName("绑定（PB2 Demo MVP 限制）：批次产品与装载清单已有批次不同时 422 SHIPMENT_PRODUCT_MISMATCH，不绑定、不递增版本")
    void bind_rejectsDifferentProductOnManifest() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByIdForUpdate(5021L)).thenReturn(transfer(5021L, 1021L, TransferStatus.DRAFT));
        Transfer loaded = transfer(5020L, 1020L, TransferStatus.DRAFT);
        loaded.setShipmentId(SHIPMENT_ID);
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(loaded));
        Batch onManifest = batch(1020L);
        onManifest.setProductId(7L);
        Batch incoming = batch(1021L);
        incoming.setProductId(8L);
        when(batchMapper.selectByIdForUpdate(1021L)).thenReturn(incoming);
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(onManifest));

        assertThatThrownBy(() -> service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5021L, 0L), "idem-bind-000000021", sender))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("SHIPMENT_PRODUCT_MISMATCH");
                    assertThat(be.getStatus().value()).isEqualTo(422);
                });
        verify(transferMapper, never()).bindShipment(anyLong(), anyLong(), anyLong(), anyLong());
        verify(shipmentMapper, never()).bumpManifestVersion(anyLong(), anyLong());
    }

    @Test
    @DisplayName("绑定（PB2 Demo MVP 限制）：同一产品的第二个批次可以装载同一运输任务")
    void bind_allowsSameProductOnManifest() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByIdForUpdate(5023L)).thenReturn(transfer(5023L, 1023L, TransferStatus.DRAFT));
        Transfer loaded = transfer(5022L, 1022L, TransferStatus.DRAFT);
        loaded.setShipmentId(SHIPMENT_ID);
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(loaded));
        Batch onManifest = batch(1022L);
        onManifest.setProductId(7L);
        Batch incoming = batch(1023L);
        incoming.setProductId(7L);
        when(batchMapper.selectByIdForUpdate(1023L)).thenReturn(incoming);
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(onManifest));
        when(transferMapper.bindShipment(5023L, SHIPMENT_ID, 0L, 101L)).thenReturn(1);
        when(shipmentMapper.bumpManifestVersion(SHIPMENT_ID, 101L)).thenReturn(1);

        service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5023L, 0L), "idem-bind-000000023", sender);

        verify(transferMapper).bindShipment(5023L, SHIPMENT_ID, 0L, 101L);
    }

    @Test
    @DisplayName("绑定：非 PLANNED 运输任务禁止变更装载清单，且不会锁交接")
    void bind_afterDispatch_rejected() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        assertThatThrownBy(() -> service.bindTransfer(SHIPMENT_ID, new ShipmentBindTransferRequest(5004L, 0L), "idem-bind-000000004", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SHIPMENT_NOT_PLANNED");
        verify(transferMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    @DisplayName("发运：仅指定承运组织可操作；发货方 403")
    void dispatch_onlyAssignedCarrier() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        assertThatThrownBy(() -> service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-dispatch-0000001", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORG_SCOPE_DENIED");
        verify(traceEventService, never()).appendShipmentTransportEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("发运：装载清单含非 PENDING 交接时 409，且不生成任何事件")
    void dispatch_requiresAllPending() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        Transfer draft = transfer(5005L, 1005L, TransferStatus.DRAFT);
        draft.setShipmentId(SHIPMENT_ID);
        when(transferMapper.selectByShipmentIdForUpdate(SHIPMENT_ID)).thenReturn(List.of(transfer(5006L, 1006L, TransferStatus.PENDING), draft));

        assertThatThrownBy(() -> service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-dispatch-0000002", carrier))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SHIPMENT_TRANSFER_NOT_PENDING");
        verify(shipmentMapper, never()).updateLifecycleByIdAndVersion(any(), any(), any());
        verify(traceEventService, never()).appendShipmentTransportEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("发运：业务时间不能明显晚于服务端时钟")
    void dispatch_rejectsFutureLoadedAt() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        assertThatThrownBy(() -> service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), 3L), "idem-dispatch-0000003", carrier))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_BUSINESS_TIME");
    }

    @Test
    @DisplayName("发运：为装载清单中每个 Batch 各生成一条 TRANSPORT，不修改交接")
    void dispatch_projectsOneTransportPerBatch() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByShipmentIdForUpdate(SHIPMENT_ID)).thenReturn(List.of(
                transfer(5007L, 1007L, TransferStatus.PENDING), transfer(5008L, 1008L, TransferStatus.PENDING)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1007L), batch(1008L)));
        when(shipmentMapper.updateLifecycleByIdAndVersion(any(Shipment.class), eq("PLANNED"), eq(3L))).thenReturn(1);

        service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-dispatch-0000004", carrier);

        verify(traceEventService).appendShipmentTransportEvent(any(), eq(1007L), eq("TB-1007"), eq(5007L), eq(301L), any());
        verify(traceEventService).appendShipmentTransportEvent(any(), eq(1008L), eq("TB-1008"), eq(5008L), eq(301L), any());
        verify(traceEventService, times(2)).appendShipmentTransportEvent(any(), any(), any(), any(), any(), any());
        verify(transferMapper, never()).updateByIdAndVersion(any(), any(), any(), any(), any());
        verify(batchMapper, never()).updateOrgIdByIdAndVersion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("到达：为每个 Batch 各生成一条 ARRIVAL，交接保持 PENDING，不转移责任组织")
    void arrive_projectsArrivalAndLeavesTransfersPending() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.IN_TRANSIT));
        when(transferMapper.selectByShipmentId(SHIPMENT_ID)).thenReturn(List.of(transfer(5009L, 1009L, TransferStatus.PENDING)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1009L)));
        when(shipmentMapper.updateLifecycleByIdAndVersion(any(Shipment.class), eq("IN_TRANSIT"), eq(3L))).thenReturn(1);

        service.arriveShipment(SHIPMENT_ID, new ShipmentArriveRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-arrive-00000001", carrier);

        verify(traceEventService).appendShipmentArrivalEvent(any(), eq(1009L), eq("TB-1009"), eq(5009L), eq(301L), any());
        verify(transferMapper, never()).updateByIdAndVersion(any(), any(), any(), any(), any());
        verify(batchMapper, never()).updateOrgIdByIdAndVersion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("到达：未发运 (PLANNED) 时 409 INVALID_STATE_TRANSITION；接收方不能确认到达")
    void arrive_stateAndRole() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        assertThatThrownBy(() -> service.arriveShipment(SHIPMENT_ID, new ShipmentArriveRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-arrive-00000002", carrier))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
        assertThatThrownBy(() -> service.arriveShipment(SHIPMENT_ID, new ShipmentArriveRequest(OffsetDateTime.now(ZoneOffset.UTC), 3L), "idem-arrive-00000003", receiver))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORG_SCOPE_DENIED");
    }

    @Test
    @DisplayName("幂等：同 key 同语义重放在加锁前直接返回，不重复生成事件")
    void dispatch_replayBeforeLocking() {
        OffsetDateTime loadedAt = OffsetDateTime.parse("2026-09-22T01:00:00Z");
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByShipmentIdForUpdate(SHIPMENT_ID)).thenReturn(List.of(transfer(5010L, 1010L, TransferStatus.PENDING)));
        when(batchMapper.selectByIdsIgnoreTenant(any())).thenReturn(List.of(batch(1010L)));
        when(shipmentMapper.updateLifecycleByIdAndVersion(any(Shipment.class), eq("PLANNED"), eq(3L))).thenReturn(1);
        org.mockito.ArgumentCaptor<ShipmentIdempotency> saved = org.mockito.ArgumentCaptor.forClass(ShipmentIdempotency.class);

        service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(loadedAt, 3L), "idem-dispatch-replay-1", carrier);
        verify(idempotencyMapper).insert(saved.capture());
        when(idempotencyMapper.selectByOrgIdAndKey(30L, "idem-dispatch-replay-1")).thenReturn(saved.getValue());

        service.dispatchShipment(SHIPMENT_ID, new ShipmentDispatchRequest(loadedAt, 3L), "idem-dispatch-replay-1", carrier);

        verify(shipmentMapper, times(1)).selectByIdForUpdate(SHIPMENT_ID);
        verify(traceEventService, times(1)).appendShipmentTransportEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("取消：装载清单包含已提交交接时拒绝取消")
    void cancel_rejectedWhenPendingTransferLoaded() {
        when(shipmentMapper.selectByIdForUpdate(SHIPMENT_ID)).thenReturn(shipment(ShipmentStatus.PLANNED));
        when(transferMapper.selectByShipmentIdForUpdate(SHIPMENT_ID)).thenReturn(List.of(transfer(5011L, 1011L, TransferStatus.PENDING)));

        assertThatThrownBy(() -> service.cancelShipment(SHIPMENT_ID, new ShipmentCancelRequest("计划变更", 3L), "idem-cancel-00000001", sender))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SHIPMENT_TRANSFER_NOT_DRAFT");
        verify(shipmentMapper, never()).updateLifecycleByIdAndVersion(any(), any(), any());
    }

    @Test
    @DisplayName("平台管理角色不可代办运输任务写操作")
    void platformScope_restricted() {
        TraceSecurityPrincipal admin = new TraceSecurityPrincipal(1L, "admin", "admin", "hash", 1L, "PLT", "平台", "PLATFORM",
                List.of("SYSTEM_ADMIN"), List.of("PLATFORM"), true, true);
        assertThatThrownBy(() -> service.createShipment(new ShipmentCreateRequest(30L, 1L, 2L, "浙A-1"), "idem-create-00000009", admin))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ADMIN_RESTRICTED");
    }
}
