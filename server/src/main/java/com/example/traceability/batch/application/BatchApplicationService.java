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
import com.example.traceability.batch.dto.DirectStockInRequest;
import com.example.traceability.batch.dto.ProcessRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 追溯批次应用服务。
 * <p>
 * 负责批次草稿创建、组织隔离分页查询、详情查看、基于单条 SQL 条件约束的草稿增量更新以及 DRAFT -> ACTIVE 提交流转。
 * 严格防范跨组织越权与并发冲突，支持客户端幂等提交。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchApplicationService {

    private final BatchMapper batchMapper;
    private final ProductMapper productMapper;

    public BatchApplicationService(BatchMapper batchMapper, ProductMapper productMapper) {
        this.batchMapper = batchMapper;
        this.productMapper = productMapper;
    }

    /**
     * 分页查询批次列表。
     * <p>
     * PLATFORM scope 角色可查询全平台批次；其余企业认证用户必须在 Mapper 层强制限定为 {@code principal.getOrgId()}。
     * </p>
     *
     * @param criteria  查询过滤条件
     * @param principal 当前认证主体
     * @return 包含分页元数据的批次列表封套
     */
    public SuccessEnvelope<List<BatchResponse>> listBatches(BatchQueryCriteria criteria, TraceSecurityPrincipal principal) {
        if (criteria.page() < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "页码 page 最小值为 1");
        }
        if (criteria.size() < 1 || criteria.size() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "分页大小 size 必须在 1 到 100 之间");
        }

        String normalizedStatus = null;
        if (criteria.status() != null && !criteria.status().isBlank()) {
            String trimmed = criteria.status().trim();
            if (!BatchStatus.isValid(trimmed)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的批次状态: " + criteria.status());
            }
            normalizedStatus = BatchStatus.fromCode(trimmed).name();
        }

        boolean isPlatform = isPlatformScope(principal);
        Long targetOrgId = isPlatform ? null : principal.getOrgId();

        long totalCount = batchMapper.countBatches(targetOrgId, normalizedStatus);
        long offset = (long) (criteria.page() - 1) * criteria.size();
        List<Batch> records = batchMapper.selectBatchesPage(targetOrgId, normalizedStatus, offset, criteria.size());

        List<BatchResponse> dtos = records.stream()
                .map(BatchResponse::fromEntity)
                .toList();

        PageMeta pageMeta = new PageMeta(criteria.page(), criteria.size(), totalCount);
        return SuccessEnvelope.ofPage(dtos, pageMeta);
    }

    /**
     * 根据内部主键 ID 查询批次详情。
     * <p>
     * 若批次不存在返回 404 RESOURCE_NOT_FOUND；已存在但非本组织且非平台管理员时返回 403 ORG_SCOPE_DENIED。
     * </p>
     *
     * @param batchId   批次 ID
     * @param principal 当前认证主体
     * @return 批次详情响应 DTO
     */
    public BatchResponse getBatchById(Long batchId, TraceSecurityPrincipal principal) {
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        if (!isPlatformScope(principal) && !Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权访问其他组织的批次数据"
            );
        }

        return BatchResponse.fromEntity(batch);
    }

    /**
     * 创建批次草稿。
     * <p>
     * 仅允许 OPERATOR 角色操作。系统管理员按 RBAC 原则默认不能代写企业业务。
     * 强制携带 Idempotency-Key；同组织同 key 且请求语义相同时返回原批次；语义不同时返回 409 IDEMPOTENCY_CONFLICT。
     * </p>
     *
     * @param req            批次创建请求
     * @param idempotencyKey 客户端幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 创建或已存在的批次详情
     */
    @Transactional
    public BatchResponse createDraftBatch(BatchCreateRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);

        // 1. 幂等键基础格式校验
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() < 16 || idempotencyKey.trim().length() > 128) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "Idempotency-Key 请求头必填且长度必须在 16 到 128 个字符之间"
            );
        }
        String cleanIdempotencyKey = idempotencyKey.trim();

        // 2. 枚举字段与基础参数业务校验与规范化
        if (!BatchType.isValid(req.batchType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的批次环节类型: " + req.batchType());
        }
        String normalizedBatchType = BatchType.fromCode(req.batchType()).name();

        if (!OriginType.isValid(req.originType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的来源类型: " + req.originType());
        }
        String normalizedOriginType = OriginType.fromCode(req.originType()).name();

        if (req.unitCode() == null || !"kg".equalsIgnoreCase(req.unitCode().trim())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "批次计量单位仅允许 kg");
        }
        String normalizedUnitCode = "kg";

        if (req.quantity() == null || req.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "批次数量必须大于 0");
        }
        if (req.quantity().scale() > 3) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "批次数量最多保留 3 位小数");
        }

        if (req.shelfLifeDays() != null && req.shelfLifeDays() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "保质期天数必须大于 0");
        }

        Long orgId = principal.getOrgId();

        // 3. 幂等预检：检查同组织下是否已存在该幂等键
        // 幂等语义重放必须在可变产品状态校验之前执行，即便产品后续变为 INACTIVE，相同载荷仍返回原批次
        Batch existingByIdempotency = batchMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanIdempotencyKey);
        if (existingByIdempotency != null) {
            if (isSameCreateSemantics(existingByIdempotency, req, normalizedBatchType, normalizedOriginType, normalizedUnitCode)) {
                return BatchResponse.fromEntity(existingByIdempotency);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前幂等键已被使用且请求载荷与历史记录不一致"
            );
        }

        // 4. 首次创建时校验关联海产品主数据存在性与 ACTIVE 状态
        // 采用排他行锁 SELECT ... FOR UPDATE 消除 TOCTOU 竞态，确保在 batch 事务提交前产品不能并发停用
        Product product = productMapper.selectByIdForUpdate(req.productId());
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + req.productId() + " 的关联海产品");
        }
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PRODUCT_NOT_ACTIVE",
                    "关联产品未启用",
                    "关联海产品必须处于 ACTIVE 启用状态，当前状态为: " + product.getStatus()
            );
        }

        // 5. 批次号同组织唯一性检查
        String cleanBatchNo = req.batchNo().trim();
        Batch existingByBatchNo = batchMapper.selectByOrgIdAndBatchNo(orgId, cleanBatchNo);
        if (existingByBatchNo != null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_NO_CONFLICT",
                    "批次号冲突",
                    "当前组织下已存在相同批次号: " + cleanBatchNo
            );
        }

        // 6. 构造新批次实体并持久化
        Batch batch = new Batch();
        batch.setOrgId(orgId);
        batch.setProductId(req.productId());
        batch.setBatchNo(cleanBatchNo);
        batch.setBatchType(normalizedBatchType);
        batch.setQuantity(req.quantity());
        batch.setUnitCode(normalizedUnitCode);
        batch.setOriginType(normalizedOriginType);
        batch.setOriginText(req.originText().trim());
        batch.setProductionDate(req.productionDate());
        batch.setCaptureDate(req.captureDate());
        batch.setFreezeDate(req.freezeDate());
        batch.setShelfLifeDays(req.shelfLifeDays());
        batch.setStatus(BatchStatus.DRAFT.name());
        batch.setCreationIdempotencyKey(cleanIdempotencyKey);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedBy(principal.getUserId());
        batch.setUpdatedBy(principal.getUserId());
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        batch.setCreatedAt(nowUtc);
        batch.setUpdatedAt(nowUtc);

        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            // 并发竞态安全当前读重查（SELECT ... FOR UPDATE 打破 REPEATABLE READ 快照读限制，穿透读取最新已提交行）
            Batch dupIdempotency = batchMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
            if (dupIdempotency != null) {
                if (isSameCreateSemantics(dupIdempotency, req, normalizedBatchType, normalizedOriginType, normalizedUnitCode)) {
                    return BatchResponse.fromEntity(dupIdempotency);
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "幂等提交冲突",
                        "并发检测到相同幂等键，但请求载荷与已落库数据不一致"
                );
            }

            Batch dupBatchNo = batchMapper.selectByOrgIdAndBatchNoForUpdate(orgId, cleanBatchNo);
            if (dupBatchNo != null) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "BATCH_NO_CONFLICT",
                        "批次号冲突",
                        "当前组织下已存在相同批次号: " + cleanBatchNo
                );
            }

            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_NO_CONFLICT",
                    "批次号或幂等键冲突",
                    "数据冲突，请检查批次号与幂等键后重试"
            );
        }

        return BatchResponse.fromEntity(batch);
    }

    /**
     * 增量更新批次草稿。
     * <p>
     * 仅限 OPERATOR 角色更新本组织 DRAFT 状态批次。
     * 单条 SQL 同时约束 id + org_id + status='DRAFT' + version，行数为 0 时精准区分跨组织、非草稿与版本冲突。
     * </p>
     *
     * @param batchId   批次 ID
     * @param req       更新请求
     * @param principal 当前认证主体
     * @return 更新后的批次详情
     */
    @Transactional
    public BatchResponse patchDraftBatch(Long batchId, BatchPatchRequest req, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);

        if (req.version() == null || req.version() < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "乐观锁版本号 version 不能为空且必须非负");
        }

        // 1. 前置安全与状态检查
        Batch existing = batchMapper.selectByIdIgnoreTenant(batchId);
        if (existing == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        if (!Objects.equals(existing.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权修改其他组织的批次数据"
            );
        }

        if (!BatchStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅草稿状态批次允许修改，当前状态为: " + existing.getStatus()
            );
        }

        if (!Objects.equals(existing.getVersion(), req.version())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "当前批次版本号为 " + existing.getVersion() + "，请求提交的版本号为 " + req.version()
            );
        }

        // 2. 准备待更新字段（batchNo, productId, batchType, originType, unitCode 在此切片不可变）
        if (req.quantity() != null) {
            if (req.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "批次数量必须大于 0");
            }
            if (req.quantity().scale() > 3) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "批次数量最多保留 3 位小数");
            }
            existing.setQuantity(req.quantity());
        }

        if (req.originText() != null && !req.originText().isBlank()) {
            existing.setOriginText(req.originText().trim());
        }

        if (req.productionDate() != null) {
            existing.setProductionDate(req.productionDate());
        }

        if (req.captureDate() != null) {
            existing.setCaptureDate(req.captureDate());
        }

        if (req.freezeDate() != null) {
            existing.setFreezeDate(req.freezeDate());
        }

        if (req.shelfLifeDays() != null) {
            if (req.shelfLifeDays() <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "保质期天数必须大于 0");
            }
            existing.setShelfLifeDays(req.shelfLifeDays());
        }

        existing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        existing.setUpdatedBy(principal.getUserId());

        // 3. 单条 SQL 原子条件更新 (约束 id + org_id + status='DRAFT' + version)
        int affectedRows = batchMapper.updateDraftBatch(existing, req.version());
        if (affectedRows == 0) {
            // 并发更新失败，使用当前锁定读 (SELECT ... FOR UPDATE) 读取最新已提交行精确分类，打破 REPEATABLE READ 快照读盲区
            Batch latest = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
            if (latest == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
            }
            if (!Objects.equals(latest.getOrgId(), principal.getOrgId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权修改其他组织的批次数据");
            }
            if (!BatchStatus.DRAFT.name().equals(latest.getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "非法状态流转", "批次状态已变更，当前状态为: " + latest.getStatus());
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "并发修改导致版本冲突，当前版本为 " + latest.getVersion() + "，请刷新后重试"
            );
        }

        Batch updated = batchMapper.selectByIdAndOrgId(batchId, principal.getOrgId());
        return BatchResponse.fromEntity(updated);
    }

    /**
     * 提交批次草稿（DRAFT -> ACTIVE）。
     * <p>
     * 仅限 OPERATOR 角色操作。关联产品必须处于 ACTIVE 状态。
     * 单条 SQL 同时约束 id + org_id + status='DRAFT' + version，原子递增版本号。
     * </p>
     *
     * @param batchId   批次 ID
     * @param req       提交请求体（包含期望版本号 version）
     * @param principal 当前认证主体
     * @return 提交激活后的批次详情
     */
    @Transactional
    public BatchResponse submitDraftBatch(Long batchId, BatchSubmitRequest req, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);

        if (req.version() == null || req.version() < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "乐观锁版本号 version 不能为空且必须非负");
        }

        // 1. 前置安全与状态检查
        Batch existing = batchMapper.selectByIdIgnoreTenant(batchId);
        if (existing == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        if (!Objects.equals(existing.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权提交其他组织的批次"
            );
        }

        if (!BatchStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅草稿状态批次允许提交激活，当前状态为: " + existing.getStatus()
            );
        }

        if (!Objects.equals(existing.getVersion(), req.version())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "当前批次版本号为 " + existing.getVersion() + "，请求提交的版本号为 " + req.version()
            );
        }

        // 2. 关联海产品主数据存在性与 ACTIVE 状态检查
        // 采用排他行锁 SELECT ... FOR UPDATE 消除 TOCTOU 竞态，确保在 batch 激活提交事务完成前产品不能并发停用
        Product product = productMapper.selectByIdForUpdate(existing.getProductId());
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + existing.getProductId() + " 的关联海产品");
        }
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PRODUCT_NOT_ACTIVE",
                    "关联产品未启用",
                    "关联海产品必须处于 ACTIVE 启用状态，当前状态为: " + product.getStatus()
            );
        }

        // 3. 单条 SQL 原子状态流转与版本递增 (id + org_id + status='DRAFT' + version)
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        int affectedRows = batchMapper.submitDraftBatch(batchId, principal.getOrgId(), req.version(), nowUtc, principal.getUserId());
        if (affectedRows == 0) {
            // 并发提交流转失败，使用当前锁定读 (SELECT ... FOR UPDATE) 读取最新已提交行精确分类，打破 REPEATABLE READ 快照读盲区
            Batch latest = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
            if (latest == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
            }
            if (!Objects.equals(latest.getOrgId(), principal.getOrgId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权提交其他组织的批次");
            }
            if (!BatchStatus.DRAFT.name().equals(latest.getStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION",
                        "非法状态流转",
                        "批次状态已变更，当前状态为: " + latest.getStatus()
                );
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "并发修改导致版本冲突，当前版本为 " + latest.getVersion() + "，请刷新后重试"
            );
        }

        Batch submitted = batchMapper.selectByIdAndOrgId(batchId, principal.getOrgId());
        return BatchResponse.fromEntity(submitted);
    }

    // ==================== 直接入库(捕捞船长自捕自产) ====================

    @Transactional(rollbackFor = Exception.class)
    public BatchResponse directStockIn(DirectStockInRequest req, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);
        if (req.quantity() == null || req.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "入库数量必须大于 0");
        }
        Product product = productMapper.selectById(req.productId());
        if (product == null || !"ACTIVE".equals(product.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "关联商品不存在或已停用");
        }
        Batch batch = new Batch();
        batch.setOrgId(principal.getOrgId());
        batch.setProductId(req.productId());
        batch.setBatchNo(req.batchNo() != null && !req.batchNo().isBlank()
                ? req.batchNo().trim()
                : "DS-" + System.currentTimeMillis());
        batch.setBatchType("SOURCE");
        batch.setQuantity(req.quantity());
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText(req.originText() != null ? req.originText().trim() : "自捕捞入库");
        batch.setCaptureDate(req.captureDate() != null ? req.captureDate() : java.time.LocalDate.now());
        batch.setStatus("ACTIVE");
        batch.setCreatedBy(principal.getUserId());
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batchMapper.insert(batch);
        return BatchResponse.fromEntity(batch);
    }

    // ==================== 加工厂加工:消耗原料批次 → 生成成品批次 ====================

    @Transactional(rollbackFor = Exception.class)
    public BatchResponse processMaterials(ProcessRequest req, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);
        if (req.consumedQuantity() == null || req.consumedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "消耗数量必须大于 0");
        }
        if (req.outputQuantity() == null || req.outputQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "产出数量必须大于 0");
        }
        // 查原料批次,校验归属和可用量
        Batch sourceBatch = batchMapper.selectById(req.sourceBatchId());
        if (sourceBatch == null || !sourceBatch.getOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问", "原料批次不存在或不属于本组织");
        }
        if (!"ACTIVE".equals(sourceBatch.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_NOT_ACTIVE", "批次不可用", "原料批次状态非ACTIVE");
        }
        BigDecimal available = sourceBatch.getQuantity() == null ? BigDecimal.ZERO : sourceBatch.getQuantity();
        if (available.compareTo(req.consumedQuantity()) < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_INSUFFICIENT", "原料不足",
                    "原料批次可用量 " + available + "kg，不足以消耗 " + req.consumedQuantity() + "kg");
        }
        // 校验产出商品是加工成品
        Product outputProduct = productMapper.selectById(req.outputProductId());
        if (outputProduct == null || !"ACTIVE".equals(outputProduct.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "产出商品不存在或已停用");
        }

        // 扣减原料批次数量
        sourceBatch.setQuantity(available.subtract(req.consumedQuantity()));
        sourceBatch.setUpdatedBy(principal.getUserId());
        sourceBatch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (sourceBatch.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            sourceBatch.setStatus("CLOSED");
        }
        batchMapper.updateById(sourceBatch);

        // 生成成品批次
        Batch outputBatch = new Batch();
        outputBatch.setOrgId(principal.getOrgId());
        outputBatch.setProductId(req.outputProductId());
        outputBatch.setBatchNo("PR-" + System.currentTimeMillis());
        outputBatch.setBatchType("PROCESSING");
        outputBatch.setQuantity(req.outputQuantity());
        outputBatch.setUnitCode("kg");
        outputBatch.setOriginType(sourceBatch.getOriginType());
        outputBatch.setOriginText("加工自批次 " + sourceBatch.getBatchNo() + " (消耗" + req.consumedQuantity() + "kg)");
        outputBatch.setProductionDate(java.time.LocalDate.now());
        outputBatch.setStatus("ACTIVE");
        outputBatch.setCreatedBy(principal.getUserId());
        outputBatch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        outputBatch.setVersion(0L);
        outputBatch.setIsDeleted(0);
        batchMapper.insert(outputBatch);
        return BatchResponse.fromEntity(outputBatch);
    }

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null || !principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "无权访问",
                    "当前操作需要企业操作员角色 (OPERATOR)"
            );
        }
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private boolean isSameCreateSemantics(
            Batch b,
            BatchCreateRequest req,
            String normalizedBatchType,
            String normalizedOriginType,
            String normalizedUnitCode
    ) {
        return Objects.equals(b.getBatchNo(), req.batchNo().trim())
                && Objects.equals(b.getProductId(), req.productId())
                && Objects.equals(b.getBatchType(), normalizedBatchType)
                && b.getQuantity().compareTo(req.quantity()) == 0
                && Objects.equals(b.getUnitCode(), normalizedUnitCode)
                && Objects.equals(b.getOriginType(), normalizedOriginType)
                && Objects.equals(b.getOriginText(), req.originText().trim())
                && Objects.equals(b.getProductionDate(), req.productionDate())
                && Objects.equals(b.getCaptureDate(), req.captureDate())
                && Objects.equals(b.getFreezeDate(), req.freezeDate())
                && Objects.equals(b.getShelfLifeDays(), req.shelfLifeDays());
    }
}
