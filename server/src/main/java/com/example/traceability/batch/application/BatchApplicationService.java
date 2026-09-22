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
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 追溯批次应用服务。
 * <p>
 * 负责批次草稿创建（服务端生成全局唯一 traceBatchNo，初始化 DRAFT+NORMAL）、组织隔离分页查询、详情查看、
 * 基于单条 SQL 条件约束的草稿增量更新以及 DRAFT+NORMAL -> ACTIVE+NORMAL 提交流转。
 * 严格防范跨组织越权与并发冲突，支持客户端幂等提交（同 key 语义冲突报 IDEMPOTENCY_KEY_REUSED）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchApplicationService {

    private final BatchMapper batchMapper;
    private final ProductMapper productMapper;
    private final TraceBatchNoGenerator traceBatchNoGenerator;
    private final TraceEventApplicationService traceEventApplicationService;
    private final BatchOperationItemMapper batchOperationItemMapper;

    public BatchApplicationService(
            BatchMapper batchMapper,
            ProductMapper productMapper,
            TraceBatchNoGenerator traceBatchNoGenerator,
            TraceEventApplicationService traceEventApplicationService,
            BatchOperationItemMapper batchOperationItemMapper
    ) {
        this.batchMapper = batchMapper;
        this.batchOperationItemMapper = batchOperationItemMapper;
        this.productMapper = productMapper;
        this.traceBatchNoGenerator = traceBatchNoGenerator;
        this.traceEventApplicationService = traceEventApplicationService;
    }

    /**
     * 分页查询批次列表。
     * <p>
     * PLATFORM scope 角色可查询全平台批次；其余企业认证用户必须在 Mapper 层强制限定为 {@code principal.getOrgId()}。
     * 支持 traceBatchNo、externalBatchNo、flowStatus、riskStatus 过滤。
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

        String normalizedFlowStatus = null;
        if (criteria.flowStatus() != null && !criteria.flowStatus().isBlank()) {
            String trimmed = criteria.flowStatus().trim();
            if (!BatchFlowStatus.isValid(trimmed)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的批次流转状态: " + criteria.flowStatus());
            }
            normalizedFlowStatus = BatchFlowStatus.fromCode(trimmed).name();
        }

        String normalizedRiskStatus = null;
        if (criteria.riskStatus() != null && !criteria.riskStatus().isBlank()) {
            String trimmed = criteria.riskStatus().trim();
            if (!BatchRiskStatus.isValid(trimmed)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的批次风险状态: " + criteria.riskStatus());
            }
            normalizedRiskStatus = BatchRiskStatus.fromCode(trimmed).name();
        }

        String cleanTraceBatchNo = (criteria.traceBatchNo() != null && !criteria.traceBatchNo().isBlank()) ? criteria.traceBatchNo().trim() : null;
        String cleanExternalBatchNo = (criteria.externalBatchNo() != null && !criteria.externalBatchNo().isBlank()) ? criteria.externalBatchNo().trim() : null;

        boolean isPlatform = isPlatformScope(principal);
        Long targetOrgId = isPlatform ? null : principal.getOrgId();

        long totalCount = batchMapper.countBatches(targetOrgId, cleanTraceBatchNo, cleanExternalBatchNo, normalizedFlowStatus, normalizedRiskStatus);
        long offset = (long) (criteria.page() - 1) * criteria.size();
        List<Batch> records = batchMapper.selectBatchesPage(targetOrgId, cleanTraceBatchNo, cleanExternalBatchNo, normalizedFlowStatus, normalizedRiskStatus, offset, criteria.size());

        Map<Long, BigDecimal> consumed = consumedQuantities(records.stream().map(Batch::getId).toList());
        List<BatchResponse> dtos = records.stream()
                .map(b -> BatchResponse.fromEntity(b, remainingQuantity(b, consumed.get(b.getId()))))
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

        Map<Long, BigDecimal> consumed = consumedQuantities(List.of(batch.getId()));
        return BatchResponse.fromEntity(batch, remainingQuantity(batch, consumed.get(batch.getId())));
    }

    /**
     * 创建来源批次草稿。
     * <p>
     * 企业端公开创建接口只建立来源批次：仅 {@code orgType=SOURCE} 组织的 OPERATOR 可以调用，
     * batchType 由服务端固定为 SOURCE，客户端不能选择或指定任何其他批次类型；
     * PROCESSING 等输出批次只能在未来由 BatchOperation 产生。系统管理员按 RBAC 原则默认不能代写企业业务。
     * 强制携带 Idempotency-Key；同<b>创建组织</b>同 key 且请求语义相同时返回原批次；
     * 语义不同时返回 409 IDEMPOTENCY_KEY_REUSED。
     * 服务端生成全局唯一 traceBatchNo，初始化 flowStatus=DRAFT, riskStatus=NORMAL，
     * 并把 creationOrgId 与 orgId 同时置为当前主体组织。请求体夹带任何未声明字段时返回 400。
     * </p>
     * <p>
     * 幂等作用域使用不可变的 creationOrgId 而非会随交接转移的 orgId，因此：
     * 批次转出后原创建方重放原始创建请求仍命中原批次而不会重复建批；
     * 接收方即便持有相同的 creation_idempotency_key 也不会与交接产生唯一键冲突。
     * 重放返回的是该批次的当前视图，此路径仅对持有原始幂等键的创建方开放，
     * 不改变 GET /batches/{id} 等常规查询各自独立的组织权限校验。
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
        checkSourceOrganization(principal, "只有来源组织 (SOURCE) 可以创建来源批次");

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

        // 2. 拒绝客户端夹带的服务端字段或任何未声明字段（batchType、traceBatchNo、orgId、flowStatus 等）
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "来源批次创建请求不接受以下字段（由服务端决定或未在契约中声明）: " + String.join(", ", req.unknownFields().keySet())
            );
        }

        // 3. 枚举字段与基础参数业务校验与规范化；批次类型由服务端固定为 SOURCE
        String normalizedBatchType = BatchType.SOURCE.name();

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

        String normalizedExternalBatchNo = normalizeExternalBatchNo(req.externalBatchNo());
        Long orgId = principal.getOrgId();

        // 3. 幂等预检：检查同"创建组织"下是否已存在该幂等键
        // 作用域必须是不可变的 creation_org_id：批次在 Transfer ACCEPTED 后 org_id 会变为接收方，
        // 若仍以 org_id 为幂等域，原创建方重放原始创建请求将查不到原批次而重复建批。
        // 幂等语义重放必须在可变产品状态校验之前执行，即便产品后续变为 INACTIVE，相同载荷仍返回原批次
        Batch existingByIdempotency = batchMapper.selectByCreationOrgIdAndIdempotencyKey(orgId, cleanIdempotencyKey);
        if (existingByIdempotency != null) {
            if (isSameCreateSemantics(existingByIdempotency, req, normalizedExternalBatchNo, normalizedBatchType, normalizedOriginType, normalizedUnitCode)) {
                return BatchResponse.fromEntity(existingByIdempotency);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
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

        // 5. 服务端生成全局唯一 traceBatchNo
        String traceBatchNo = traceBatchNoGenerator.generate();

        // 6. 构造新批次实体并持久化
        Batch batch = new Batch();
        // 新建时创建组织与当前责任组织相同；此后 creationOrgId 永不改变，orgId 随 Transfer ACCEPTED 转移
        batch.setOrgId(orgId);
        batch.setCreationOrgId(orgId);
        batch.setProductId(req.productId());
        batch.setTraceBatchNo(traceBatchNo);
        batch.setExternalBatchNo(normalizedExternalBatchNo);
        batch.setBatchType(normalizedBatchType);
        batch.setQuantity(req.quantity());
        batch.setUnitCode(normalizedUnitCode);
        batch.setOriginType(normalizedOriginType);
        batch.setOriginText(req.originText().trim());
        batch.setProductionDate(req.productionDate());
        batch.setCaptureDate(req.captureDate());
        batch.setFreezeDate(req.freezeDate());
        batch.setShelfLifeDays(req.shelfLifeDays());
        batch.setFlowStatus(BatchFlowStatus.DRAFT.name());
        batch.setRiskStatus(BatchRiskStatus.NORMAL.name());
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
            Batch dupIdempotency = batchMapper.selectByCreationOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
            if (dupIdempotency != null) {
                if (isSameCreateSemantics(dupIdempotency, req, normalizedExternalBatchNo, normalizedBatchType, normalizedOriginType, normalizedUnitCode)) {
                    return BatchResponse.fromEntity(dupIdempotency);
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "幂等提交冲突",
                        "并发检测到相同幂等键，但请求载荷与已落库数据不一致"
                );
            }

            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "DATA_CONFLICT",
                    "数据冲突",
                    "唯一键冲突，请重试"
            );
        }

        return BatchResponse.fromEntity(batch);
    }

    /**
     * 增量更新批次草稿。
     * <p>
     * 仅限 OPERATOR 角色更新本组织处于 DRAFT+NORMAL 状态的批次。
     * 单条 SQL 同时约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version，
     * 行数为 0 时精准区分跨组织、非草稿正常状态与版本冲突。
     * 绝不允许修改 traceBatchNo 与 creationOrgId。支持可选更新 externalBatchNo。
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

        rejectOperationOwnedBatch(existing);

        if (!BatchFlowStatus.DRAFT.name().equals(existing.getFlowStatus()) || !BatchRiskStatus.NORMAL.name().equals(existing.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅处于草稿正常状态 (DRAFT+NORMAL) 的批次允许修改，当前状态为: flow=" + existing.getFlowStatus() + ", risk=" + existing.getRiskStatus()
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

        // 2. 准备待更新字段（traceBatchNo, productId, batchType, originType, unitCode 在此切片不可变）
        if (req.externalBatchNo() != null) {
            existing.setExternalBatchNo(normalizeExternalBatchNo(req.externalBatchNo()));
        }

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

        // 3. 单条 SQL 原子条件更新 (约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version)
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
            if (!BatchFlowStatus.DRAFT.name().equals(latest.getFlowStatus()) || !BatchRiskStatus.NORMAL.name().equals(latest.getRiskStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION",
                        "非法状态流转",
                        "批次状态已变更，当前状态为: flow=" + latest.getFlowStatus() + ", risk=" + latest.getRiskStatus()
                );
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
     * 提交激活来源批次草稿（DRAFT+NORMAL -> ACTIVE+NORMAL），并在同一事务内自动生成唯一 SOURCE 追溯事件。
     * <p>
     * 仅限来源组织 (SOURCE) 的 OPERATOR 操作；批次必须为 SOURCE 类型、当前责任组织仍是创建它的来源组织、
     * 来源字段完整且关联产品处于 ACTIVE 状态。
     * 单条 SQL 同时约束 id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version，原子递增版本号；
     * 并发提交时只有一个事务能命中该条件更新，其余返回 409。
     * SOURCE 事件写入失败会抛出异常并使批次激活随事务整体回滚。
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
        checkSourceOrganization(principal, "只有来源组织 (SOURCE) 可以提交激活来源批次");

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

        rejectOperationOwnedBatch(existing);

        // 当前责任组织必须仍是创建该来源批次的组织
        if (!Objects.equals(existing.getCreationOrgId(), existing.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "只有创建该来源批次且仍为当前责任组织的来源组织可以提交激活"
            );
        }

        if (!BatchFlowStatus.DRAFT.name().equals(existing.getFlowStatus()) || !BatchRiskStatus.NORMAL.name().equals(existing.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "仅处于草稿正常状态 (DRAFT+NORMAL) 的批次允许提交激活，当前状态为: flow=" + existing.getFlowStatus() + ", risk=" + existing.getRiskStatus()
            );
        }

        // 普通提交只激活来源批次；输出批次未来只能随 BatchOperation 提交激活
        if (!BatchType.SOURCE.name().equals(existing.getBatchType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_TYPE_NOT_SUBMITTABLE",
                    "批次类型不允许普通提交",
                    "普通提交接口仅允许激活来源批次 (SOURCE)，当前批次类型为: " + existing.getBatchType()
            );
        }

        if (!OriginType.isValid(existing.getOriginType()) || existing.getOriginText() == null || existing.getOriginText().isBlank()) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SOURCE_FIELDS_INCOMPLETE",
                    "来源信息不完整",
                    "来源批次激活前必须具备合法的来源类型与产地来源描述"
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

        // 3. 单条 SQL 原子状态流转与版本递增 (id + org_id + flow_status='DRAFT' + risk_status='NORMAL' + version)
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        int affectedRows = batchMapper.submitDraftBatch(batchId, principal.getOrgId(), req.version(), nowUtc, principal.getUserId());
        if (affectedRows == 0) {
            Batch latest = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
            if (latest == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
            }
            if (!Objects.equals(latest.getOrgId(), principal.getOrgId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权提交其他组织的批次");
            }
            if (!BatchFlowStatus.DRAFT.name().equals(latest.getFlowStatus()) || !BatchRiskStatus.NORMAL.name().equals(latest.getRiskStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION",
                        "非法状态流转",
                        "批次状态已变更，当前状态为: flow=" + latest.getFlowStatus() + ", risk=" + latest.getRiskStatus()
                );
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "并发修改导致版本冲突，当前版本为 " + latest.getVersion() + "，请刷新后重试"
            );
        }

        // 4. 同一事务内自动投影唯一 SOURCE 追溯事件；写入失败抛出异常，批次激活随之回滚
        Batch submitted = batchMapper.selectByIdAndOrgId(batchId, principal.getOrgId());
        traceEventApplicationService.appendSourceEvent(submitted, principal.getUserId(), nowUtc);
        return BatchResponse.fromEntity(submitted);
    }

    /**
     * BatchOperation 产出的 OUTPUT 草稿只能随操作提交原子激活，不得通过普通批次接口修改或提交（契约不变量 7）。
     */
    private void rejectOperationOwnedBatch(Batch batch) {
        if (batch.getProducedByOperationId() != null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BATCH_OWNED_BY_OPERATION",
                    "批次由批次操作管理",
                    "该批次由批次操作产出，只能随批次操作提交激活，不能通过普通批次接口修改或提交"
            );
        }
    }

    /**
     * 批量读取各批次在已提交批次操作中的累计 INPUT 消耗量。
     */
    private Map<Long, BigDecimal> consumedQuantities(List<Long> batchIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (batchIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : batchOperationItemMapper.sumSubmittedInputQuantityByBatchIds(batchIds)) {
            Object id = row.get("batchId");
            Object total = row.get("total");
            if (id instanceof Number n && total != null) {
                result.put(n.longValue(), total instanceof BigDecimal bd ? bd : new BigDecimal(total.toString()));
            }
        }
        return result;
    }

    /**
     * 派生剩余量 = 声明数量 - 已提交批次操作 INPUT 消耗量（Sale 与处置于后续 Slice 加入）；CLOSED 批次剩余量为 0。
     */
    private static BigDecimal remainingQuantity(Batch batch, BigDecimal consumed) {
        if (BatchFlowStatus.CLOSED.name().equals(batch.getFlowStatus())) {
            return BigDecimal.ZERO;
        }
        if (batch.getQuantity() == null) {
            return null;
        }
        BigDecimal remaining = batch.getQuantity().subtract(consumed != null ? consumed : BigDecimal.ZERO);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private void checkSourceOrganization(TraceSecurityPrincipal principal, String detail) {
        if (principal == null || !"SOURCE".equals(principal.getOrgType())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_TYPE_NOT_ALLOWED",
                    "组织类型不允许当前操作",
                    detail
            );
        }
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

    private String normalizeExternalBatchNo(String rawExternal) {
        if (rawExternal == null || rawExternal.isBlank()) {
            return null;
        }
        return rawExternal.trim();
    }

    private boolean isSameCreateSemantics(
            Batch b,
            BatchCreateRequest req,
            String normalizedExternal,
            String normalizedBatchType,
            String normalizedOriginType,
            String normalizedUnitCode
    ) {
        // batchType 恒为服务端决定的 SOURCE；历史非 SOURCE 批次即便同 key 也视为语义不同
        return Objects.equals(b.getExternalBatchNo(), normalizedExternal)
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
