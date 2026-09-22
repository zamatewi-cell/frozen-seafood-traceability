package com.example.traceability.trace.application;

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
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferIdempotency;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferPatchRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import com.example.traceability.trace.dto.TransferResponse;
import com.example.traceability.trace.dto.TransferSubmitRequest;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferIdempotencyMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
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
 * 企业间整批交接应用服务。
 * <p>
 * 负责整批交接生命周期（DRAFT -> PENDING -> ACCEPTED / REJECTED）业务编排。Transfer 只表达责任交接，
 * 物理运输由 Shipment 承担（统一业务契约 v1.1 §7）：
 * <ul>
 *   <li>创建交接草稿：批次快照锁定，排他活跃校验，防已消耗批次流转，uk_transfer_open_batch 冲突安全捕获；
 *       接收方必须为启用的非承运组织（承运商不会成为批次当前责任组织）；</li>
 *   <li>修改与逻辑删除草稿：严格限制发送方与 DRAFT 阶段；已绑定运输任务的草稿禁止修改接收方，
 *       删除已绑定草稿时按 shipment → transfer 顺序加锁并递增运输任务版本；</li>
 *   <li>发送方提交：必须已绑定 PLANNED 运输任务，形成批次 PENDING 业务预留，校验批次归属人防篡改与 INPUT 消耗拦截；</li>
 *   <li>接收方接受：关联运输任务必须已 DELIVERED；实收计量单位匹配与正数量校验，差异强制说明，
 *       原子转移批次及公开追溯码当前责任组织；不生成 ARRIVAL（ARRIVAL 唯一来源为 Shipment 到达）；</li>
 *   <li>接收方拒收：关联运输任务必须已 DELIVERED；保存拒收原因，不转移批次，不写追溯事件；</li>
 *   <li>统一行锁顺序 shipment → transfer → batch，避免与运输任务发运 / 装载清单变更并发时死锁；</li>
 *   <li>多动作统一幂等：基于 SHA-256 规范化语义哈希（消除 BigDecimal 尾随零歧义），结合行级锁后当前读消除快照盲区；</li>
 *   <li>SQL 跨租户数据隔离防越权与审计 JSON 安全序列化。</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class TransferApplicationService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TRF_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final TransferMapper transferMapper;
    private final TransferIdempotencyMapper idempotencyMapper;
    private final BatchMapper batchMapper;
    private final BatchOperationItemMapper batchOperationItemMapper;
    private final OrganizationMapper organizationMapper;
    private final AuditApplicationService auditService;
    private final PublicTraceCodeMapper publicTraceCodeMapper;
    private final ShipmentMapper shipmentMapper;
    private final ObjectMapper objectMapper;

    public TransferApplicationService(
            TransferMapper transferMapper,
            TransferIdempotencyMapper idempotencyMapper,
            BatchMapper batchMapper,
            BatchOperationItemMapper batchOperationItemMapper,
            OrganizationMapper organizationMapper,
            AuditApplicationService auditService,
            PublicTraceCodeMapper publicTraceCodeMapper,
            ShipmentMapper shipmentMapper,
            ObjectMapper objectMapper
    ) {
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.idempotencyMapper = Objects.requireNonNull(idempotencyMapper, "idempotencyMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.batchOperationItemMapper = Objects.requireNonNull(batchOperationItemMapper, "batchOperationItemMapper 不能为空");
        this.organizationMapper = Objects.requireNonNull(organizationMapper, "organizationMapper 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.publicTraceCodeMapper = Objects.requireNonNull(publicTraceCodeMapper, "publicTraceCodeMapper 不能为空");
        this.shipmentMapper = Objects.requireNonNull(shipmentMapper, "shipmentMapper 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 创建企业间整批交接草稿 (POST /api/v1/transfers)。
     *
     * @param req            创建交接草稿请求
     * @param idempotencyKey 客户端防重幂等键
     * @param principal      当前操作人主体
     * @return 创建或幂等重放的交接记录详情
     */
    @Transactional
    public TransferResponse createDraft(
            TransferCreateRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long senderOrgId = principal.getOrgId();

        String action = "CREATE";
        String requestHash = computeHash(action, req.batchId(), req.receiverOrgId());

        // 1. 幂等预检（优先于所有可变业务校验，确保已成功请求重放始终返回原 transfer；同 key 不同语义优先 409 IDEMPOTENCY_CONFLICT）
        TransferIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(senderOrgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action) && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(existingIdem.getTransferId());
                if (existingTransfer != null) {
                    return toResponse(existingTransfer);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前幂等键已被本组织用于其他动作或不同语义的创建请求"
            );
        }

        // 2. 组织间交接基本规则校验
        if (Objects.equals(senderOrgId, req.receiverOrgId())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TRANSFER_SAME_ORGANIZATION",
                    "无效交接组织",
                    "发货企业与收货企业不能为同一组织"
            );
        }

        Organization receiverOrg = organizationMapper.selectById(req.receiverOrgId());
        if (receiverOrg == null || !"ACTIVE".equalsIgnoreCase(receiverOrg.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIVER_ORG_NOT_ACTIVE",
                    "接收组织不可用",
                    "接收方企业组织不存在或已被停用"
            );
        }
        checkReceiverOrgType(receiverOrg);

        // 2. 锁定关联批次并校验归属与状态
        Batch batch = batchMapper.selectByIdForUpdate(req.batchId());
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + req.batchId() + " 的关联批次");
        }
        if (!Objects.equals(batch.getOrgId(), senderOrgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅能对当前所属企业持有的批次发起交接"
            );
        }
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_NOT_ACTIVE",
                    "批次状态不可交接",
                    "当前批次流转或风险状态不可交接 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")，仅 ACTIVE 且 NORMAL 状态批次允许发起交接"
            );
        }

        // 3. 检查批次是否已被已提交批次操作作为 INPUT 消耗
        if (batchOperationItemMapper.countSubmittedInputUsageByBatchId(batch.getId()) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_ALREADY_CONSUMED",
                    "批次已被物料操作消耗",
                    "批次已被已提交的批次操作作为投入(INPUT)消耗，禁止直接交接；请先通过 SPLIT 拆分新批次后再交接"
            );
        }

        // 4. 检查批次是否已有未结束交接 (DRAFT/PENDING 排他)
        if (transferMapper.countActiveTransfersByBatchId(batch.getId()) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_TRANSFER_CONFLICT",
                    "批次存在未结束交接",
                    "当前批次已存在处于草稿(DRAFT)或在途确认(PENDING)中的交接凭单，同一时刻只允许一个未完成交接"
            );
        }

        // 5. 构造交接实体并持久化
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String transferNo = generateTransferNo(nowUtc);

        Transfer transfer = new Transfer();
        transfer.setTransferNo(transferNo);
        transfer.setBatchId(batch.getId());
        transfer.setSenderOrgId(senderOrgId);
        transfer.setReceiverOrgId(req.receiverOrgId());
        transfer.setQuantity(batch.getQuantity());
        transfer.setUnitCode(batch.getUnitCode());
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setIdempotencyKey(cleanKey);
        transfer.setVersion(0L);
        transfer.setIsDeleted(0);
        transfer.setIsLegacy(0);
        transfer.setCreatedAt(nowUtc);
        transfer.setCreatedBy(principal.getUserId());
        transfer.setUpdatedAt(nowUtc);
        transfer.setUpdatedBy(principal.getUserId());

        try {
            transferMapper.insert(transfer);
        } catch (DuplicateKeyException e) {
            // 当前读恢复检查是否为同一幂等键重试，否则映射为 409 BATCH_TRANSFER_CONFLICT（绝不冒泡 500）
            TransferIdempotency dup = idempotencyMapper.selectByOrgIdAndKeyForUpdate(senderOrgId, cleanKey);
            if (dup != null && Objects.equals(dup.getAction(), action) && Objects.equals(dup.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(dup.getTransferId());
                if (existingTransfer != null) {
                    return toResponse(existingTransfer);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_TRANSFER_CONFLICT",
                    "批次存在未结束交接",
                    "当前批次已存在未结束交接或发生并发创建冲突"
            );
        }

        // 6. 持久化幂等记录
        saveIdempotencyRecord(senderOrgId, cleanKey, action, transfer.getId(), requestHash, nowUtc);

        // 7. 记录审计日志
        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("transferNo", transferNo);
        auditSummary.put("batchId", batch.getId());
        auditSummary.put("receiverOrgId", req.receiverOrgId());
        auditSummary.put("quantity", batch.getQuantity().stripTrailingZeros().toPlainString());

        auditService.recordAudit(
                principal.getUserId(),
                senderOrgId,
                "CREATE",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );

        return toResponse(transfer);
    }

    /**
     * 发送方修改交接草稿 (PATCH /api/v1/transfers/{transferId})。
     */
    @Transactional
    public TransferResponse patchDraft(
            Long transferId,
            TransferPatchRequest req,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        Long orgId = principal.getOrgId();

        Transfer transfer = transferMapper.selectByIdForUpdate(transferId);
        if (transfer == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + transferId + " 的交接凭单");
        }
        if (!Objects.equals(transfer.getSenderOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅发送企业有权修改交接草稿"
            );
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 DRAFT 草稿状态允许修改，当前状态为: " + transfer.getStatus()
            );
        }
        if (transfer.getShipmentId() != null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "TRANSFER_BOUND_TO_SHIPMENT",
                    "交接已绑定运输任务",
                    "交接草稿已绑定运输任务，接收方必须与运输任务一致；如需更换接收方请先从运输任务解绑"
            );
        }
        if (Objects.equals(orgId, req.receiverOrgId())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TRANSFER_SAME_ORGANIZATION",
                    "无效交接组织",
                    "发货企业与收货企业不能为同一组织"
            );
        }
        if (!Objects.equals(transfer.getVersion(), req.expectedVersion())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生变化，请刷新后重试"
            );
        }

        Organization receiverOrg = organizationMapper.selectById(req.receiverOrgId());
        if (receiverOrg == null || !"ACTIVE".equalsIgnoreCase(receiverOrg.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIVER_ORG_NOT_ACTIVE",
                    "接收组织不可用",
                    "接收方企业组织不存在或已被停用"
            );
        }
        checkReceiverOrgType(receiverOrg);

        Long expectedSenderOrgId = transfer.getSenderOrgId();
        Long expectedReceiverOrgId = transfer.getReceiverOrgId();
        String expectedStatus = TransferStatus.DRAFT.name();

        transfer.setReceiverOrgId(req.receiverOrgId());
        transfer.setUpdatedBy(principal.getUserId());

        int updated = transferMapper.updateByIdAndVersion(
                transfer,
                expectedSenderOrgId,
                expectedReceiverOrgId,
                expectedStatus,
                req.expectedVersion()
        );
        if (updated == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生并发冲突，更新失败"
            );
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("newReceiverOrgId", req.receiverOrgId());

        auditService.recordAudit(
                principal.getUserId(),
                orgId,
                "UPDATE",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );

        transfer.setVersion(req.expectedVersion() + 1);
        Transfer updatedTransfer = transferMapper.selectById(transferId);
        return toResponse(updatedTransfer != null ? updatedTransfer : transfer);
    }

    /**
     * 发送方逻辑删除交接草稿 (DELETE /api/v1/transfers/{transferId})。
     */
    @Transactional
    public void deleteDraft(
            Long transferId,
            Long expectedVersion,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        Long orgId = principal.getOrgId();

        // 装载清单变更统一遵循 shipment → transfer → batch 锁顺序：已绑定草稿先锁运输任务，再锁交接
        LockedTransfer locked = lockShipmentThenTransfer(transferId, true);
        Transfer transfer = locked.transfer();
        Shipment boundShipment = locked.shipment();
        if (!Objects.equals(transfer.getSenderOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅发送企业有权删除交接草稿"
            );
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 DRAFT 草稿状态允许删除，当前状态为: " + transfer.getStatus()
            );
        }
        if (!Objects.equals(transfer.getVersion(), expectedVersion)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生变化，请刷新后重试"
            );
        }

        Long expectedSenderOrgId = transfer.getSenderOrgId();
        Long expectedReceiverOrgId = transfer.getReceiverOrgId();
        String expectedStatus = TransferStatus.DRAFT.name();

        if (boundShipment != null && boundShipment.getStatus() != ShipmentStatus.PLANNED) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_PLANNED",
                    "运输任务已不可变更装载清单",
                    "交接已绑定的运输任务当前状态为 " + boundShipment.getStatus() + "，仅 PLANNED 运输任务允许变更装载清单"
            );
        }

        transfer.setIsDeleted(1);
        transfer.setShipmentId(null);
        transfer.setUpdatedBy(principal.getUserId());

        int updated = transferMapper.updateByIdAndVersion(
                transfer,
                expectedSenderOrgId,
                expectedReceiverOrgId,
                expectedStatus,
                expectedVersion
        );
        if (updated == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单删除发生并发版本冲突"
            );
        }
        if (boundShipment != null) {
            bumpShipmentManifestVersion(boundShipment.getId(), principal.getUserId());
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("deletedTransferNo", transfer.getTransferNo());
        if (boundShipment != null) {
            auditSummary.put("unboundShipmentId", boundShipment.getId());
        }

        auditService.recordAudit(
                principal.getUserId(),
                orgId,
                "DELETE",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );
    }

    /**
     * 发送方提交交接 (POST /api/v1/transfers/{transferId}/submit)。
     */
    @Transactional
    public TransferResponse submitTransfer(
            Long transferId,
            TransferSubmitRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();

        String action = "SUBMIT";
        String requestHash = computeHash(action, transferId, req.expectedVersion());

        // 1. 幂等普通读预检
        TransferIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(orgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action) && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(existingIdem.getTransferId());
                if (existingTransfer != null) {
                    return toResponse(existingTransfer);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前幂等键已被本组织用于其他动作或不同语义的提交请求"
            );
        }

        // 2. 按 shipment → transfer 顺序锁定绑定的运输任务与交接记录 (SELECT ... FOR UPDATE)
        LockedTransfer locked = lockShipmentThenTransfer(transferId, true);
        Transfer transfer = locked.transfer();
        Shipment shipment = locked.shipment();

        // 3. 锁定后当前读排他幂等检查（关键：消除快照盲区，慢并发线程安全恢复）
        TransferIdempotency lockedIdem = idempotencyMapper.selectByOrgIdAndKeyForUpdate(orgId, cleanKey);
        if (lockedIdem != null) {
            if (Objects.equals(lockedIdem.getAction(), action) && Objects.equals(lockedIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(transferId);
                return toResponse(existingTransfer != null ? existingTransfer : transfer);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "并发幂等冲突",
                    "当前幂等键已被本组织其他并发提交占用"
            );
        }

        // 4. 身份与状态校验
        if (!Objects.equals(transfer.getSenderOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅发送企业有权提交交接"
            );
        }
        if (transfer.getStatus() != TransferStatus.DRAFT) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 DRAFT 状态交接允许提交，当前状态为: " + transfer.getStatus()
            );
        }
        if (!Objects.equals(transfer.getVersion(), req.expectedVersion())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生变化，请刷新后重试"
            );
        }
        if (shipment == null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_BOUND",
                    "交接未绑定运输任务",
                    "交接提交为 PENDING 前必须先绑定一个 PLANNED 运输任务"
            );
        }
        if (shipment.getStatus() != ShipmentStatus.PLANNED) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_PLANNED",
                    "运输任务状态不允许提交交接",
                    "交接绑定的运输任务当前状态为 " + shipment.getStatus() + "，仅 PLANNED 运输任务允许提交交接"
            );
        }

        // 5. 锁定关联批次，进行归属人防篡改校验、活性校验及 INPUT 消耗校验
        Batch batch = batchMapper.selectByIdForUpdate(transfer.getBatchId());
        if (batch == null) {
            throw new ResourceNotFoundException("未找到关联批次");
        }
        if (!Objects.equals(batch.getOrgId(), transfer.getSenderOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "批次所属组织与交接发货方不一致，禁止提交交接"
            );
        }
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不可交接",
                    "批次当前状态不可交接 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")，仅 ACTIVE 且 NORMAL 状态批次允许提交交接"
            );
        }
        if (batchOperationItemMapper.countSubmittedInputUsageByBatchId(batch.getId()) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_ALREADY_CONSUMED",
                    "批次已被物料操作消耗",
                    "批次已被已提交的批次操作作为投入(INPUT)消耗，禁止直接提交交接"
            );
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        Long expectedSenderOrgId = transfer.getSenderOrgId();
        Long expectedReceiverOrgId = transfer.getReceiverOrgId();
        String expectedStatus = TransferStatus.DRAFT.name();

        transfer.setSubmittedRecordedAt(nowUtc);
        transfer.setSubmittedBy(principal.getUserId());
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setUpdatedBy(principal.getUserId());

        int updated = transferMapper.updateByIdAndVersion(
                transfer,
                expectedSenderOrgId,
                expectedReceiverOrgId,
                expectedStatus,
                req.expectedVersion()
        );
        if (updated == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "提交交接时发生并发版本冲突"
            );
        }

        saveIdempotencyRecord(orgId, cleanKey, action, transfer.getId(), requestHash, nowUtc);

        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("shipmentId", shipment.getId());
        auditSummary.put("shipmentNo", shipment.getShipmentNo());

        auditService.recordAudit(
                principal.getUserId(),
                orgId,
                "SUBMIT",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );

        transfer.setVersion(req.expectedVersion() + 1);
        Transfer updatedTransfer = transferMapper.selectById(transferId);
        return toResponse(updatedTransfer != null ? updatedTransfer : transfer);
    }

    /**
     * 接收方确认接受交接 (POST /api/v1/transfers/{transferId}/accept)。
     */
    @Transactional
    public TransferResponse acceptTransfer(
            Long transferId,
            TransferAcceptRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkReceiverRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long receiverOrgId = principal.getOrgId();

        // 1. 实收数量与单位严格前置校验
        if (req.receivedQuantity() == null || req.receivedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "实收数量 receivedQuantity 必须大于 0"
            );
        }

        String action = "ACCEPT";
        String diffReason = req.differenceReason() != null ? req.differenceReason().trim() : null;
        String requestHash = computeHash(
                action,
                transferId,
                req.receivedQuantity(),
                req.unitCode(),
                req.occurredAt().toInstant().toString(),
                diffReason,
                req.expectedVersion()
        );

        // 2. 幂等普通读预检
        TransferIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(receiverOrgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action) && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(existingIdem.getTransferId());
                if (existingTransfer != null) {
                    return toResponse(existingTransfer);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等接受冲突",
                    "当前幂等键已被本组织用于其他动作或不同语义的接受请求"
            );
        }

        // 3. 按 shipment(FOR SHARE) → transfer(FOR UPDATE) 顺序锁定运输任务与交接记录
        LockedTransfer locked = lockShipmentThenTransfer(transferId, false);
        Transfer transfer = locked.transfer();
        Shipment shipment = locked.shipment();

        // 4. 锁定后当前读排他幂等检查
        TransferIdempotency lockedIdem = idempotencyMapper.selectByOrgIdAndKeyForUpdate(receiverOrgId, cleanKey);
        if (lockedIdem != null) {
            if (Objects.equals(lockedIdem.getAction(), action) && Objects.equals(lockedIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(transferId);
                return toResponse(existingTransfer != null ? existingTransfer : transfer);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "并发幂等冲突",
                    "当前幂等键已被本组织其他并发接受请求占用"
            );
        }

        // 5. 身份与状态流转校验
        if (!Objects.equals(transfer.getReceiverOrgId(), receiverOrgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅指定接收企业有权接受货物交接"
            );
        }
        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 PENDING 状态交接允许接受，当前状态为: " + transfer.getStatus()
            );
        }
        if (!Objects.equals(transfer.getVersion(), req.expectedVersion())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生变化，请刷新后重试"
            );
        }

        // 6. 关联运输任务必须已物理到达 (DELIVERED)；Shipment 到达不代表接收方已验收
        requireDeliveredShipment(shipment, "接受");

        // 7. 计量单位一致性校验
        if (!Objects.equals(req.unitCode(), transfer.getUnitCode())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "UNIT_CODE_MISMATCH",
                    "计量单位不匹配",
                    "实收计量单位(" + req.unitCode() + ")与发货计量单位(" + transfer.getUnitCode() + ")不一致"
            );
        }

        // 7. 锁定关联批次并校验归属人防篡改与活性
        Batch batch = batchMapper.selectByIdForUpdate(transfer.getBatchId());
        if (batch == null) {
            throw new ResourceNotFoundException("未找到交接关联批次");
        }
        if (!Objects.equals(batch.getOrgId(), transfer.getSenderOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "批次当前持有方与交接发货方不一致，禁止接受交接"
            );
        }
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不可流转",
                    "关联批次流转或风险状态不可流转 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")，已被质量冻结或召回，禁止接受交接"
            );
        }

        // 9. 实收数量差异强制说明
        boolean hasQuantityDiff = req.receivedQuantity().compareTo(transfer.getQuantity()) != 0;
        if (hasQuantityDiff) {
            if (diffReason == null || diffReason.isBlank()) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DIFFERENCE_REASON_REQUIRED",
                        "数量差异原因未填",
                        "实收数量(" + req.receivedQuantity().stripTrailingZeros().toPlainString() +
                                ")与发货数量(" + transfer.getQuantity().stripTrailingZeros().toPlainString() +
                                ")存在差异，必须说明原因"
                );
            }
        } else {
            diffReason = null;
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime receivedAtUtc = req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        Long expectedSenderOrgId = transfer.getSenderOrgId();
        Long expectedReceiverOrgId = transfer.getReceiverOrgId();
        String expectedStatus = TransferStatus.PENDING.name();

        // 10. 更新交接记录状态与决定字段
        transfer.setReceivedAt(receivedAtUtc);
        transfer.setDecisionRecordedAt(nowUtc);
        transfer.setDecidedBy(principal.getUserId());
        transfer.setReceivedQuantity(req.receivedQuantity());
        transfer.setDifferenceReason(diffReason);
        transfer.setStatus(TransferStatus.ACCEPTED);
        transfer.setUpdatedBy(principal.getUserId());

        int updatedTransfer = transferMapper.updateByIdAndVersion(
                transfer,
                expectedSenderOrgId,
                expectedReceiverOrgId,
                expectedStatus,
                req.expectedVersion()
        );
        if (updatedTransfer == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接确认发生并发冲突"
            );
        }

        // 11. 原子转移批次持有组织 (带 updated_by 与乐观锁，强制限定旧持有组织 expectedSenderOrgId)
        try {
            int updatedBatch = batchMapper.updateOrgIdByIdAndVersion(
                    batch.getId(),
                    expectedSenderOrgId,
                    receiverOrgId,
                    batch.getVersion(),
                    principal.getUserId()
            );
            if (updatedBatch == 0) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "BATCH_CONCURRENT_CONFLICT",
                        "批次并发冲突",
                        "批次持有企业更新失败，批次所属组织或版本已改变"
                );
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_CONCURRENT_CONFLICT",
                    "批次并发冲突",
                    "批次持有企业更新时发生并发冲突"
            );
        }

        // 12. 原子转移关联公开追溯码 (public_trace_code) 的归属组织 (强制限定原 expectedSenderOrgId)
        int updatedCodes = publicTraceCodeMapper.transferOrgScopeByBatchId(
                batch.getId(),
                expectedSenderOrgId,
                receiverOrgId,
                nowUtc,
                principal.getUserId()
        );
        if (updatedCodes == 0) {
            int existingCodeCount = publicTraceCodeMapper.countByBatchIdIgnoreTenant(batch.getId());
            if (existingCodeCount > 0) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "TRACE_CODE_ORG_CONFLICT",
                        "追溯码组织冲突",
                        "批次关联的公开追溯码持有组织与发货方不一致，操作已回滚"
                );
            }
        }

        // 13. 不生成 ARRIVAL：ARRIVAL 唯一自动来源为 Shipment IN_TRANSIT → DELIVERED（统一业务契约 v1.1 §11）

        // 14. 记录幂等并写审计
        saveIdempotencyRecord(receiverOrgId, cleanKey, action, transfer.getId(), requestHash, nowUtc);

        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("receivedQuantity", req.receivedQuantity().stripTrailingZeros().toPlainString());
        auditSummary.put("differenceReason", diffReason != null ? diffReason : "");

        auditService.recordAudit(
                principal.getUserId(),
                receiverOrgId,
                "ACCEPT",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );

        transfer.setVersion(req.expectedVersion() + 1);
        Transfer latestTransfer = transferMapper.selectById(transferId);
        return toResponse(latestTransfer != null ? latestTransfer : transfer);
    }

    /**
     * 接收方拒收交接 (POST /api/v1/transfers/{transferId}/reject)。
     */
    @Transactional
    public TransferResponse rejectTransfer(
            Long transferId,
            TransferRejectRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkReceiverRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long receiverOrgId = principal.getOrgId();

        if (req.reason() == null || req.reason().isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "拒收原因 reason 不能为空"
            );
        }

        String action = "REJECT";
        String cleanReason = req.reason().trim();
        String requestHash = computeHash(action, transferId, cleanReason, req.occurredAt().toInstant().toString(), req.expectedVersion());

        // 1. 幂等普通读预检
        TransferIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(receiverOrgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action) && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(existingIdem.getTransferId());
                if (existingTransfer != null) {
                    return toResponse(existingTransfer);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等拒收冲突",
                    "当前幂等键已被本组织用于其他动作或不同语义的拒收请求"
            );
        }

        // 2. 按 shipment(FOR SHARE) → transfer(FOR UPDATE) 顺序锁定运输任务与交接记录
        LockedTransfer locked = lockShipmentThenTransfer(transferId, false);
        Transfer transfer = locked.transfer();
        Shipment shipment = locked.shipment();

        // 3. 锁定后当前读排他幂等检查
        TransferIdempotency lockedIdem = idempotencyMapper.selectByOrgIdAndKeyForUpdate(receiverOrgId, cleanKey);
        if (lockedIdem != null) {
            if (Objects.equals(lockedIdem.getAction(), action) && Objects.equals(lockedIdem.getRequestHash(), requestHash)) {
                Transfer existingTransfer = transferMapper.selectById(transferId);
                return toResponse(existingTransfer != null ? existingTransfer : transfer);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "并发幂等冲突",
                    "当前幂等键已被本组织其他并发拒收请求占用"
            );
        }

        // 4. 身份与状态流转校验
        if (!Objects.equals(transfer.getReceiverOrgId(), receiverOrgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "仅指定接收企业有权拒收货物"
            );
        }
        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅 PENDING 状态交接允许拒收，当前状态为: " + transfer.getStatus()
            );
        }
        if (!Objects.equals(transfer.getVersion(), req.expectedVersion())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接凭单版本已发生变化，请刷新后重试"
            );
        }

        requireDeliveredShipment(shipment, "拒收");

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime rejectedAtUtc = req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        Long expectedSenderOrgId = transfer.getSenderOrgId();
        Long expectedReceiverOrgId = transfer.getReceiverOrgId();
        String expectedStatus = TransferStatus.PENDING.name();

        transfer.setReceivedAt(rejectedAtUtc);
        transfer.setDecisionRecordedAt(nowUtc);
        transfer.setDecidedBy(principal.getUserId());
        transfer.setRejectionReason(cleanReason);
        transfer.setStatus(TransferStatus.REJECTED);
        transfer.setUpdatedBy(principal.getUserId());

        int updated = transferMapper.updateByIdAndVersion(
                transfer,
                expectedSenderOrgId,
                expectedReceiverOrgId,
                expectedStatus,
                req.expectedVersion()
        );
        if (updated == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "拒收操作发生并发版本冲突"
            );
        }

        saveIdempotencyRecord(receiverOrgId, cleanKey, action, transfer.getId(), requestHash, nowUtc);

        Map<String, Object> auditSummary = new LinkedHashMap<>();
        auditSummary.put("rejectionReason", cleanReason);

        auditService.recordAudit(
                principal.getUserId(),
                receiverOrgId,
                "REJECT",
                "TRANSFER",
                transfer.getId(),
                nowUtc,
                "SUCCESS",
                serializeSummary(auditSummary)
        );

        transfer.setVersion(req.expectedVersion() + 1);
        Transfer updatedTransfer = transferMapper.selectById(transferId);
        return toResponse(updatedTransfer != null ? updatedTransfer : transfer);
    }

    /**
     * 查询单个交接凭证详情 (GET /api/v1/transfers/{transferId})。
     */
    public TransferResponse getTransferDetail(Long transferId, TraceSecurityPrincipal principal) {
        Long orgId = principal.getOrgId();
        Transfer transfer = transferMapper.selectByIdAndOrgScope(transferId, orgId);
        if (transfer == null) {
            boolean exists = transferMapper.existsByIdIgnoreTenant(transferId) > 0;
            if (!exists) {
                throw new ResourceNotFoundException("未找到 ID 为 " + transferId + " 的交接凭单");
            }
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权查看其他企业之间的交接记录"
            );
        }
        return enrich(List.of(transfer)).get(0);
    }

    /**
     * 分页查询当前组织相关交接列表 (GET /api/v1/transfers)。
     * <p>
     * 发送方与接收方在交接结束（包括批次已转出）后仍可只读查询本组织参与过的历史交接。
     * </p>
     */
    public List<TransferResponse> listTransfers(
            String direction,
            String status,
            Long batchId,
            long page,
            int size,
            TraceSecurityPrincipal principal
    ) {
        long safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        long offset = (safePage - 1) * safeSize;

        List<Transfer> transfers = transferMapper.selectTransfersPage(
                principal.getOrgId(),
                direction,
                status,
                batchId,
                offset,
                safeSize
        );
        return enrich(transfers);
    }

    /**
     * 统计当前组织相关交接总数。
     */
    public long countTransfers(String direction, String status, Long batchId, TraceSecurityPrincipal principal) {
        return transferMapper.countTransfers(principal.getOrgId(), direction, status, batchId);
    }

    /**
     * 单条交接响应：补全关联运输任务编号 / 状态与追溯批次号（调用方已完成交接组织范围校验）。
     */
    private TransferResponse toResponse(Transfer transfer) {
        return enrich(List.of(transfer)).get(0);
    }

    /**
     * 以关联运输任务与批次补全交接响应展示字段（调用方已完成交接组织范围校验）。
     */
    private List<TransferResponse> enrich(List<Transfer> transfers) {
        if (transfers.isEmpty()) {
            return List.of();
        }
        Set<Long> shipmentIds = new HashSet<>();
        Set<Long> batchIds = new HashSet<>();
        for (Transfer t : transfers) {
            if (t.getShipmentId() != null) {
                shipmentIds.add(t.getShipmentId());
            }
            batchIds.add(t.getBatchId());
        }
        Map<Long, Shipment> shipments = shipmentIds.isEmpty()
                ? Map.of()
                : shipmentMapper.selectByIdsIgnoreTenant(shipmentIds).stream()
                        .collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<Long, Batch> batches = batchMapper.selectByIdsIgnoreTenant(batchIds).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        return transfers.stream()
                .map(t -> TransferResponse.fromEntity(
                        t,
                        t.getShipmentId() != null ? shipments.get(t.getShipmentId()) : null,
                        batches.get(t.getBatchId())))
                .toList();
    }

    /**
     * 已锁定的交接及其绑定运输任务（未绑定时 shipment 为 null）。
     */
    private record LockedTransfer(Transfer transfer, Shipment shipment) {
    }

    /**
     * 按统一行锁顺序 shipment → transfer 锁定交接及其绑定运输任务。
     * <p>
     * 先以普通读获知 shipment_id，再锁运输任务（exclusive 为 true 时 FOR UPDATE，否则 FOR SHARE），最后锁交接；
     * 若加锁期间绑定关系被并发改变（普通读与锁定读的 shipment_id 不一致），返回 409 VERSION_CONFLICT 要求刷新重试，
     * 从而绝不在持有交接锁时反向等待运输任务锁。
     * </p>
     */
    private LockedTransfer lockShipmentThenTransfer(Long transferId, boolean exclusive) {
        Transfer snapshot = transferMapper.selectById(transferId);
        if (snapshot == null || Objects.equals(snapshot.getIsDeleted(), 1)) {
            throw new ResourceNotFoundException("未找到 ID 为 " + transferId + " 的交接凭单");
        }
        Shipment shipment = null;
        if (snapshot.getShipmentId() != null) {
            shipment = exclusive
                    ? shipmentMapper.selectByIdForUpdate(snapshot.getShipmentId())
                    : shipmentMapper.selectByIdForShare(snapshot.getShipmentId());
        }
        Transfer transfer = transferMapper.selectByIdForUpdate(transferId);
        if (transfer == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + transferId + " 的交接凭单");
        }
        if (!Objects.equals(transfer.getShipmentId(), snapshot.getShipmentId())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "交接的运输任务绑定关系已被并发修改，请刷新后重试"
            );
        }
        return new LockedTransfer(transfer, shipment);
    }

    private void requireDeliveredShipment(Shipment shipment, String actionLabel) {
        if (shipment == null || shipment.getStatus() != ShipmentStatus.DELIVERED) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_DELIVERED",
                    "运输任务尚未到达",
                    "关联运输任务当前状态为 " + (shipment == null ? "未绑定" : shipment.getStatus())
                            + "，仅运输任务已到达 (DELIVERED) 后接收方才能" + actionLabel + "交接"
            );
        }
    }

    private void bumpShipmentManifestVersion(Long shipmentId, Long userId) {
        if (shipmentMapper.bumpManifestVersion(shipmentId, userId) != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_NOT_PLANNED",
                    "运输任务已不可变更装载清单",
                    "运输任务已非 PLANNED，装载清单变更已回滚"
            );
        }
    }

    private void checkReceiverOrgType(Organization receiverOrg) {
        // 承运商只负责物理运输，不会成为 Batch 当前责任组织（统一业务契约 v1.1 §3.3），因此不能作为交接接收方
        if ("CARRIER".equalsIgnoreCase(receiverOrg.getOrgType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECEIVER_ORG_TYPE_NOT_ALLOWED",
                    "接收组织类型不允许",
                    "承运组织只负责物理运输，不能作为交接接收方成为批次当前责任组织"
            );
        }
    }

    // =========================================================================
    // 内部私有工具方法
    // =========================================================================

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        if (principal.getRoles().contains("SYSTEM_ADMIN")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ADMIN_RESTRICTED",
                    "管理员权限受限",
                    "系统管理员不可代发或代办具体企业的业务交接"
            );
        }
        if (!principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_NOT_ALLOWED",
                    "角色权限不足",
                    "仅企业操作员 (OPERATOR) 允许执行当前交接写操作"
            );
        }
    }

    private void checkReceiverRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        if (principal.getRoles().contains("SYSTEM_ADMIN")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ADMIN_RESTRICTED",
                    "管理员权限受限",
                    "系统管理员不可代办具体企业的业务交接"
            );
        }
        if (!principal.getRoles().contains("OPERATOR") && !principal.getRoles().contains("QUALITY_MANAGER")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_NOT_ALLOWED",
                    "角色权限不足",
                    "仅接收企业的操作员 (OPERATOR) 或质量管理员 (QUALITY_MANAGER) 允许确认或拒收交接"
            );
        }
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
            byte[] delimiter = new byte[]{0x1F}; // 单元分隔符 US
            for (Object param : params) {
                digest.update(delimiter);
                if (param != null) {
                    if (param instanceof BigDecimal bd) {
                        digest.update(bd.stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8));
                    } else if (param instanceof TemporalAccessor) {
                        digest.update(param.toString().getBytes(StandardCharsets.UTF_8));
                    } else {
                        digest.update(param.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    digest.update(new byte[]{0});
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    private void saveIdempotencyRecord(
            Long orgId,
            String idempotencyKey,
            String action,
            Long transferId,
            String requestHash,
            LocalDateTime nowUtc
    ) {
        TransferIdempotency entity = new TransferIdempotency();
        entity.setOrgId(orgId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setAction(action);
        entity.setTransferId(transferId);
        entity.setRequestHash(requestHash);
        entity.setCreatedAt(nowUtc);

        try {
            idempotencyMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 当前锁定读恢复穿透快照盲区
            TransferIdempotency dup = idempotencyMapper.selectByOrgIdAndKeyForUpdate(orgId, idempotencyKey);
            if (dup != null) {
                if (Objects.equals(dup.getAction(), action) && Objects.equals(dup.getRequestHash(), requestHash)) {
                    return;
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "并发幂等冲突",
                        "并发检测到相同幂等键，且请求载荷与已落库数据不一致"
                );
            }
            throw e;
        }
    }

    private String serializeSummary(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "SYSTEM_ERROR",
                    "审计日志序列化失败",
                    e.getMessage()
            );
        }
    }

    private String generateTransferNo(LocalDateTime now) {
        int randomSuffix = RANDOM.nextInt(9000) + 1000;
        return "TRF-" + now.format(TRF_NO_FORMAT) + "-" + randomSuffix;
    }
}
