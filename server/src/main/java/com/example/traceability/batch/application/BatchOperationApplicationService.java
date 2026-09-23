package com.example.traceability.batch.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchItemRole;
import com.example.traceability.batch.domain.BatchOperation;
import com.example.traceability.batch.domain.BatchOperationItem;
import com.example.traceability.batch.domain.BatchOperationStatus;
import com.example.traceability.batch.domain.BatchOperationType;
import com.example.traceability.batch.domain.BatchRelation;
import com.example.traceability.batch.domain.BatchRelationType;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.domain.BatchSaleGuard;
import com.example.traceability.batch.domain.BatchType;
import com.example.traceability.batch.domain.TraceBatchNoGenerator;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationItemResponse;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.dto.BatchRelationResponse;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.batch.mapper.BatchOperationMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.application.TraceEventApplicationService.ProcessBatchLine;
import com.example.traceability.trace.application.TraceEventApplicationService.ProcessProjection;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 批次操作（物料转换）与谱系边应用服务。
 * <p>
 * Phase A Slice 3 协议（统一业务契约 v1.1 §4 / §5 / §6 / §11）：
 * <ul>
 *   <li>本 Slice 仅执行 PROCESS 与 SPLIT，且恰好一个 INPUT；MERGE / REPACK 返回 422 OPERATION_TYPE_NOT_SUPPORTED
 *       （当前 Slice 范围限制，不是永久业务规则）；</li>
 *   <li>INPUT 必须全量消耗输入批次当前剩余量，禁止部分 INPUT；需要部分加工时先 SPLIT；</li>
 *   <li>OUTPUT 批次由服务端在创建操作草稿时生成为 DRAFT+NORMAL，只能随操作提交原子激活；</li>
 *   <li>提交在同一事务内：关闭全部 INPUT、激活全部 OUTPUT、固化谱系边，PROCESS 自动投影 PROCESS 事件，SPLIT 不产生事件；</li>
 *   <li>物料平衡由服务端以 BigDecimal 数值比较（compareTo）精确校验：sum(INPUT) = sum(OUTPUT + LOSS + WASTE + SAMPLE)；</li>
 *   <li>写操作仅限当前责任组织为 PROCESSOR 的 OPERATOR；读取对操作所属组织开放（含历史参与后已转出批次的情形）。</li>
 * </ul>
 * 锁顺序：batch_operation → batch（按 ID 升序），从不锁 transfer / shipment，与 Slice 2 的 shipment → transfer → batch 不成环。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchOperationApplicationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter OP_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Set<BatchOperationType> SUPPORTED_TYPES = Set.of(BatchOperationType.PROCESS, BatchOperationType.SPLIT);

    private final BatchOperationMapper operationMapper;
    private final BatchOperationItemMapper itemMapper;
    private final BatchRelationMapper relationMapper;
    private final BatchMapper batchMapper;
    private final TransferMapper transferMapper;
    private final ProductMapper productMapper;
    private final TraceBatchNoGenerator traceBatchNoGenerator;
    private final TraceEventApplicationService traceEventService;
    private final BatchQuantityService batchQuantityService;

    public BatchOperationApplicationService(
            BatchOperationMapper operationMapper,
            BatchOperationItemMapper itemMapper,
            BatchRelationMapper relationMapper,
            BatchMapper batchMapper,
            TransferMapper transferMapper,
            ProductMapper productMapper,
            TraceBatchNoGenerator traceBatchNoGenerator,
            TraceEventApplicationService traceEventService,
            BatchQuantityService batchQuantityService
    ) {
        this.operationMapper = Objects.requireNonNull(operationMapper, "operationMapper 不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper 不能为空");
        this.relationMapper = Objects.requireNonNull(relationMapper, "relationMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.productMapper = Objects.requireNonNull(productMapper, "productMapper 不能为空");
        this.traceBatchNoGenerator = Objects.requireNonNull(traceBatchNoGenerator, "traceBatchNoGenerator 不能为空");
        this.traceEventService = Objects.requireNonNull(traceEventService, "traceEventService 不能为空");
        this.batchQuantityService = Objects.requireNonNull(batchQuantityService, "batchQuantityService 不能为空");
    }

    // =====================================================================================
    // 创建草稿
    // =====================================================================================

    /**
     * 创建批次操作草稿，并由服务端生成全部 OUTPUT 草稿批次（DRAFT+NORMAL）。
     * <p>
     * 同组织相同 Idempotency-Key 且语义相同则重放原操作；语义不同返回 409 IDEMPOTENCY_CONFLICT。
     * 创建阶段不改变输入批次状态，也不对输入批次做排他预留；提交时在行锁下重新校验全部前提。
     * </p>
     *
     * @param req            操作创建请求
     * @param idempotencyKey 客户端创建幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 创建或重放的批次操作详情
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchOperationResponse createDraftOperation(
            BatchOperationCreateRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkWriteAccess(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);

        BatchOperationType opType = resolveSupportedType(req.operationType());
        ValidatedItems validated = validateItems(opType, req.items());
        LocalDateTime occurredAtUtc = normalizeOccurredAt(req);
        String cleanNote = req.note() != null && !req.note().isBlank() ? req.note().trim() : null;
        Long orgId = principal.getOrgId();

        // 1. 幂等预检（含已删除草稿占用的键）
        BatchOperation existing = operationMapper.selectByOrgIdAndIdempotencyKeyIncludingDeleted(orgId, cleanKey);
        if (existing != null) {
            return replayCreateOrConflict(existing, validated, opType, occurredAtUtc, cleanNote);
        }

        // 2. 锁定唯一 INPUT 批次并校验全量消耗前提
        BatchOperationItemRequest inputReq = validated.input();
        Batch input = batchMapper.selectByIdIgnoreTenantForUpdate(inputReq.batchId());
        if (input == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + inputReq.batchId() + " 的输入批次");
        }
        verifyInputConsumable(input, inputReq.quantity(), orgId);

        // 3. 产出产品校验
        List<Long> outputProductIds = new ArrayList<>();
        for (BatchOperationItemRequest out : validated.outputs()) {
            outputProductIds.add(resolveOutputProductId(opType, out, input));
        }

        // 4. 持久化操作主表
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        BatchOperation op = new BatchOperation();
        op.setOrgId(orgId);
        op.setOperationNo(generateOperationNo(nowUtc));
        op.setOperationType(opType.name());
        op.setOccurredAt(occurredAtUtc);
        op.setRecordedAt(nowUtc);
        op.setStatus(BatchOperationStatus.DRAFT.name());
        op.setIdempotencyKey(cleanKey);
        op.setNote(cleanNote);
        op.setVersion(0L);
        op.setIsDeleted(0);
        op.setCreatedAt(nowUtc);
        op.setCreatedBy(principal.getUserId());
        op.setUpdatedAt(nowUtc);
        op.setUpdatedBy(principal.getUserId());
        try {
            operationMapper.insert(op);
        } catch (DuplicateKeyException e) {
            BatchOperation dup = operationMapper.selectByOrgIdAndIdempotencyKeyIncludingDeleted(orgId, cleanKey);
            if (dup != null) {
                return replayCreateOrConflict(dup, validated, opType, occurredAtUtc, cleanNote);
            }
            throw e;
        }

        // 5. 服务端生成 OUTPUT 草稿批次（PROCESS 产出为 PROCESSING；SPLIT 产出继承输入批次类型）
        String outputBatchType = opType == BatchOperationType.PROCESS ? BatchType.PROCESSING.name() : input.getBatchType();
        // 按请求中 OUTPUT 出现顺序一一对应；值相同的 OUTPUT 请求记录彼此相等，不能作为 Map 键
        List<Long> outputBatchIds = new ArrayList<>();
        for (int i = 0; i < validated.outputs().size(); i++) {
            BatchOperationItemRequest out = validated.outputs().get(i);
            Batch child = new Batch();
            child.setOrgId(orgId);
            child.setCreationOrgId(orgId);
            child.setProductId(outputProductIds.get(i));
            child.setTraceBatchNo(traceBatchNoGenerator.generate());
            child.setExternalBatchNo(out.externalBatchNo() != null && !out.externalBatchNo().isBlank() ? out.externalBatchNo().trim() : null);
            child.setBatchType(outputBatchType);
            child.setQuantity(out.quantity());
            child.setUnitCode("kg");
            child.setOriginType(input.getOriginType());
            child.setOriginText(input.getOriginText());
            child.setProductionDate(occurredAtUtc.toLocalDate());
            child.setCaptureDate(input.getCaptureDate());
            // 普通 PROCESS 不推导速冻；SPLIT 只拆分身份，均不声明速冻日期
            child.setFreezeDate(null);
            child.setShelfLifeDays(out.shelfLifeDays());
            child.setFlowStatus(BatchFlowStatus.DRAFT.name());
            child.setRiskStatus(BatchRiskStatus.NORMAL.name());
            child.setProducedByOperationId(op.getId());
            child.setConsumedByOperationId(null);
            child.setCreationIdempotencyKey(null);
            child.setVersion(0L);
            child.setIsDeleted(0);
            child.setCreatedAt(nowUtc);
            child.setCreatedBy(principal.getUserId());
            child.setUpdatedAt(nowUtc);
            child.setUpdatedBy(principal.getUserId());
            batchMapper.insert(child);
            outputBatchIds.add(child.getId());
        }

        // 6. 持久化明细（保持请求顺序）
        List<BatchOperationItem> itemsToInsert = new ArrayList<>();
        int outputIndex = 0;
        for (BatchOperationItemRequest ir : req.items()) {
            BatchItemRole role = BatchItemRole.fromCode(ir.role());
            BatchOperationItem item = new BatchOperationItem();
            item.setOperationId(op.getId());
            item.setBatchId(switch (role) {
                case INPUT -> ir.batchId();
                case OUTPUT -> outputBatchIds.get(outputIndex++);
                default -> null;
            });
            item.setRole(role.name());
            item.setQuantity(ir.quantity());
            item.setUnitCode("kg");
            item.setNormalizedQuantity(ir.quantity());
            item.setConversionRuleId(null);
            item.setVersion(0L);
            item.setIsDeleted(0);
            item.setCreatedAt(nowUtc);
            item.setCreatedBy(principal.getUserId());
            item.setUpdatedAt(nowUtc);
            item.setUpdatedBy(principal.getUserId());
            itemsToInsert.add(item);
        }
        itemMapper.insertBatch(itemsToInsert);

        return buildResponse(op, itemMapper.selectByOperationId(op.getId()), List.of());
    }

    // =====================================================================================
    // 提交
    // =====================================================================================

    /**
     * 提交批次操作草稿：同一事务内关闭 INPUT、激活 OUTPUT、固化谱系边，PROCESS 自动投影 PROCESS 事件。
     * <p>
     * 任一校验或条件更新失败均抛出异常并整体回滚。
     * </p>
     *
     * <p>
     * 隔离级别为 READ COMMITTED：输入批次行锁（FOR UPDATE）是与 Transfer 新建 / 提交路径的唯一串行化点，
     * 取得行锁后的未结束交接与已消耗数量检查必须读取最新已提交数据；REPEATABLE READ 下事务早先建立的快照会漏看
     * 在等待行锁期间已提交的交接。这样无需再对 transfer 加共享锁，避免与 shipment → transfer → batch 锁顺序成环。
     * </p>
     *
     * @param operationId    批次操作 ID
     * @param req            提交请求参数（必须携带 version）
     * @param idempotencyKey 客户端提交防重幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 提交成功后的批次操作详情及生成的谱系边
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchOperationResponse submitOperation(
            Long operationId,
            BatchOperationSubmitRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkWriteAccess(principal);
        String cleanSubmissionKey = validateIdempotencyKey(idempotencyKey);
        if (req == null || req.version() == null || req.version() < 0) {
            throw badRequest("乐观锁版本号 version 不能为空且必须非负");
        }
        Long orgId = principal.getOrgId();

        // 1. 前置查询、组织边界与提交幂等
        BatchOperation existing = requireOwnOperation(operationId, orgId);
        BatchOperation replay = replaySubmittedOrNull(existing, cleanSubmissionKey);
        if (replay != null) {
            return loadResponse(replay);
        }
        BatchOperation keyOwner = operationMapper.selectByOrgIdAndSubmissionKey(orgId, cleanSubmissionKey);
        if (keyOwner != null && !Objects.equals(keyOwner.getId(), operationId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前提交幂等键已被本组织其他批次操作使用");
        }
        requireDraftAndVersion(existing, req.version());

        // 2. 锁定操作主记录，锁后重新判定
        BatchOperation lockedOp = operationMapper.selectByIdForUpdate(operationId);
        if (lockedOp == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        replay = replaySubmittedOrNull(lockedOp, cleanSubmissionKey);
        if (replay != null) {
            return loadResponse(replay);
        }
        requireDraftAndVersion(lockedOp, req.version());
        BatchOperationType opType = resolveSupportedType(lockedOp.getOperationType());

        // 3. 明细与基数
        List<BatchOperationItem> items = itemMapper.selectByOperationId(operationId);
        List<BatchOperationItem> inputItems = new ArrayList<>();
        List<BatchOperationItem> outputItems = new ArrayList<>();
        BigDecimal[] totals = sumByRole(items);
        for (BatchOperationItem item : items) {
            if (BatchItemRole.INPUT.name().equals(item.getRole())) {
                inputItems.add(item);
            } else if (BatchItemRole.OUTPUT.name().equals(item.getRole())) {
                outputItems.add(item);
            }
        }
        validateCardinality(opType, inputItems.size(), outputItems.size());
        verifyMassBalance(totals);

        // 4. 按批次 ID 升序加排他行锁（INPUT 与 OUTPUT 一并）
        List<Long> sortedBatchIds = items.stream()
                .map(BatchOperationItem::getBatchId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, Batch> locked = new LinkedHashMap<>();
        for (Long bid : sortedBatchIds) {
            Batch b = batchMapper.selectByIdIgnoreTenantForUpdate(bid);
            if (b == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + bid + " 的关联批次");
            }
            locked.put(bid, b);
        }

        // 5. INPUT：责任组织、ACTIVE+NORMAL、未被消耗、无未结束交接、全量消耗
        BatchOperationItem inputItem = inputItems.get(0);
        Batch input = locked.get(inputItem.getBatchId());
        verifyInputConsumable(input, inputItem.getQuantity(), orgId);

        // 6. OUTPUT：必须是本操作产出的 DRAFT+NORMAL 草稿，数量与明细一致
        for (BatchOperationItem outItem : outputItems) {
            Batch out = locked.get(outItem.getBatchId());
            if (!Objects.equals(out.getProducedByOperationId(), operationId)
                    || !Objects.equals(out.getOrgId(), orgId)
                    || !BatchFlowStatus.DRAFT.name().equals(out.getFlowStatus())
                    || !BatchRiskStatus.NORMAL.name().equals(out.getRiskStatus())
                    || out.getQuantity().compareTo(outItem.getQuantity()) != 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "OPERATION_OUTPUT_STATE_INVALID", "输出批次状态异常",
                        "输出批次 " + out.getTraceBatchNo() + " 不是本操作产出的草稿批次或数量不一致，无法提交");
            }
        }

        // 7. 谱系边（非自环 + recursive CTE 环检测，作为纵深防御）
        String relationType = opType == BatchOperationType.SPLIT ? BatchRelationType.SPLIT.name() : BatchRelationType.TRANSFORM.name();
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        List<BatchRelation> relations = new ArrayList<>();
        for (BatchOperationItem outItem : outputItems) {
            Long parentId = input.getId();
            Long childId = outItem.getBatchId();
            if (Objects.equals(parentId, childId)) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_RELATION_SELF_LOOP", "谱系关系自环",
                        "批次 " + parentId + " 试图与自身建立谱系边");
            }
            if (relationMapper.checkCycleWithCte(childId, parentId) > 0) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_RELATION_CYCLE", "批次谱系成环",
                        String.format("检测到循环关系：子批次 %d 已存在到达父批次 %d 的上游路径", childId, parentId));
            }
            BatchRelation relation = new BatchRelation();
            relation.setOperationId(operationId);
            relation.setParentBatchId(parentId);
            relation.setChildBatchId(childId);
            relation.setRelationType(relationType);
            relation.setCreatedAt(nowUtc);
            relations.add(relation);
        }

        // 8. 条件更新：操作 DRAFT → SUBMITTED
        int affected;
        try {
            affected = operationMapper.submitOperation(operationId, orgId, req.version(), cleanSubmissionKey, nowUtc, principal.getUserId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "提交幂等键已被本组织其他批次操作使用");
        }
        if (affected != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突",
                    "批次操作已被并发修改，请刷新后重试");
        }

        // 9. 条件更新：INPUT CLOSED、OUTPUT ACTIVE（任一不命中即回滚）
        if (batchMapper.closeConsumedInput(input.getId(), orgId, operationId, nowUtc, principal.getUserId()) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED", "批次已被物料操作消耗",
                    "输入批次 " + input.getTraceBatchNo() + " 已并发关闭或被其他操作消耗，本次提交已回滚");
        }
        for (BatchOperationItem outItem : outputItems) {
            if (batchMapper.activateOperationOutput(outItem.getBatchId(), orgId, operationId, nowUtc, principal.getUserId()) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "OPERATION_OUTPUT_STATE_INVALID", "输出批次状态异常",
                        "输出批次 " + outItem.getBatchId() + " 已不是可激活的草稿，本次提交已回滚");
            }
        }

        // 10. 谱系边
        relationMapper.insertBatch(relations);

        // 11. PROCESS 自动投影 PROCESS 事件（每个 OUTPUT 一条）；SPLIT 不生成 PACK / PROCESS
        if (opType == BatchOperationType.PROCESS) {
            List<ProcessBatchLine> inputLines = List.of(new ProcessBatchLine(input.getId(), input.getTraceBatchNo(), inputItem.getQuantity()));
            List<ProcessBatchLine> outputLines = outputItems.stream()
                    .map(o -> new ProcessBatchLine(o.getBatchId(), locked.get(o.getBatchId()).getTraceBatchNo(), o.getQuantity()))
                    .toList();
            ProcessProjection projection = new ProcessProjection(
                    operationId,
                    lockedOp.getOperationNo(),
                    orgId,
                    lockedOp.getOccurredAt(),
                    inputLines,
                    outputLines,
                    totals[0],
                    totals[2],
                    totals[3],
                    totals[4]
            );
            for (BatchOperationItem outItem : outputItems) {
                traceEventService.appendProcessEvent(projection, outItem.getBatchId(), principal.getUserId(), nowUtc);
            }
        }

        return loadResponse(operationMapper.selectByIdAndOrgId(operationId, orgId));
    }

    // =====================================================================================
    // 删除草稿
    // =====================================================================================

    /**
     * 删除批次操作草稿：同一事务内逻辑删除操作、明细及其全部 OUTPUT 草稿批次。
     *
     * @param operationId     批次操作 ID
     * @param expectedVersion 期望版本号
     * @param principal       当前认证主体
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDraftOperation(Long operationId, Long expectedVersion, TraceSecurityPrincipal principal) {
        checkWriteAccess(principal);
        if (expectedVersion == null || expectedVersion < 0) {
            throw badRequest("expectedVersion 不能为空且必须非负");
        }
        Long orgId = principal.getOrgId();
        requireOwnOperation(operationId, orgId);

        BatchOperation locked = operationMapper.selectByIdForUpdate(operationId);
        if (locked == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        requireDraftAndVersion(locked, expectedVersion);

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (operationMapper.softDeleteDraft(operationId, orgId, expectedVersion, nowUtc, principal.getUserId()) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突",
                    "批次操作已被并发修改，请刷新后重试");
        }
        itemMapper.softDeleteByOperationId(operationId, nowUtc, principal.getUserId());
        batchMapper.softDeleteOperationDraftOutputs(operationId, orgId, nowUtc, principal.getUserId());
    }

    // =====================================================================================
    // 读取
    // =====================================================================================

    /**
     * 查询批次操作详情。
     * <p>
     * 操作所属组织（OPERATOR / QUALITY_MANAGER 等任意本组织用户）始终可读，即使相关批次此后已转出（历史只读）；
     * 平台只读角色可读；其他组织 403。
     * </p>
     */
    public BatchOperationResponse getOperation(Long operationId, TraceSecurityPrincipal principal) {
        requirePrincipal(principal);
        BatchOperation op = operationMapper.selectByIdIgnoreTenant(operationId);
        if (op == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        if (!isPlatformScope(principal) && !Objects.equals(op.getOrgId(), principal.getOrgId())) {
            throw orgScopeDenied("无权访问其他组织的批次操作");
        }
        return loadResponse(op);
    }

    /**
     * 按批次查询引用该批次（INPUT 或 OUTPUT）的批次操作。
     * <p>
     * 企业用户只返回本组织创建的操作，绝不暴露其他企业的内部单据；
     * 允许条件：当前责任组织，或本组织曾对该批次执行过操作（历史参与，只读）。平台只读角色返回全部。其他组织 403。
     * </p>
     */
    public SuccessEnvelope<List<BatchOperationResponse>> listOperationsByBatch(
            Long batchId,
            int page,
            int size,
            TraceSecurityPrincipal principal
    ) {
        requirePrincipal(principal);
        if (batchId == null || batchId <= 0) {
            throw badRequest("batchId 必须为正整数");
        }
        if (page < 1) {
            throw badRequest("页码 page 最小值为 1");
        }
        if (size < 1 || size > 100) {
            throw badRequest("分页大小 size 必须在 1 到 100 之间");
        }
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        Long scopeOrgId = isPlatformScope(principal) ? null : principal.getOrgId();
        long total = operationMapper.countByBatchId(batchId, scopeOrgId);
        if (scopeOrgId != null && !Objects.equals(batch.getOrgId(), scopeOrgId) && total == 0) {
            throw orgScopeDenied("无权访问其他组织批次的操作记录");
        }
        long offset = (long) (page - 1) * size;
        List<BatchOperationResponse> data = operationMapper.selectPageByBatchId(batchId, scopeOrgId, offset, size).stream()
                .map(this::loadResponse)
                .toList();
        return SuccessEnvelope.ofPage(data, new PageMeta(page, size, total));
    }

    // =====================================================================================
    // 校验辅助
    // =====================================================================================

    private record ValidatedItems(
            BatchOperationItemRequest input,
            List<BatchOperationItemRequest> outputs,
            List<BatchOperationItemRequest> others
    ) {
    }

    private BatchOperationType resolveSupportedType(String rawType) {
        if (rawType == null || !BatchOperationType.isValid(rawType)) {
            throw badRequest("不支持的批次操作类型: " + rawType);
        }
        BatchOperationType type = BatchOperationType.fromCode(rawType);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATION_TYPE_NOT_SUPPORTED", "操作类型暂不支持",
                    type.name() + " 不在当前 Slice（Phase A Slice 3）执行范围内，本 Slice 仅支持 PROCESS 与 SPLIT；"
                            + "这是当前开发范围限制，不是永久业务规则");
        }
        return type;
    }

    private ValidatedItems validateItems(BatchOperationType opType, List<BatchOperationItemRequest> items) {
        if (items == null || items.size() < 2) {
            throw badRequest("操作明细项目 items 不能为空且至少包含 2 个项目");
        }
        BatchOperationItemRequest input = null;
        int inputCount = 0;
        List<BatchOperationItemRequest> outputs = new ArrayList<>();
        List<BatchOperationItemRequest> others = new ArrayList<>();
        for (BatchOperationItemRequest item : items) {
            if (item == null) {
                throw badRequest("操作明细项目列表中不得包含空项目");
            }
            if (!BatchItemRole.isValid(item.role())) {
                throw badRequest("不支持的项目角色: " + item.role());
            }
            BatchItemRole role = BatchItemRole.fromCode(item.role());
            if (item.unitCode() == null || !"kg".equalsIgnoreCase(item.unitCode().trim())) {
                throw badRequest("操作明细计量单位仅允许 kg");
            }
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                throw badRequest("明细数量必须大于 0");
            }
            if (item.quantity().stripTrailingZeros().scale() > 3) {
                throw badRequest("明细数量最多保留 3 位小数");
            }
            boolean hasOutputOnlyFields = item.productId() != null
                    || (item.externalBatchNo() != null && !item.externalBatchNo().isBlank())
                    || item.shelfLifeDays() != null;
            switch (role) {
                case INPUT -> {
                    if (item.batchId() == null || item.batchId() <= 0) {
                        throw badRequest("INPUT 项目必须指定有效的 batchId");
                    }
                    if (hasOutputOnlyFields) {
                        throw badRequest("productId / externalBatchNo / shelfLifeDays 仅允许出现在 OUTPUT 项目中");
                    }
                    inputCount++;
                    input = item;
                }
                case OUTPUT -> {
                    if (item.batchId() != null) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "OUTPUT_BATCH_SERVER_GENERATED", "输出批次由服务端生成",
                                "OUTPUT 项目不得指定 batchId：输出批次由服务端在创建操作草稿时生成，并只能随操作提交激活");
                    }
                    outputs.add(item);
                }
                default -> {
                    if (item.batchId() != null) {
                        throw badRequest(role.name() + " 角色项目严禁关联批次 batchId");
                    }
                    if (hasOutputOnlyFields) {
                        throw badRequest("productId / externalBatchNo / shelfLifeDays 仅允许出现在 OUTPUT 项目中");
                    }
                    others.add(item);
                }
            }
        }
        validateCardinality(opType, inputCount, outputs.size());
        BigDecimal[] totals = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (BatchOperationItemRequest item : items) {
            int idx = roleIndex(BatchItemRole.fromCode(item.role()));
            totals[idx] = totals[idx].add(item.quantity());
        }
        verifyMassBalance(totals);
        return new ValidatedItems(input, List.copyOf(outputs), List.copyOf(others));
    }

    /**
     * 本 Slice 固定：PROCESS 恰好 1 个 INPUT、至少 1 个 OUTPUT；SPLIT 恰好 1 个 INPUT、至少 2 个 OUTPUT。
     * 多 INPUT 物料合并属于 MERGE 职责（当前 Slice 未开放）。
     */
    private void validateCardinality(BatchOperationType opType, int inputCount, int outputCount) {
        if (inputCount != 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "操作基数约束不符",
                    opType.name() + " 操作必须恰好包含 1 个 INPUT 项目（当前 " + inputCount + " 个）；多输入物料合并属于 MERGE 职责");
        }
        int minOutputs = opType == BatchOperationType.SPLIT ? 2 : 1;
        if (outputCount < minOutputs) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "操作基数约束不符",
                    opType.name() + " 操作必须至少包含 " + minOutputs + " 个 OUTPUT 项目（当前 " + outputCount + " 个）");
        }
    }

    /**
     * 精确物料平衡：以 BigDecimal 数值比较（compareTo，忽略 scale 差异）判定 sum(INPUT) = sum(OUTPUT + LOSS + WASTE + SAMPLE)。
     */
    private void verifyMassBalance(BigDecimal[] totals) {
        BigDecimal right = totals[1].add(totals[2]).add(totals[3]).add(totals[4]);
        if (totals[0].compareTo(right) != 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_MASS_BALANCE_VIOLATION", "物料平衡校验失败",
                    String.format("物料不平衡：投入 %s kg ≠ 产出 %s kg + 损耗 %s kg + 废弃 %s kg + 留样 %s kg（合计 %s kg）",
                            totals[0].toPlainString(), totals[1].toPlainString(), totals[2].toPlainString(),
                            totals[3].toPlainString(), totals[4].toPlainString(), right.toPlainString()));
        }
    }

    /**
     * INPUT 可全量消耗前提：当前责任组织、ACTIVE+NORMAL、未被操作消耗、未开始终端销售、无未结束交接、数量等于当前全部剩余量。
     * 首次销售锁定适用于全部操作类型（PROCESS / SPLIT / MERGE / REPACK 都必须经过本 INPUT 校验）。
     */
    private void verifyInputConsumable(Batch input, BigDecimal requestedQuantity, Long orgId) {
        if (!Objects.equals(input.getOrgId(), orgId)) {
            throw orgScopeDenied("输入批次 " + input.getTraceBatchNo() + " 不由本组织当前负责，禁止执行批次操作");
        }
        if (input.getConsumedByOperationId() != null || BatchFlowStatus.CLOSED.name().equals(input.getFlowStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED", "批次已被物料操作消耗",
                    "输入批次 " + input.getTraceBatchNo() + " 已关闭或已被其他批次操作全量消耗");
        }
        if (!BatchFlowStatus.ACTIVE.name().equals(input.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(input.getRiskStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED", "批次状态不可流转",
                    "输入批次 " + input.getTraceBatchNo() + " 必须为 ACTIVE+NORMAL (flowStatus="
                            + input.getFlowStatus() + ", riskStatus=" + input.getRiskStatus() + ")");
        }
        BatchSaleGuard.rejectIfSaleStarted(input, "加工、拆分、合并或分装");
        if (transferMapper.countActiveTransfersByBatchId(input.getId()) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_TRANSFER_OPEN", "批次存在未结束交接",
                    "输入批次 " + input.getTraceBatchNo() + " 存在草稿(DRAFT)或待接收(PENDING)交接，请先删除草稿或等待交接结束");
        }
        BigDecimal remaining = batchQuantityService.remainingOf(input);
        if (requestedQuantity.compareTo(remaining) != 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_INPUT_NOT_ALLOWED", "禁止部分投入",
                    "INPUT 必须全量消耗输入批次当前剩余 " + remaining.toPlainString() + " kg（本次 "
                            + requestedQuantity.toPlainString() + " kg）；如需部分加工，请先执行 SPLIT 拆分");
        }
    }

    private Long resolveOutputProductId(BatchOperationType opType, BatchOperationItemRequest out, Batch input) {
        if (out.productId() == null || Objects.equals(out.productId(), input.getProductId())) {
            return input.getProductId();
        }
        if (opType == BatchOperationType.SPLIT) {
            throw badRequest("SPLIT 只拆分追溯身份，不改变产品：OUTPUT 的 productId 必须为空或与输入批次产品一致");
        }
        Product product = productMapper.selectById(out.productId());
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + out.productId() + " 的产出产品");
        }
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_ACTIVE", "关联产品未启用",
                    "产出产品必须处于 ACTIVE 启用状态，当前状态为: " + product.getStatus());
        }
        return out.productId();
    }

    private LocalDateTime normalizeOccurredAt(BatchOperationCreateRequest req) {
        if (req.occurredAt() == null) {
            throw badRequest("业务发生时间 occurredAt 不能为空");
        }
        LocalDateTime occurred = req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
        if (occurred.isAfter(LocalDateTime.now(ZoneOffset.UTC).plus(MAX_FUTURE_SKEW))) {
            throw badRequest("业务发生时间 occurredAt 不能晚于当前时间");
        }
        return occurred;
    }

    // =====================================================================================
    // 幂等辅助
    // =====================================================================================

    private BatchOperationResponse replayCreateOrConflict(
            BatchOperation existing,
            ValidatedItems validated,
            BatchOperationType opType,
            LocalDateTime occurredAtUtc,
            String cleanNote
    ) {
        if (existing.getIsDeleted() != null && existing.getIsDeleted() != 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前创建幂等键已被一个已删除的批次操作草稿使用，请使用新的幂等键");
        }
        List<BatchOperationItem> existingItems = itemMapper.selectByOperationId(existing.getId());
        if (isSameCreateSemantics(existing, existingItems, validated, opType, occurredAtUtc, cleanNote)) {
            return loadResponse(existing);
        }
        throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                "当前创建幂等键已被使用且请求载荷与历史记录不一致");
    }

    private boolean isSameCreateSemantics(
            BatchOperation existing,
            List<BatchOperationItem> existingItems,
            ValidatedItems validated,
            BatchOperationType opType,
            LocalDateTime occurredAtUtc,
            String cleanNote
    ) {
        if (!Objects.equals(existing.getOperationType(), opType.name())
                || !existing.getOccurredAt().isEqual(occurredAtUtc)
                || !Objects.equals(existing.getNote(), cleanNote)) {
            return false;
        }
        Map<Long, Batch> itemBatches = loadBatches(existingItems);
        Long inputProductId = null;
        Map<ItemKey, Integer> existingCounts = new HashMap<>();
        for (BatchOperationItem ei : existingItems) {
            Batch b = ei.getBatchId() != null ? itemBatches.get(ei.getBatchId()) : null;
            if (BatchItemRole.INPUT.name().equals(ei.getRole()) && b != null) {
                inputProductId = b.getProductId();
            }
            existingCounts.merge(ItemKey.of(ei, b), 1, Integer::sum);
        }
        Map<ItemKey, Integer> requestCounts = new HashMap<>();
        requestCounts.merge(ItemKey.input(validated.input()), 1, Integer::sum);
        for (BatchOperationItemRequest out : validated.outputs()) {
            requestCounts.merge(ItemKey.output(out, inputProductId), 1, Integer::sum);
        }
        for (BatchOperationItemRequest other : validated.others()) {
            requestCounts.merge(ItemKey.other(other), 1, Integer::sum);
        }
        return existingCounts.equals(requestCounts);
    }

    private record ItemKey(String role, Long batchId, BigDecimal quantity, Long productId, String externalBatchNo, Integer shelfLifeDays) {
        static ItemKey of(BatchOperationItem item, Batch outputBatch) {
            BigDecimal qty = item.getQuantity().stripTrailingZeros();
            return switch (item.getRole()) {
                case "INPUT" -> new ItemKey("INPUT", item.getBatchId(), qty, null, null, null);
                case "OUTPUT" -> new ItemKey("OUTPUT", null, qty,
                        outputBatch != null ? outputBatch.getProductId() : null,
                        outputBatch != null ? outputBatch.getExternalBatchNo() : null,
                        outputBatch != null ? outputBatch.getShelfLifeDays() : null);
                default -> new ItemKey(item.getRole(), null, qty, null, null, null);
            };
        }

        static ItemKey input(BatchOperationItemRequest req) {
            return new ItemKey("INPUT", req.batchId(), req.quantity().stripTrailingZeros(), null, null, null);
        }

        static ItemKey output(BatchOperationItemRequest req, Long inputProductId) {
            Long productId = req.productId() != null ? req.productId() : inputProductId;
            String ext = req.externalBatchNo() != null && !req.externalBatchNo().isBlank() ? req.externalBatchNo().trim() : null;
            return new ItemKey("OUTPUT", null, req.quantity().stripTrailingZeros(), productId, ext, req.shelfLifeDays());
        }

        static ItemKey other(BatchOperationItemRequest req) {
            return new ItemKey(BatchItemRole.fromCode(req.role()).name(), null, req.quantity().stripTrailingZeros(), null, null, null);
        }
    }

    // =====================================================================================
    // 通用辅助
    // =====================================================================================

    private BatchOperation requireOwnOperation(Long operationId, Long orgId) {
        BatchOperation op = operationMapper.selectByIdIgnoreTenant(operationId);
        if (op == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        if (!Objects.equals(op.getOrgId(), orgId)) {
            throw orgScopeDenied("无权操作其他组织的批次操作数据");
        }
        return op;
    }

    private BatchOperation replaySubmittedOrNull(BatchOperation op, String submissionKey) {
        if (!BatchOperationStatus.SUBMITTED.name().equals(op.getStatus())) {
            return null;
        }
        if (submissionKey.equals(op.getSubmissionIdempotencyKey())) {
            return op;
        }
        throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "非法状态流转",
                "当前批次操作已处于 SUBMITTED 状态，不可重复提交");
    }

    private void requireDraftAndVersion(BatchOperation op, Long expectedVersion) {
        if (!BatchOperationStatus.DRAFT.name().equals(op.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "非法状态流转",
                    "仅草稿状态 DRAFT 的批次操作允许当前动作，当前状态为: " + op.getStatus());
        }
        if (!Objects.equals(op.getVersion(), expectedVersion)) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突",
                    "当前批次操作版本号为 " + op.getVersion() + "，请求的版本号为 " + expectedVersion);
        }
    }

    private BatchOperationResponse loadResponse(BatchOperation op) {
        List<BatchOperationItem> items = itemMapper.selectByOperationId(op.getId());
        List<BatchRelation> relations = relationMapper.selectByOperationId(op.getId());
        return buildResponse(op, items, relations);
    }

    private BatchOperationResponse buildResponse(BatchOperation op, List<BatchOperationItem> items, List<BatchRelation> relations) {
        Map<Long, Batch> batches = loadBatches(items);
        List<BatchOperationItemResponse> itemResponses = items.stream()
                .map(i -> BatchOperationItemResponse.fromEntity(i, i.getBatchId() != null ? batches.get(i.getBatchId()) : null))
                .toList();
        List<BatchRelationResponse> relationResponses = relations.stream().map(BatchRelationResponse::fromEntity).toList();
        BigDecimal[] totals = sumByRole(items);
        boolean balanced = totals[0].compareTo(totals[1].add(totals[2]).add(totals[3]).add(totals[4])) == 0;
        return BatchOperationResponse.fromEntity(op, itemResponses, relationResponses, balanced, totals);
    }

    private Map<Long, Batch> loadBatches(List<BatchOperationItem> items) {
        List<Long> ids = items.stream().map(BatchOperationItem::getBatchId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Batch> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Batch b : batchMapper.selectByIdsIgnoreTenant(ids)) {
                map.put(b.getId(), b);
            }
        }
        return map;
    }

    /** 按 INPUT、OUTPUT、LOSS、WASTE、SAMPLE 顺序汇总数量。 */
    private static BigDecimal[] sumByRole(List<BatchOperationItem> items) {
        BigDecimal[] totals = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (BatchOperationItem item : items) {
            BigDecimal qty = item.getNormalizedQuantity() != null ? item.getNormalizedQuantity() : item.getQuantity();
            int idx = roleIndex(BatchItemRole.fromCode(item.getRole()));
            totals[idx] = totals[idx].add(qty);
        }
        return totals;
    }

    private static int roleIndex(BatchItemRole role) {
        return switch (role) {
            case INPUT -> 0;
            case OUTPUT -> 1;
            case LOSS -> 2;
            case WASTE -> 3;
            case SAMPLE -> 4;
        };
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() < 16 || key.trim().length() > 128) {
            throw badRequest("Idempotency-Key 请求头必填且长度必须在 16 到 128 个字符之间");
        }
        return key.trim();
    }

    private String generateOperationNo(LocalDateTime time) {
        return "OP" + time.format(OP_NO_DATE_FORMAT) + String.format("%04d", RANDOM.nextInt(10000));
    }

    /**
     * 写权限：平台 / 系统管理员不可代办；必须是 OPERATOR；组织类型必须为 PROCESSOR（Demo MVP 路线图 Slice 3）。
     * 输入批次必须由本组织当前负责，在锁内另行校验。
     */
    private void checkWriteAccess(TraceSecurityPrincipal principal) {
        requirePrincipal(principal);
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办具体企业的加工或拆分");
        }
        if (!principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                    "该操作仅限企业操作员（OPERATOR）执行");
        }
        if (!"PROCESSOR".equals(principal.getOrgType())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_TYPE_NOT_ALLOWED", "组织类型不允许当前操作",
                    "加工与拆分仅限加工企业（PROCESSOR）执行");
        }
    }

    private void requirePrincipal(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }

    private static BusinessException orgScopeDenied(String detail) {
        return new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", detail);
    }
}
