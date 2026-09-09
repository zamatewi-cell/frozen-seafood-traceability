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
import com.example.traceability.batch.dto.BatchOperationItemResponse;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.dto.BatchRelationResponse;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.batch.mapper.BatchOperationMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 批次操作与谱系边应用服务。
 * <p>
 * 负责批次操作草稿创建、服务端物料平衡重新计算、引用批次活性/组织校验、
 * 输入批次历史累计量校验、输出批次唯一生产来源校验、基于 MySQL 8.4 recursive CTE 的成环检测、
 * 稳定顺序行锁控制、两套幂等并发安全恢复及谱系边生成。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchOperationApplicationService {

    private static final BigDecimal MASS_BALANCE_TOLERANCE = new BigDecimal("0.001");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter OP_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final BatchOperationMapper operationMapper;
    private final BatchOperationItemMapper itemMapper;
    private final BatchRelationMapper relationMapper;
    private final BatchMapper batchMapper;

    public BatchOperationApplicationService(
            BatchOperationMapper operationMapper,
            BatchOperationItemMapper itemMapper,
            BatchRelationMapper relationMapper,
            BatchMapper batchMapper
    ) {
        this.operationMapper = operationMapper;
        this.itemMapper = itemMapper;
        this.relationMapper = relationMapper;
        this.batchMapper = batchMapper;
    }

    /**
     * 创建批次操作草稿。
     * <p>
     * 仅限 OPERATOR 角色操作。服务端推导组织与操作人上下文。
     * 强制校验 Idempotency-Key；同组织相同幂等键重放原操作，不同语义返回 409 IDEMPOTENCY_CONFLICT。
     * </p>
     *
     * @param req            操作创建请求
     * @param idempotencyKey 客户端创建幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 创建或重放的批次操作详情
     */
    @Transactional
    public BatchOperationResponse createDraftOperation(
            BatchOperationCreateRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);

        // 1. 幂等键基础格式校验
        String cleanIdempotencyKey = validateIdempotencyKey(idempotencyKey, "Idempotency-Key");

        // 2. 操作类型校验与规范化
        if (!BatchOperationType.isValid(req.operationType())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "不支持的批次操作类型: " + req.operationType()
            );
        }
        BatchOperationType opType = BatchOperationType.fromCode(req.operationType());

        // 3. 项目明细基础校验、单位校验、数量校验与 batchId 规则校验
        List<BatchOperationItemRequest> itemRequests = req.items();
        if (itemRequests == null || itemRequests.size() < 2) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "操作明细项目 items 不能为空且至少包含 2 个项目"
            );
        }

        int inputCount = 0;
        int outputCount = 0;
        Set<Long> referencedBatchIds = new HashSet<>();

        for (BatchOperationItemRequest item : itemRequests) {
            if (!BatchItemRole.isValid(item.role())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "不支持的项目角色: " + item.role()
                );
            }
            BatchItemRole role = BatchItemRole.fromCode(item.role());

            if (item.unitCode() == null || !"kg".equalsIgnoreCase(item.unitCode().trim())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "操作明细计量单位仅允许 kg"
                );
            }

            if (item.quantity() == null || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "明细数量必须大于 0"
                );
            }
            if (item.quantity().scale() > 3) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "明细数量最多保留 3 位小数"
                );
            }

            if (role == BatchItemRole.INPUT || role == BatchItemRole.OUTPUT) {
                if (item.batchId() == null || item.batchId() <= 0) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "参数校验失败",
                            role.name() + " 角色项目必须指定有效的 batchId"
                    );
                }
                // 同一操作中批次 ID 不得重复，且同一批次不得同时作为投入和产出
                if (!referencedBatchIds.add(item.batchId())) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "参数校验失败",
                            "同一操作中批次 ID 不得重复引用: " + item.batchId()
                    );
                }
                if (role == BatchItemRole.INPUT) {
                    inputCount++;
                } else {
                    outputCount++;
                }
            } else {
                // LOSS/WASTE/SAMPLE 角色严禁引用批次
                if (item.batchId() != null) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "参数校验失败",
                            role.name() + " 角色项目严禁关联批次 batchId"
                    );
                }
            }
        }

        // 4. 操作类型基数（Cardinality）约束检查
        validateCardinality(opType, inputCount, outputCount);

        Long orgId = principal.getOrgId();

        // 5. 幂等预检：查询同组织下是否已存在该创建幂等键
        BatchOperation existing = operationMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanIdempotencyKey);
        if (existing != null) {
            List<BatchOperationItem> existingItems = itemMapper.selectByOperationId(existing.getId());
            if (isSameCreateSemantics(existing, existingItems, req, opType)) {
                List<BatchRelation> relations = relationMapper.selectByOperationId(existing.getId());
                return buildResponse(existing, existingItems, relations);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前创建幂等键已被使用且请求载荷与历史记录不一致"
            );
        }

        // 6. 构造操作主表与明细并持久化
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String operationNo = generateOperationNo(nowUtc);

        BatchOperation op = new BatchOperation();
        op.setOrgId(orgId);
        op.setOperationNo(operationNo);
        op.setOperationType(opType.name());
        op.setOccurredAt(req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        op.setRecordedAt(nowUtc);
        op.setStatus(BatchOperationStatus.DRAFT.name());
        op.setIdempotencyKey(cleanIdempotencyKey);
        op.setNote(req.note() != null ? req.note().trim() : null);
        op.setVersion(0L);
        op.setIsDeleted(0);
        op.setCreatedAt(nowUtc);
        op.setCreatedBy(principal.getUserId());
        op.setUpdatedAt(nowUtc);
        op.setUpdatedBy(principal.getUserId());

        try {
            operationMapper.insert(op);
        } catch (DuplicateKeyException e) {
            // 当前读恢复
            BatchOperation dup = operationMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
            if (dup != null) {
                List<BatchOperationItem> dupItems = itemMapper.selectByOperationId(dup.getId());
                if (isSameCreateSemantics(dup, dupItems, req, opType)) {
                    List<BatchRelation> relations = relationMapper.selectByOperationId(dup.getId());
                    return buildResponse(dup, dupItems, relations);
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "幂等提交冲突",
                        "并发检测到相同创建幂等键，但请求载荷与已落库数据不一致"
                );
            }
            throw e;
        }

        List<BatchOperationItem> itemsToInsert = new ArrayList<>();
        for (BatchOperationItemRequest ir : itemRequests) {
            BatchOperationItem item = new BatchOperationItem();
            item.setOperationId(op.getId());
            item.setBatchId(ir.batchId());
            item.setRole(BatchItemRole.fromCode(ir.role()).name());
            item.setQuantity(ir.quantity());
            item.setUnitCode("kg");
            item.setNormalizedQuantity(ir.quantity()); // 本阶段基准单位为 kg，normalizedQuantity = quantity
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

        // 重新读取已入库明细以获取生成的自增 ID
        List<BatchOperationItem> insertedItems = itemMapper.selectByOperationId(op.getId());
        return buildResponse(op, insertedItems, List.of());
    }

    /**
     * 提交批次操作草稿并生成谱系边。
     * <p>
     * 仅限本组织 OPERATOR 角色操作。
     * 服务端重新计算物料平衡、按稳定顺序对批次加行锁、校验批次活性及声明量/可用量、
     * 检查输出批次唯一生产来源、利用 MySQL 8.4 递归 CTE 环检测，并原子写入谱系边。
     * </p>
     *
     * @param operationId    批次操作 ID
     * @param req            提交请求参数（必须携带 version）
     * @param idempotencyKey 客户端提交防重幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 提交成功后的批次操作详情及生成的谱系边
     */
    @Transactional
    public BatchOperationResponse submitOperation(
            Long operationId,
            BatchOperationSubmitRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);

        String cleanSubmissionKey = validateIdempotencyKey(idempotencyKey, "Idempotency-Key");

        if (req == null || req.version() == null || req.version() < 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "乐观锁版本号 version 不能为空且必须非负"
            );
        }

        Long orgId = principal.getOrgId();

        // 1. 前置查询与安全组织边界校验
        BatchOperation existing = operationMapper.selectByIdIgnoreTenant(operationId);
        if (existing == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        if (!Objects.equals(existing.getOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权操作其他组织的批次操作数据"
            );
        }

        // 2. 提交幂等已完成检查
        if (BatchOperationStatus.SUBMITTED.name().equals(existing.getStatus())) {
            if (cleanSubmissionKey.equals(existing.getSubmissionIdempotencyKey())) {
                List<BatchOperationItem> items = itemMapper.selectByOperationId(existing.getId());
                List<BatchRelation> relations = relationMapper.selectByOperationId(existing.getId());
                return buildResponse(existing, items, relations);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "当前批次操作已处于 SUBMITTED 状态，不可重复提交"
            );
        }

        // 检查同组织下提交幂等键是否已被其他操作占用
        BatchOperation existingSubmission = operationMapper.selectByOrgIdAndSubmissionKey(orgId, cleanSubmissionKey);
        if (existingSubmission != null && !Objects.equals(existingSubmission.getId(), operationId)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前提交幂等键已被本组织其他批次操作使用"
            );
        }

        if (!BatchOperationStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅草稿状态 DRAFT 的批次操作允许提交，当前状态为: " + existing.getStatus()
            );
        }

        if (!Objects.equals(existing.getVersion(), req.version())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "当前批次操作版本号为 " + existing.getVersion() + "，请求提交的版本号为 " + req.version()
            );
        }

        // 3. 稳定顺序加排他行锁（死锁防范）
        // 先对批次操作主记录加排他锁
        BatchOperation lockedOp = operationMapper.selectByIdForUpdate(operationId);
        if (lockedOp == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
        }
        if (BatchOperationStatus.SUBMITTED.name().equals(lockedOp.getStatus())) {
            if (cleanSubmissionKey.equals(lockedOp.getSubmissionIdempotencyKey())) {
                List<BatchOperationItem> items = itemMapper.selectByOperationId(lockedOp.getId());
                List<BatchRelation> relations = relationMapper.selectByOperationId(lockedOp.getId());
                return buildResponse(lockedOp, items, relations);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "当前批次操作已并发流转为 SUBMITTED 状态"
            );
        }

        List<BatchOperationItem> items = itemMapper.selectByOperationId(operationId);
        if (items.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "操作明细为空",
                    "当前批次操作不存在任何明细项目"
            );
        }

        // 提取所有涉及的 batchId，升序排序后逐个加排他行锁
        List<Long> distinctBatchIds = items.stream()
                .map(BatchOperationItem::getBatchId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        Map<Long, Batch> lockedBatchMap = new HashMap<>();
        for (Long bid : distinctBatchIds) {
            Batch b = batchMapper.selectByIdIgnoreTenantForUpdate(bid);
            if (b == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + bid + " 的关联批次");
            }
            if (!Objects.equals(b.getOrgId(), orgId)) {
                throw new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ORG_SCOPE_DENIED",
                        "组织数据访问越权",
                        "关联批次 " + b.getBatchNo() + " 属于其他企业组织，严禁跨组织流转"
                );
            }
            if (!BatchStatus.ACTIVE.name().equals(b.getStatus())) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_FLOW_BLOCKED",
                        "批次状态不可流转",
                        "批次 " + b.getBatchNo() + " 状态为 " + b.getStatus() + "，仅 ACTIVE 状态批次允许参与操作流转"
                );
            }
            lockedBatchMap.put(bid, b);
        }

        // 4. 服务端独立重新计算物料平衡 (Mass Balance)
        BigDecimal sumInput = BigDecimal.ZERO;
        BigDecimal sumOutputAndLoss = BigDecimal.ZERO;

        List<BatchOperationItem> inputItems = new ArrayList<>();
        List<BatchOperationItem> outputItems = new ArrayList<>();

        for (BatchOperationItem item : items) {
            BatchItemRole role = BatchItemRole.fromCode(item.getRole());
            BigDecimal normalizedQty = item.getNormalizedQuantity();
            if (role == BatchItemRole.INPUT) {
                sumInput = sumInput.add(normalizedQty);
                inputItems.add(item);
            } else {
                sumOutputAndLoss = sumOutputAndLoss.add(normalizedQty);
                if (role == BatchItemRole.OUTPUT) {
                    outputItems.add(item);
                }
            }
        }

        BigDecimal balanceDiff = sumInput.subtract(sumOutputAndLoss).abs();
        if (balanceDiff.compareTo(MASS_BALANCE_TOLERANCE) > 0) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_MASS_BALANCE_VIOLATION",
                    "物料平衡校验失败",
                    String.format("物料不平衡：投入总量(%s kg)与产出损耗总量(%s kg)差值超过容差 0.001 kg",
                            sumInput.toPlainString(), sumOutputAndLoss.toPlainString())
            );
        }

        // 5. 输出批次唯一生产来源与声明数量强校验
        for (BatchOperationItem outItem : outputItems) {
            int upstreamCount = relationMapper.countUpstreamRelationsByChildBatchId(outItem.getBatchId());
            if (upstreamCount > 0) {
                Batch outBatch = lockedBatchMap.get(outItem.getBatchId());
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_OUTPUT_ALREADY_PRODUCED",
                        "输出批次已被产出",
                        "批次 " + outBatch.getBatchNo() + " 已存在上游谱系边，一个批次只能由一次已提交操作产出"
                );
            }
            Batch outBatch = lockedBatchMap.get(outItem.getBatchId());
            if (outItem.getQuantity().compareTo(outBatch.getQuantity()) != 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_OUTPUT_QUANTITY_MISMATCH",
                        "输出批次数量不匹配",
                        String.format("输出项目数量(%s kg)必须严格等于批次初始声明数量(%s kg)",
                                outItem.getQuantity().toPlainString(), outBatch.getQuantity().toPlainString())
                );
            }
        }

        // 6. 输入批次累计占用量校验（历史已提交 INPUT + 本次 INPUT <= 批次声明量）
        for (BatchOperationItem inItem : inputItems) {
            BigDecimal historicalUsed = itemMapper.sumSubmittedInputQuantityByBatchId(inItem.getBatchId());
            BigDecimal totalRequested = historicalUsed.add(inItem.getQuantity());
            Batch inBatch = lockedBatchMap.get(inItem.getBatchId());
            if (totalRequested.compareTo(inBatch.getQuantity()) > 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_QUANTITY_EXCEEDED",
                        "输入批次数量超额",
                        String.format("输入批次 %s 累计投入量(%s kg)超过声明数量(%s kg)，历史已用 %s kg，本次投入 %s kg",
                                inBatch.getBatchNo(), totalRequested.toPlainString(), inBatch.getQuantity().toPlainString(),
                                historicalUsed.toPlainString(), inItem.getQuantity().toPlainString())
                );
            }
        }

        // 7. 笛卡尔积生成关系边、自环检查与关系类型映射
        BatchOperationType opType = BatchOperationType.fromCode(lockedOp.getOperationType());
        String relationType = switch (opType) {
            case MERGE -> BatchRelationType.MERGE.name();
            case SPLIT -> BatchRelationType.SPLIT.name();
            case PROCESS, REPACK -> BatchRelationType.TRANSFORM.name();
        };

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        List<BatchRelation> relationsToInsert = new ArrayList<>();

        for (BatchOperationItem inItem : inputItems) {
            for (BatchOperationItem outItem : outputItems) {
                Long parentId = inItem.getBatchId();
                Long childId = outItem.getBatchId();

                if (Objects.equals(parentId, childId)) {
                    throw new BusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "BATCH_RELATION_SELF_LOOP",
                            "谱系关系自环",
                            "批次 " + parentId + " 试图与自身建立谱系边，违反非自环规则"
                    );
                }

                // 8. 基于 MySQL 8.4 recursive CTE 成环检测
                // 检查拟新增边 parent -> child 是否在现有图中已存在 child ~> parent 的可达路径
                int cycleCount = relationMapper.checkCycleWithCte(childId, parentId);
                if (cycleCount > 0) {
                    throw new BusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "BATCH_RELATION_CYCLE",
                            "批次谱系成环",
                            String.format("检测到循环关系：子批次 %d 已存在到达父批次 %d 的上游路径，禁止成环", childId, parentId)
                    );
                }

                BatchRelation relation = new BatchRelation();
                relation.setOperationId(operationId);
                relation.setParentBatchId(parentId);
                relation.setChildBatchId(childId);
                relation.setRelationType(relationType);
                relation.setCreatedAt(nowUtc);
                relationsToInsert.add(relation);
            }
        }

        // 9. 单条原子更新状态流转、记录提交幂等键并递增版本号
        int affected = operationMapper.submitOperation(
                operationId,
                orgId,
                req.version(),
                cleanSubmissionKey,
                nowUtc,
                principal.getUserId()
        );

        if (affected == 0) {
            // 当前锁定读排查
            BatchOperation latest = operationMapper.selectByIdForUpdate(operationId);
            if (latest == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + operationId + " 的批次操作");
            }
            if (BatchOperationStatus.SUBMITTED.name().equals(latest.getStatus())) {
                if (cleanSubmissionKey.equals(latest.getSubmissionIdempotencyKey())) {
                    List<BatchRelation> existingRelations = relationMapper.selectByOperationId(operationId);
                    return buildResponse(latest, items, existingRelations);
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION",
                        "非法状态流转",
                        "当前批次操作已并发被其他请求提交"
                );
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "并发修改导致版本冲突，当前版本为 " + latest.getVersion() + "，请刷新后重试"
            );
        }

        // 10. 批量持久化谱系边
        if (!relationsToInsert.isEmpty()) {
            relationMapper.insertBatch(relationsToInsert);
        }

        // 重新获取更新后的实体与边
        BatchOperation submittedOp = operationMapper.selectByIdAndOrgId(operationId, orgId);
        List<BatchRelation> insertedRelations = relationMapper.selectByOperationId(operationId);
        return buildResponse(submittedOp, items, insertedRelations);
    }

    private void validateCardinality(BatchOperationType opType, int inputCount, int outputCount) {
        switch (opType) {
            case MERGE -> {
                if (inputCount < 2 || outputCount < 1) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "操作基数约束不符",
                            "MERGE 合并操作必须至少包含 2 个 INPUT 项目且至少包含 1 个 OUTPUT 项目"
                    );
                }
            }
            case SPLIT -> {
                if (inputCount < 1 || outputCount < 2) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "操作基数约束不符",
                            "SPLIT 拆分操作必须至少包含 1 个 INPUT 项目且至少包含 2 个 OUTPUT 项目"
                    );
                }
            }
            case PROCESS, REPACK -> {
                if (inputCount < 1 || outputCount < 1) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "操作基数约束不符",
                            opType.name() + " 操作必须至少包含 1 个 INPUT 项目且至少包含 1 个 OUTPUT 项目"
                    );
                }
            }
        }
    }

    private boolean isSameCreateSemantics(
            BatchOperation existing,
            List<BatchOperationItem> existingItems,
            BatchOperationCreateRequest req,
            BatchOperationType opType
    ) {
        if (!Objects.equals(existing.getOperationType(), opType.name())) {
            return false;
        }
        LocalDateTime reqOccurred = req.occurredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        long diffSeconds = Math.abs(java.time.temporal.ChronoUnit.SECONDS.between(existing.getOccurredAt(), reqOccurred));
        if (diffSeconds > 1) {
            return false;
        }
        String cleanNote = req.note() != null ? req.note().trim() : null;
        if (!Objects.equals(existing.getNote(), cleanNote)) {
            return false;
        }

        if (existingItems.size() != req.items().size()) {
            return false;
        }

        for (int i = 0; i < existingItems.size(); i++) {
            BatchOperationItem ei = existingItems.get(i);
            BatchOperationItemRequest ri = req.items().get(i);

            if (!Objects.equals(ei.getRole(), BatchItemRole.fromCode(ri.role()).name())) {
                return false;
            }
            if (!Objects.equals(ei.getBatchId(), ri.batchId())) {
                return false;
            }
            if (ei.getQuantity().compareTo(ri.quantity()) != 0) {
                return false;
            }
            if (!"kg".equalsIgnoreCase(ri.unitCode().trim())) {
                return false;
            }
        }
        return true;
    }

    private BatchOperationResponse buildResponse(
            BatchOperation op,
            List<BatchOperationItem> items,
            List<BatchRelation> relations
    ) {
        List<BatchOperationItemResponse> itemResponses = items.stream()
                .map(BatchOperationItemResponse::fromEntity)
                .toList();

        List<BatchRelationResponse> relationResponses = relations.stream()
                .map(BatchRelationResponse::fromEntity)
                .toList();

        return BatchOperationResponse.fromEntity(op, itemResponses, relationResponses, Boolean.TRUE);
    }

    private String validateIdempotencyKey(String key, String headerName) {
        if (key == null || key.isBlank() || key.trim().length() < 16 || key.trim().length() > 128) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    headerName + " 请求头必填且长度必须在 16 到 128 个字符之间"
            );
        }
        return key.trim();
    }

    private String generateOperationNo(LocalDateTime time) {
        return "OP" + time.format(OP_NO_DATE_FORMAT) + String.format("%04d", RANDOM.nextInt(10000));
    }

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null || !principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "权限不足",
                    "该操作仅限企业操作员（OPERATOR）执行"
            );
        }
    }
}
