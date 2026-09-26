package com.example.traceability.trace.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchSaleGuard;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
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
import com.example.traceability.trace.dto.ShipmentResponse;
import com.example.traceability.trace.mapper.ShipmentIdempotencyMapper;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 冷链运输任务应用服务。
 * <p>
 * 负责 Shipment 生命周期（PLANNED → IN_TRANSIT → DELIVERED；PLANNED → CANCELLED）编排（统一业务契约 v1.1 §7）：
 * <ul>
 *   <li>发货方创建 PLANNED 运输任务，绑定 / 解绑本组织 DRAFT 交接，取消 PLANNED 运输任务；</li>
 *   <li>承运商确认装载发运：全部关联交接必须已 PENDING，为每个 Batch 自动生成一条 TRANSPORT；</li>
 *   <li>承运商确认物理到达：为每个 Batch 自动生成一条 ARRIVAL；交接状态保持不变，由接收方另行 ACCEPT / REJECT；</li>
 *   <li>Shipment 从不修改 Batch 当前责任组织，承运商永远不会成为 Batch 当前责任组织。</li>
 * </ul>
 * 并发规则：所有改变装载清单或运输状态的操作统一遵循 shipment → transfer → batch 行锁顺序；
 * 装载清单每次变化递增运输任务版本，使持有旧版本号的并发发运请求被乐观锁检测为冲突。
 * </p>
 * <p>
 * Demo MVP Phase A 阶段性限制：承运方必须为独立的 {@code CARRIER} 类型组织，且承运组织不能作为发货方创建运输任务。
 * 统一业务契约 v1.1 未规定承运方必须与发送 / 接收方不同，因此该限制只在应用层执行，不写入数据库永久约束。
 * Demo MVP（Phase B PB2）阶段性限制：同一运输任务只装载同一产品的批次，作为契约 §7.1“兼容的运输温控规则”在多温区混装
 * 不在 MVP 范围内时的无歧义解释；同样只在应用层执行，不写入数据库永久约束。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Service
public class ShipmentApplicationService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter SHP_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    /** 业务时间允许超前服务端时钟的最大偏差。 */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final String CARRIER_ORG_TYPE = "CARRIER";

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_BIND = "BIND";
    private static final String ACTION_DISPATCH = "DISPATCH";
    private static final String ACTION_ARRIVE = "ARRIVE";
    private static final String ACTION_CANCEL = "CANCEL";

    private final ShipmentMapper shipmentMapper;
    private final ShipmentIdempotencyMapper idempotencyMapper;
    private final TransferMapper transferMapper;
    private final BatchMapper batchMapper;
    private final BatchOperationItemMapper batchOperationItemMapper;
    private final OrganizationMapper organizationMapper;
    private final SiteMapper siteMapper;
    private final TraceEventApplicationService traceEventService;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public ShipmentApplicationService(
            ShipmentMapper shipmentMapper,
            ShipmentIdempotencyMapper idempotencyMapper,
            TransferMapper transferMapper,
            BatchMapper batchMapper,
            BatchOperationItemMapper batchOperationItemMapper,
            OrganizationMapper organizationMapper,
            SiteMapper siteMapper,
            TraceEventApplicationService traceEventService,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.shipmentMapper = Objects.requireNonNull(shipmentMapper, "shipmentMapper 不能为空");
        this.idempotencyMapper = Objects.requireNonNull(idempotencyMapper, "idempotencyMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.batchOperationItemMapper = Objects.requireNonNull(batchOperationItemMapper, "batchOperationItemMapper 不能为空");
        this.organizationMapper = Objects.requireNonNull(organizationMapper, "organizationMapper 不能为空");
        this.siteMapper = Objects.requireNonNull(siteMapper, "siteMapper 不能为空");
        this.traceEventService = Objects.requireNonNull(traceEventService, "traceEventService 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    // =========================================================================
    // 发货方：创建 / 绑定 / 解绑 / 取消
    // =========================================================================

    /**
     * 发货方创建 PLANNED 运输任务 (POST /api/v1/shipments)。
     */
    @Transactional
    public ShipmentResponse createShipment(ShipmentCreateRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long senderOrgId = principal.getOrgId();
        String vehicleNo = req.vehicleOrContainerNo().trim();
        String requestHash = computeHash(ACTION_CREATE, req.carrierOrgId(), req.originSiteId(), req.destinationSiteId(), vehicleNo);

        ShipmentResponse replay = replayIfSameRequest(senderOrgId, cleanKey, ACTION_CREATE, requestHash, false);
        if (replay != null) {
            return replay;
        }

        if (CARRIER_ORG_TYPE.equalsIgnoreCase(principal.getOrgType())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_NOT_ALLOWED",
                    "组织类型不允许",
                    "Demo MVP Phase A 中承运组织只负责装载、发运与到达，不能作为发货方创建运输任务"
            );
        }

        Organization carrier = organizationMapper.selectById(req.carrierOrgId());
        if (carrier == null || !"ACTIVE".equalsIgnoreCase(carrier.getStatus())
                || !CARRIER_ORG_TYPE.equalsIgnoreCase(carrier.getOrgType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CARRIER_ORG_INVALID",
                    "承运组织不可用",
                    "承运组织不存在、已停用或不是承运企业 (CARRIER)；Demo MVP Phase A 要求由独立承运组织承运"
            );
        }

        Site origin = requireActiveSite(req.originSiteId(), "启运场所");
        if (!Objects.equals(origin.getOrgId(), senderOrgId)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SITE_ORG_MISMATCH",
                    "启运场所不属于发货方",
                    "启运场所必须属于当前发货组织"
            );
        }
        Site destination = requireActiveSite(req.destinationSiteId(), "目的场所");
        Long receiverOrgId = destination.getOrgId();
        if (Objects.equals(receiverOrgId, senderOrgId)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SHIPMENT_SAME_ORGANIZATION",
                    "无效运输组织",
                    "目的场所属于发货组织本身；企业间运输的目的场所必须属于接收方组织"
            );
        }
        Organization receiver = organizationMapper.selectById(receiverOrgId);
        if (receiver == null || !"ACTIVE".equalsIgnoreCase(receiver.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIVER_ORG_NOT_ACTIVE",
                    "接收组织不可用",
                    "目的场所所属的接收组织不存在或已被停用"
            );
        }
        if (CARRIER_ORG_TYPE.equalsIgnoreCase(receiver.getOrgType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIVER_ORG_TYPE_NOT_ALLOWED",
                    "接收组织类型不允许",
                    "承运组织不能作为运输任务接收方"
            );
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        Shipment shipment = new Shipment();
        shipment.setShipmentNo(generateShipmentNo(nowUtc));
        shipment.setSenderOrgId(senderOrgId);
        shipment.setReceiverOrgId(receiverOrgId);
        shipment.setCarrierOrgId(carrier.getId());
        shipment.setVehicleOrContainerNo(vehicleNo);
        shipment.setOriginSiteId(origin.getId());
        shipment.setDestinationSiteId(destination.getId());
        shipment.setStatus(ShipmentStatus.PLANNED);
        shipment.setVersion(0L);
        shipment.setIsDeleted(0);
        shipment.setCreatedAt(nowUtc);
        shipment.setCreatedBy(principal.getUserId());
        shipment.setUpdatedAt(nowUtc);
        shipment.setUpdatedBy(principal.getUserId());
        shipmentMapper.insert(shipment);

        if (!saveIdempotencyRecord(senderOrgId, cleanKey, ACTION_CREATE, shipment.getId(), requestHash, nowUtc)) {
            // 同键并发创建已由另一事务成功落库：抛出冲突让本事务回滚，客户端重试将重放原结果
            throw concurrentIdempotencyConflict();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("shipmentNo", shipment.getShipmentNo());
        summary.put("carrierOrgId", carrier.getId());
        summary.put("receiverOrgId", receiverOrgId);
        summary.put("originSiteId", origin.getId());
        summary.put("destinationSiteId", destination.getId());
        audit(principal, "CREATE", shipment.getId(), nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipment.getId()));
    }

    /**
     * 发货方把本组织 DRAFT 交接绑定到 PLANNED 运输任务 (POST /api/v1/shipments/{id}/transfers)。
     * 锁顺序：shipment → transfer → batch。
     */
    @Transactional
    public ShipmentResponse bindTransfer(
            Long shipmentId,
            ShipmentBindTransferRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        String requestHash = computeHash(ACTION_BIND, shipmentId, req.transferId(), req.expectedTransferVersion());

        ShipmentResponse replay = replayIfSameRequest(orgId, cleanKey, ACTION_BIND, requestHash, false);
        if (replay != null) {
            return replay;
        }

        Shipment shipment = lockShipment(shipmentId);
        replay = replayIfSameRequest(orgId, cleanKey, ACTION_BIND, requestHash, true);
        if (replay != null) {
            return replay;
        }
        requireSender(shipment, orgId, "仅运输任务发货方有权绑定交接");
        requirePlannedForManifestChange(shipment);

        Transfer transfer = transferMapper.selectByIdForUpdate(req.transferId());
        if (transfer == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + req.transferId() + " 的交接凭单");
        }
        if (!Objects.equals(transfer.getSenderOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅能绑定本组织发出的交接"
            );
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 DRAFT 交接允许绑定运输任务，当前状态为: " + transfer.getStatus()
            );
        }
        if (transfer.getShipmentId() != null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "TRANSFER_BOUND_TO_SHIPMENT",
                    "交接已绑定运输任务",
                    Objects.equals(transfer.getShipmentId(), shipmentId)
                            ? "交接已在当前运输任务的装载清单中"
                            : "交接已绑定其他运输任务，请先解绑"
            );
        }
        if (!Objects.equals(transfer.getVersion(), req.expectedTransferVersion())) {
            throw versionConflict("交接凭单版本已发生变化，请刷新后重试");
        }
        if (!Objects.equals(transfer.getReceiverOrgId(), shipment.getReceiverOrgId())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SHIPMENT_PARTY_MISMATCH",
                    "交接与运输任务参与方不一致",
                    "同一运输任务中的交接必须具有相同发送方、接收方、起点与终点；该交接的接收方与运输任务目的组织不一致"
            );
        }
        Set<Long> manifestBatchIds = new HashSet<>();
        for (Transfer loaded : transferMapper.selectByShipmentId(shipmentId)) {
            if (Objects.equals(loaded.getBatchId(), transfer.getBatchId())) {
                throw shipmentBatchDuplicate();
            }
            manifestBatchIds.add(loaded.getBatchId());
        }

        Batch batch = batchMapper.selectByIdForUpdate(transfer.getBatchId());
        if (batch == null) {
            throw new ResourceNotFoundException("未找到交接关联批次");
        }
        requireBatchTransferable(batch, orgId);
        // 防御性：已开始终端销售的批次不得装载交接（正常情况下交接创建时已被拒绝）
        BatchSaleGuard.rejectIfSaleStarted(batch, "装载交接");
        requireSameProductManifest(batch, manifestBatchIds);

        try {
            if (transferMapper.bindShipment(transfer.getId(), shipmentId, req.expectedTransferVersion(), principal.getUserId()) != 1) {
                throw versionConflict("交接绑定发生并发冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw shipmentBatchDuplicate();
        }
        bumpManifestVersion(shipmentId, principal.getUserId());

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (!saveIdempotencyRecord(orgId, cleanKey, ACTION_BIND, shipmentId, requestHash, nowUtc)) {
            throw concurrentIdempotencyConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transferId", transfer.getId());
        summary.put("batchId", batch.getId());
        audit(principal, "BIND_TRANSFER", shipmentId, nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipmentId));
    }

    /**
     * 发货方把 DRAFT 交接从 PLANNED 运输任务解绑
     * (DELETE /api/v1/shipments/{id}/transfers/{transferId}?expectedTransferVersion=)。
     * 锁顺序：shipment → transfer。
     */
    @Transactional
    public ShipmentResponse unbindTransfer(
            Long shipmentId,
            Long transferId,
            Long expectedTransferVersion,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        Long orgId = principal.getOrgId();

        Shipment shipment = lockShipment(shipmentId);
        requireSender(shipment, orgId, "仅运输任务发货方有权解绑交接");
        requirePlannedForManifestChange(shipment);

        Transfer transfer = transferMapper.selectByIdForUpdate(transferId);
        if (transfer == null || !Objects.equals(transfer.getShipmentId(), shipmentId)) {
            throw new ResourceNotFoundException("运输任务 " + shipmentId + " 的装载清单中不存在交接 " + transferId);
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 DRAFT 交接允许解绑，已提交 (" + transfer.getStatus() + ") 的交接不能移出装载清单"
            );
        }
        if (!Objects.equals(transfer.getVersion(), expectedTransferVersion)) {
            throw versionConflict("交接凭单版本已发生变化，请刷新后重试");
        }
        if (transferMapper.unbindShipment(transferId, shipmentId, expectedTransferVersion, principal.getUserId()) != 1) {
            throw versionConflict("交接解绑发生并发冲突，请刷新后重试");
        }
        bumpManifestVersion(shipmentId, principal.getUserId());

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transferId", transferId);
        audit(principal, "UNBIND_TRANSFER", shipmentId, nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipmentId));
    }

    /**
     * 发货方取消 PLANNED 运输任务 (POST /api/v1/shipments/{id}/cancel)。
     * 仅当装载清单中全部交接仍为 DRAFT 时允许取消，取消时一并解绑。锁顺序：shipment → transfer。
     */
    @Transactional
    public ShipmentResponse cancelShipment(
            Long shipmentId,
            ShipmentCancelRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        String reason = req.reason().trim();
        String requestHash = computeHash(ACTION_CANCEL, shipmentId, reason, req.expectedVersion());

        ShipmentResponse replay = replayIfSameRequest(orgId, cleanKey, ACTION_CANCEL, requestHash, false);
        if (replay != null) {
            return replay;
        }
        Shipment shipment = lockShipment(shipmentId);
        replay = replayIfSameRequest(orgId, cleanKey, ACTION_CANCEL, requestHash, true);
        if (replay != null) {
            return replay;
        }
        requireSender(shipment, orgId, "仅运输任务发货方有权取消运输任务");
        if (shipment.getStatus() != ShipmentStatus.PLANNED) {
            throw invalidTransition("仅 PLANNED 运输任务允许取消，当前状态为: " + shipment.getStatus());
        }
        if (!Objects.equals(shipment.getVersion(), req.expectedVersion())) {
            throw versionConflict("运输任务版本已发生变化（装载清单可能已变更），请刷新后重试");
        }

        List<Transfer> transfers = transferMapper.selectByShipmentIdForUpdate(shipmentId);
        for (Transfer t : transfers) {
            if (t.getStatus() != TransferStatus.DRAFT) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "SHIPMENT_TRANSFER_NOT_DRAFT",
                        "装载清单包含已提交交接",
                        "交接 " + t.getTransferNo() + " 已提交为 " + t.getStatus() + "，运输任务不能取消"
                );
            }
        }
        for (Transfer t : transfers) {
            if (transferMapper.unbindShipment(t.getId(), shipmentId, t.getVersion(), principal.getUserId()) != 1) {
                throw versionConflict("取消运输任务时解绑交接发生并发冲突，请刷新后重试");
            }
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setCancelledRecordedAt(nowUtc);
        shipment.setCancelledBy(principal.getUserId());
        shipment.setCancelReason(reason);
        shipment.setUpdatedBy(principal.getUserId());
        if (shipmentMapper.updateLifecycleByIdAndVersion(shipment, ShipmentStatus.PLANNED.name(), req.expectedVersion()) != 1) {
            throw versionConflict("取消运输任务发生并发版本冲突");
        }

        if (!saveIdempotencyRecord(orgId, cleanKey, ACTION_CANCEL, shipmentId, requestHash, nowUtc)) {
            throw concurrentIdempotencyConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reason", reason);
        summary.put("unboundTransferCount", transfers.size());
        audit(principal, "CANCEL", shipmentId, nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipmentId));
    }

    // =========================================================================
    // 承运商：发运 / 到达
    // =========================================================================

    /**
     * 承运商确认装载发运 (POST /api/v1/shipments/{id}/dispatch)，PLANNED → IN_TRANSIT。
     * <p>
     * 装载清单全部交接必须已 PENDING；为每个 Batch 在同一事务内自动生成且仅生成一条 TRANSPORT。
     * 锁顺序：shipment → transfer（按主键）。
     * </p>
     */
    @Transactional
    public ShipmentResponse dispatchShipment(
            Long shipmentId,
            ShipmentDispatchRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        LocalDateTime loadedAtUtc = toUtc(req.loadedAt());
        String requestHash = computeHash(ACTION_DISPATCH, shipmentId, loadedAtUtc, req.expectedVersion());

        ShipmentResponse replay = replayIfSameRequest(orgId, cleanKey, ACTION_DISPATCH, requestHash, false);
        if (replay != null) {
            return replay;
        }
        Shipment shipment = lockShipment(shipmentId);
        replay = replayIfSameRequest(orgId, cleanKey, ACTION_DISPATCH, requestHash, true);
        if (replay != null) {
            return replay;
        }
        requireCarrier(shipment, orgId);
        if (shipment.getStatus() != ShipmentStatus.PLANNED) {
            throw invalidTransition("仅 PLANNED 运输任务允许确认装载发运，当前状态为: " + shipment.getStatus());
        }
        if (!Objects.equals(shipment.getVersion(), req.expectedVersion())) {
            throw versionConflict("运输任务版本已发生变化（装载清单可能已变更），请刷新后核对装载清单再发运");
        }
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        requireNotFuture(loadedAtUtc, nowUtc, "装载发运时间 loadedAt");

        List<Transfer> transfers = transferMapper.selectByShipmentIdForUpdate(shipmentId);
        if (transfers.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_EMPTY",
                    "装载清单为空",
                    "运输任务尚未绑定任何交接，不能发运"
            );
        }
        for (Transfer t : transfers) {
            if (t.getStatus() != TransferStatus.PENDING) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "SHIPMENT_TRANSFER_NOT_PENDING",
                        "装载清单包含未提交交接",
                        "交接 " + t.getTransferNo() + " 当前为 " + t.getStatus() + "，发运前全部关联交接必须已由发货方提交 (PENDING)"
                );
            }
        }
        Map<Long, Batch> batches = loadBatches(transfers);
        for (Transfer t : transfers) {
            Batch b = batches.get(t.getBatchId());
            if (b == null || !Objects.equals(b.getOrgId(), shipment.getSenderOrgId())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "BATCH_CONCURRENT_CONFLICT",
                        "批次责任组织异常",
                        "装载批次当前责任组织与运输任务发货方不一致，禁止发运"
                );
            }
        }

        shipment.setStatus(ShipmentStatus.IN_TRANSIT);
        shipment.setLoadedAt(loadedAtUtc);
        shipment.setDispatchedRecordedAt(nowUtc);
        shipment.setDispatchedBy(principal.getUserId());
        shipment.setUpdatedBy(principal.getUserId());
        if (shipmentMapper.updateLifecycleByIdAndVersion(shipment, ShipmentStatus.PLANNED.name(), req.expectedVersion()) != 1) {
            throw versionConflict("确认发运发生并发版本冲突");
        }

        TraceEventApplicationService.ShipmentProjection projection = projection(shipment);
        for (Transfer t : transfers) {
            traceEventService.appendShipmentTransportEvent(
                    projection, t.getBatchId(), batches.get(t.getBatchId()).getTraceBatchNo(), t.getId(),
                    principal.getUserId(), nowUtc);
        }

        if (!saveIdempotencyRecord(orgId, cleanKey, ACTION_DISPATCH, shipmentId, requestHash, nowUtc)) {
            throw concurrentIdempotencyConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("loadedAt", loadedAtUtc.toString());
        summary.put("transportEventCount", transfers.size());
        audit(principal, "DISPATCH", shipmentId, nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipmentId));
    }

    /**
     * 承运商确认物理到达 (POST /api/v1/shipments/{id}/arrive)，IN_TRANSIT → DELIVERED。
     * <p>
     * 为每个 Batch 在同一事务内自动生成且仅生成一条 ARRIVAL；不修改任何交接状态，不改变 Batch 当前责任组织。
     * </p>
     */
    @Transactional
    public ShipmentResponse arriveShipment(
            Long shipmentId,
            ShipmentArriveRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        LocalDateTime unloadedAtUtc = toUtc(req.unloadedAt());
        String requestHash = computeHash(ACTION_ARRIVE, shipmentId, unloadedAtUtc, req.expectedVersion());

        ShipmentResponse replay = replayIfSameRequest(orgId, cleanKey, ACTION_ARRIVE, requestHash, false);
        if (replay != null) {
            return replay;
        }
        Shipment shipment = lockShipment(shipmentId);
        replay = replayIfSameRequest(orgId, cleanKey, ACTION_ARRIVE, requestHash, true);
        if (replay != null) {
            return replay;
        }
        requireCarrier(shipment, orgId);
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT) {
            throw invalidTransition("仅 IN_TRANSIT 运输任务允许确认到达，当前状态为: " + shipment.getStatus());
        }
        if (!Objects.equals(shipment.getVersion(), req.expectedVersion())) {
            throw versionConflict("运输任务版本已发生变化，请刷新后重试");
        }
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        requireNotFuture(unloadedAtUtc, nowUtc, "到达时间 unloadedAt");
        if (unloadedAtUtc.isBefore(shipment.getLoadedAt())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_BUSINESS_TIME",
                    "业务时间不合法",
                    "到达时间不能早于装载发运时间 (" + shipment.getLoadedAt().atOffset(ZoneOffset.UTC) + ")"
            );
        }

        // IN_TRANSIT 后装载清单已冻结，普通读即可稳定获得全部交接
        List<Transfer> transfers = transferMapper.selectByShipmentId(shipmentId);
        Map<Long, Batch> batches = loadBatches(transfers);

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setUnloadedAt(unloadedAtUtc);
        shipment.setDeliveredRecordedAt(nowUtc);
        shipment.setDeliveredBy(principal.getUserId());
        shipment.setUpdatedBy(principal.getUserId());
        if (shipmentMapper.updateLifecycleByIdAndVersion(shipment, ShipmentStatus.IN_TRANSIT.name(), req.expectedVersion()) != 1) {
            throw versionConflict("确认到达发生并发版本冲突");
        }

        TraceEventApplicationService.ShipmentProjection projection = projection(shipment);
        for (Transfer t : transfers) {
            Batch b = batches.get(t.getBatchId());
            traceEventService.appendShipmentArrivalEvent(
                    projection, t.getBatchId(), b != null ? b.getTraceBatchNo() : null, t.getId(),
                    principal.getUserId(), nowUtc);
        }

        if (!saveIdempotencyRecord(orgId, cleanKey, ACTION_ARRIVE, shipmentId, requestHash, nowUtc)) {
            throw concurrentIdempotencyConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("unloadedAt", unloadedAtUtc.toString());
        summary.put("arrivalEventCount", transfers.size());
        audit(principal, "ARRIVE", shipmentId, nowUtc, summary);

        return toResponse(shipmentMapper.selectById(shipmentId));
    }

    // =========================================================================
    // 查询
    // =========================================================================

    /**
     * 查询运输任务详情 (GET /api/v1/shipments/{id})：发送方、承运方、接收方可读（含历史参与后的只读查询）。
     */
    public ShipmentResponse getShipment(Long shipmentId, TraceSecurityPrincipal principal) {
        Shipment shipment = isPlatformScope(principal)
                ? shipmentMapper.selectById(shipmentId)
                : shipmentMapper.selectByIdAndPartyScope(shipmentId, principal.getOrgId());
        if (shipment == null || Objects.equals(shipment.getIsDeleted(), 1)) {
            if (shipmentMapper.existsByIdIgnoreTenant(shipmentId) == 0) {
                throw new ResourceNotFoundException("未找到 ID 为 " + shipmentId + " 的运输任务");
            }
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅运输任务的发货方、承运方与接收方可以查看该运输任务"
            );
        }
        return toResponse(shipment);
    }

    /**
     * 分页查询当前组织参与的运输任务 (GET /api/v1/shipments)。
     */
    public List<ShipmentResponse> listShipments(String role, String status, long page, int size, TraceSecurityPrincipal principal) {
        long safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        long offset = (safePage - 1) * safeSize;
        List<Shipment> shipments = shipmentMapper.selectShipmentsPage(principal.getOrgId(), role, status, offset, safeSize);
        return toResponses(shipments);
    }

    /**
     * 统计当前组织参与的运输任务总数。
     */
    public long countShipments(String role, String status, TraceSecurityPrincipal principal) {
        return shipmentMapper.countShipments(principal.getOrgId(), role, status);
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    private ShipmentResponse toResponse(Shipment shipment) {
        return toResponses(List.of(shipment)).get(0);
    }

    private List<ShipmentResponse> toResponses(List<Shipment> shipments) {
        if (shipments.isEmpty()) {
            return List.of();
        }
        Set<Long> orgIds = new HashSet<>();
        Set<Long> siteIds = new HashSet<>();
        Map<Long, List<Transfer>> transfersByShipment = new HashMap<>();
        Set<Long> batchIds = new HashSet<>();
        for (Shipment s : shipments) {
            orgIds.add(s.getSenderOrgId());
            orgIds.add(s.getReceiverOrgId());
            orgIds.add(s.getCarrierOrgId());
            siteIds.add(s.getOriginSiteId());
            siteIds.add(s.getDestinationSiteId());
            List<Transfer> transfers = transferMapper.selectByShipmentId(s.getId());
            transfersByShipment.put(s.getId(), transfers);
            transfers.forEach(t -> batchIds.add(t.getBatchId()));
        }
        Map<Long, Organization> orgs = organizationMapper.selectByIdsIgnoreTenant(orgIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        Map<Long, Site> sites = siteMapper.selectByIds(siteIds).stream()
                .collect(Collectors.toMap(Site::getId, Function.identity()));
        Map<Long, Batch> batches = batchIds.isEmpty()
                ? Map.of()
                : batchMapper.selectByIdsIgnoreTenant(batchIds).stream()
                        .collect(Collectors.toMap(Batch::getId, Function.identity()));
        return shipments.stream()
                .map(s -> ShipmentResponse.of(s, orgs, sites, transfersByShipment.get(s.getId()), batches))
                .toList();
    }

    private Map<Long, Batch> loadBatches(Collection<Transfer> transfers) {
        Set<Long> ids = transfers.stream().map(Transfer::getBatchId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return batchMapper.selectByIdsIgnoreTenant(ids).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
    }

    private TraceEventApplicationService.ShipmentProjection projection(Shipment s) {
        Organization carrier = organizationMapper.selectById(s.getCarrierOrgId());
        Site origin = siteMapper.selectByIdIgnoreTenant(s.getOriginSiteId());
        Site destination = siteMapper.selectByIdIgnoreTenant(s.getDestinationSiteId());
        return new TraceEventApplicationService.ShipmentProjection(
                s.getId(),
                s.getShipmentNo(),
                s.getSenderOrgId(),
                s.getCarrierOrgId(),
                carrier != null ? carrier.getName() : null,
                s.getVehicleOrContainerNo(),
                s.getOriginSiteId(),
                origin != null ? origin.getName() : null,
                s.getDestinationSiteId(),
                destination != null ? destination.getName() : null,
                s.getLoadedAt(),
                s.getUnloadedAt()
        );
    }

    private Shipment lockShipment(Long shipmentId) {
        Shipment shipment = shipmentMapper.selectByIdForUpdate(shipmentId);
        if (shipment == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + shipmentId + " 的运输任务");
        }
        return shipment;
    }

    private void requireSender(Shipment shipment, Long orgId, String detail) {
        if (!Objects.equals(shipment.getSenderOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", detail);
        }
    }

    private void requireCarrier(Shipment shipment, Long orgId) {
        if (!Objects.equals(shipment.getCarrierOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅运输任务指定的承运组织有权确认装载发运与到达"
            );
        }
    }

    private void requirePlannedForManifestChange(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.PLANNED) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_PLANNED",
                    "运输任务已不可变更装载清单",
                    "运输任务当前状态为 " + shipment.getStatus() + "，仅 PLANNED 运输任务允许增删交接"
            );
        }
    }

    private void requireBatchTransferable(Batch batch, Long senderOrgId) {
        if (!Objects.equals(batch.getOrgId(), senderOrgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "批次当前责任组织与发货方不一致，禁止装载"
            );
        }
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不可交接",
                    "批次当前状态不可交接 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus()
                            + ")，仅 ACTIVE 且 NORMAL 状态批次允许装载"
            );
        }
        if (batchOperationItemMapper.countSubmittedInputUsageByBatchId(batch.getId()) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_ALREADY_CONSUMED",
                    "批次已被物料操作消耗",
                    "批次已被已提交的批次操作作为投入(INPUT)消耗，禁止装载交接"
            );
        }
    }

    /**
     * Demo MVP（PB2）应用层限制：同一运输任务只装载同一产品的批次。
     * <p>
     * 契约 v1.1 §7.1 要求同一 Shipment 的 Transfer 具有兼容的运输温控规则，但未定义“兼容”；规则按产品、环节与测量业务时间
     * 版本化选择（§10.1），多温区混装是 MVP 非目标（§1.1）。只有“同一产品”在整个运输期间始终对应唯一规则版本序列，
     * 因此 Demo MVP 以此作为无歧义的解释。这不是契约对所有运输任务的普遍要求，不写入数据库永久约束。
     * 批次产品创建后不可变，装载清单上其他批次的产品用非锁定读即可稳定获得，不引入新的锁。
     * </p>
     */
    private void requireSameProductManifest(Batch batch, Set<Long> manifestBatchIds) {
        if (manifestBatchIds.isEmpty()) {
            return;
        }
        for (Batch loaded : batchMapper.selectByIdsIgnoreTenant(manifestBatchIds)) {
            if (!Objects.equals(loaded.getProductId(), batch.getProductId())) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "SHIPMENT_PRODUCT_MISMATCH",
                        "装载产品不一致",
                        "Demo MVP 阶段同一运输任务只装载同一产品的批次，以保证运输温控规则唯一（多温区混装不在 MVP 范围内）；"
                                + "该交接的批次产品与装载清单中已有批次不同，请为其另建运输任务"
                );
            }
        }
    }

    private void bumpManifestVersion(Long shipmentId, Long userId) {
        if (shipmentMapper.bumpManifestVersion(shipmentId, userId) != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_PLANNED",
                    "运输任务已不可变更装载清单",
                    "运输任务已非 PLANNED，装载清单变更已回滚"
            );
        }
    }

    private Site requireActiveSite(Long siteId, String label) {
        Site site = siteMapper.selectByIdIgnoreTenant(siteId);
        if (site == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + siteId + " 的" + label);
        }
        if (!"ACTIVE".equalsIgnoreCase(site.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SITE_NOT_ACTIVE",
                    "场所未启用",
                    label + "未处于启用状态，当前状态为: " + site.getStatus()
            );
        }
        return site;
    }

    private void requireNotFuture(LocalDateTime businessTimeUtc, LocalDateTime nowUtc, String fieldName) {
        if (businessTimeUtc.isAfter(nowUtc.plus(MAX_FUTURE_SKEW))) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_BUSINESS_TIME",
                    "业务时间不合法",
                    fieldName + " 不能晚于当前时间"
            );
        }
    }

    private static LocalDateTime toUtc(OffsetDateTime time) {
        return time.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 幂等重放：同组织同键同动作同语义返回运输任务当前结果；同键不同动作或不同语义返回 409 IDEMPOTENCY_CONFLICT。
     *
     * @param locked true 表示在已持有运输任务行锁后执行当前锁定读，消除快照盲区
     * @return 重放响应；未命中返回 null
     */
    private ShipmentResponse replayIfSameRequest(Long orgId, String key, String action, String requestHash, boolean locked) {
        ShipmentIdempotency existing = locked
                ? idempotencyMapper.selectByOrgIdAndKeyForUpdate(orgId, key)
                : idempotencyMapper.selectByOrgIdAndKey(orgId, key);
        if (existing == null) {
            return null;
        }
        if (Objects.equals(existing.getAction(), action) && Objects.equals(existing.getRequestHash(), requestHash)) {
            Shipment shipment = shipmentMapper.selectById(existing.getShipmentId());
            if (shipment != null) {
                return toResponse(shipment);
            }
        }
        throw new BusinessException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "幂等提交冲突",
                "当前幂等键已被本组织用于其他动作或不同语义的运输任务请求"
        );
    }

    /**
     * @return true 表示已写入；false 表示同键同语义记录已由并发事务写入
     */
    private boolean saveIdempotencyRecord(
            Long orgId,
            String key,
            String action,
            Long shipmentId,
            String requestHash,
            LocalDateTime nowUtc
    ) {
        ShipmentIdempotency entity = new ShipmentIdempotency();
        entity.setOrgId(orgId);
        entity.setIdempotencyKey(key);
        entity.setAction(action);
        entity.setShipmentId(shipmentId);
        entity.setRequestHash(requestHash);
        entity.setCreatedAt(nowUtc);
        try {
            idempotencyMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            ShipmentIdempotency dup = idempotencyMapper.selectByOrgIdAndKeyForUpdate(orgId, key);
            if (dup != null && Objects.equals(dup.getAction(), action) && Objects.equals(dup.getRequestHash(), requestHash)) {
                return false;
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "并发幂等冲突",
                    "并发检测到相同幂等键，且请求载荷与已落库数据不一致"
            );
        }
    }

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ADMIN_RESTRICTED",
                    "管理员权限受限",
                    "平台管理角色不可代办具体企业的运输任务"
            );
        }
        if (!principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_NOT_ALLOWED",
                    "角色权限不足",
                    "仅企业操作员 (OPERATOR) 允许执行运输任务写操作"
            );
        }
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "幂等键缺失",
                    "请求头 Idempotency-Key 不能为空"
            );
        }
        String clean = key.trim();
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(clean).matches()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "幂等键格式不合法",
                    "Idempotency-Key 必须由 16 至 128 位数字、字母、点、减号、下划线或冒号组成"
            );
        }
        return clean;
    }

    private String computeHash(String action, Object... params) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(action.getBytes(StandardCharsets.UTF_8));
            for (Object param : params) {
                digest.update((byte) 0x1F);
                if (param == null) {
                    digest.update((byte) 0);
                } else {
                    digest.update(param.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    private void audit(TraceSecurityPrincipal principal, String action, Long shipmentId, LocalDateTime nowUtc, Map<String, Object> summary) {
        String json;
        try {
            json = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
        auditService.recordAudit(principal.getUserId(), principal.getOrgId(), action, "SHIPMENT", shipmentId, nowUtc, "SUCCESS", json);
    }

    private static BusinessException versionConflict(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突", detail);
    }

    private static BusinessException invalidTransition(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "非法状态流转", detail);
    }

    private static BusinessException shipmentBatchDuplicate() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "SHIPMENT_BATCH_DUPLICATE",
                "批次已在装载清单中",
                "同一批次在同一运输任务中只能出现一次"
        );
    }

    private static BusinessException concurrentIdempotencyConflict() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "并发幂等冲突",
                "相同幂等键的并发请求已由另一事务完成，请使用相同幂等键重试以获取原结果"
        );
    }

    private static String generateShipmentNo(LocalDateTime now) {
        return "SHP-" + now.format(SHP_NO_FORMAT) + "-" + (RANDOM.nextInt(9000) + 1000);
    }
}
