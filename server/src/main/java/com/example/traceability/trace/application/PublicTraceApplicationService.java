package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchLineageEdge;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.domain.PublicTraceCode;
import com.example.traceability.trace.domain.PublicTraceCodeIdempotency;
import com.example.traceability.trace.domain.PublicTraceCodeStatus;
import com.example.traceability.trace.domain.PublicTraceIdGenerator;
import com.example.traceability.trace.domain.TraceDataMasker;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 公开追溯码与消费者投影应用服务。
 * <p>
 * 负责批次公开追溯码的生成与激活、停用终态管理、当前码查询，以及匿名消费者公开追溯信息安全投影（含祖先谱系聚合）。
 * 严格落实组织隔离（企业路径 SQL 始终显式带 org_id）、排他行级锁（消除 TOCTOU）、
 * 统一幂等记录表 (public_trace_code_idempotency) 绑定（覆盖 action+batch 语义指纹）、
 * 穿透 MySQL REPEATABLE READ 快照盲区的当前锁定读恢复，以及严格白名单字段过滤与机密信息隔离。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class PublicTraceApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PublicTraceApplicationService.class);

    private static final Pattern PUBLIC_TRACE_ID_PATTERN = Pattern.compile("^[A-Z2-7]{26}$");

    private static final String PUBLIC_DISCLOSURE_STATEMENT =
            "本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。";

    private static final String TEMPERATURE_INSUFFICIENT_NOTE =
            "当前切片尚未接入冷链实时温控时序采集流，暂无有效温控监测记录，不构成本项目温控合规依据。";

    private final BatchMapper batchMapper;
    private final BatchRelationMapper batchRelationMapper;
    private final ProductMapper productMapper;
    private final TraceEventMapper traceEventMapper;
    private final PublicTraceCodeMapper publicTraceCodeMapper;
    private final PublicTraceCodeIdempotencyMapper idempotencyMapper;

    public PublicTraceApplicationService(
            BatchMapper batchMapper,
            BatchRelationMapper batchRelationMapper,
            ProductMapper productMapper,
            TraceEventMapper traceEventMapper,
            PublicTraceCodeMapper publicTraceCodeMapper,
            PublicTraceCodeIdempotencyMapper idempotencyMapper
    ) {
        this.batchMapper = batchMapper;
        this.batchRelationMapper = batchRelationMapper;
        this.productMapper = productMapper;
        this.traceEventMapper = traceEventMapper;
        this.publicTraceCodeMapper = publicTraceCodeMapper;
        this.idempotencyMapper = idempotencyMapper;
    }

    /**
     * 激活实体批次的对外公开追溯码（仅限本组织企业操作员 OPERATOR）。
     * <p>
     * 业务规则：
     * 1. 仅允许 ACTIVE 状态的批次首次激活；
     * 2. 一批一码不可变性：同一批次在全系统生命周期内仅生成一个 26 位 Base32 编码；
     * 3. 统一幂等持久绑定：
     *    - 同 key 同批次同动作激活：幂等重放返回原码；
     *    - 任何成功返回（包括不同 key 命中已有 ACTIVE 资源），均在同一事务内将该 key 绑定至统一幂等表；
     *    - 相同 key 复用于不同批次或不同动作（如先激活再停用）：抛出 409 IDEMPOTENCY_KEY_REUSED；
     *    - 并发不同 key 激活同一批次：底层 uk_public_trace_code_batch 保证只生成一条码，当前锁定读穿透快照盲区恢复并持久绑定各自 key；
     * 4. 企业读写与锁定 SQL 始终显式约束 org_id，杜绝越权实体窥探。
     * </p>
     *
     * @param batchId        关联批次内部主键 ID
     * @param idempotencyKey 客户端激活防重幂等键 (16..128 字符)
     * @param principal      当前认证主体
     * @return 激活成功的追溯码白名单响应
     */
    @Transactional
    public PublicTraceCodeResponse activatePublicTraceCode(
            Long batchId,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        String action = "ACTIVATE";
        String requestHash = computeActionHash(action, batchId);

        // 1. 统一组织范围幂等预检
        PublicTraceCodeIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(orgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action)
                    && Objects.equals(existingIdem.getBatchId(), batchId)
                    && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                PublicTraceCode code = publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, orgId);
                if (code != null) {
                    return PublicTraceCodeResponse.fromEntity(code);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "幂等键冲突",
                    "当前幂等键已被用于其他操作或不同批次"
            );
        }

        // 2. 批次行级排他悲观锁（强制带 org_id）
        Batch batch = batchMapper.selectByIdAndOrgIdForUpdate(batchId, orgId);
        if (batch == null) {
            // 租户未命中时，仅查组织 ID 标量判断是 404 还是 403，绝不向内存读取他组织批次实体
            Long ownerOrgId = batchMapper.selectOrgIdByIdIgnoreTenant(batchId);
            if (ownerOrgId == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
            }
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权操作其他组织的批次追溯码"
            );
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        // 3. 检查批次是否已有码（租户隔离查询，依赖外层 batch 行锁与底层唯一索引防重）
        PublicTraceCode existingCode = publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, orgId);
        if (existingCode != null) {
            if (PublicTraceCodeStatus.DISABLED.name().equals(existingCode.getStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION",
                        "追溯码状态不允许操作",
                        "批次公开追溯码已停用，终态禁止重新激活或轮换"
                );
            }
            // 命中已有 ACTIVE 资源：必须在当前事务内持久绑定该 key
            bindIdempotencyKey(orgId, cleanKey, action, batchId, existingCode.getId(), requestHash, nowUtc);
            return PublicTraceCodeResponse.fromEntity(existingCode);
        }

        // 4. 仅 ACTIVE 且 NORMAL 批次允许首次激活
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许当前操作",
                    "批次当前流转或风险状态不允许激活公开追溯码 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")"
            );
        }

        // 5. 生成 26 位 RFC 4648 Base32 public_id 与内部指纹 token_hash
        String publicId = PublicTraceIdGenerator.generatePublicId();
        String tokenHash = PublicTraceIdGenerator.computeTokenHash(publicId);

        PublicTraceCode newCode = new PublicTraceCode();
        newCode.setBatchId(batchId);
        newCode.setOrgId(orgId);
        newCode.setPublicId(publicId);
        newCode.setTokenHash(tokenHash);
        newCode.setStatus(PublicTraceCodeStatus.ACTIVE.name());
        newCode.setActivatedAt(nowUtc);
        newCode.setDisabledAt(null);
        newCode.setVersion(0L);
        newCode.setIsDeleted(0);
        newCode.setCreatedAt(nowUtc);
        newCode.setCreatedBy(principal.getUserId());
        newCode.setUpdatedAt(nowUtc);
        newCode.setUpdatedBy(principal.getUserId());

        PublicTraceCode codeToReturn = newCode;
        try {
            publicTraceCodeMapper.insert(newCode);
        } catch (DuplicateKeyException e) {
            // MySQL REPEATABLE READ 快照盲区当前锁定读恢复 (SELECT ... FOR UPDATE)
            PublicTraceCode dupBatch = publicTraceCodeMapper.selectByBatchIdAndOrgIdForUpdate(batchId, orgId);
            if (dupBatch != null) {
                codeToReturn = dupBatch;
            } else {
                throw e;
            }
        }

        // 6. 持久绑定该幂等键
        bindIdempotencyKey(orgId, cleanKey, action, batchId, codeToReturn.getId(), requestHash, nowUtc);

        return PublicTraceCodeResponse.fromEntity(codeToReturn);
    }

    /**
     * 停用实体批次的对外公开追溯码（仅限本组织企业操作员 OPERATOR）。
     * <p>
     * 业务规则：
     * 1. 停用为终态 (DISABLED)，严禁物理删除，严禁再激活或轮换；
     * 2. 停用后的追溯码在消费者端表现为统一的 404 PUBLIC_TRACE_NOT_FOUND；
     * 3. 统一幂等持久绑定：
     *    - 同 key 同批次停用：返回原停用结果；
     *    - 任何成功返回（包括已处于 DISABLED 终态被新 key 再次调用），均在同一事务内将该 key 绑定至统一幂等表；
     *    - 相同 key 复用于不同批次或激活操作：抛出 409 IDEMPOTENCY_KEY_REUSED；
     * 4. 企业读写与锁定 SQL 始终显式约束 org_id。
     * </p>
     *
     * @param batchId        关联批次内部主键 ID
     * @param idempotencyKey 客户端停用防重幂等键 (16..128 字符)
     * @param principal      当前认证主体
     * @return 停用成功的追溯码白名单响应
     */
    @Transactional
    public PublicTraceCodeResponse disablePublicTraceCode(
            Long batchId,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();
        String action = "DISABLE";
        String requestHash = computeActionHash(action, batchId);

        // 1. 统一组织范围幂等预检
        PublicTraceCodeIdempotency existingIdem = idempotencyMapper.selectByOrgIdAndKey(orgId, cleanKey);
        if (existingIdem != null) {
            if (Objects.equals(existingIdem.getAction(), action)
                    && Objects.equals(existingIdem.getBatchId(), batchId)
                    && Objects.equals(existingIdem.getRequestHash(), requestHash)) {
                PublicTraceCode code = publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, orgId);
                if (code != null) {
                    return PublicTraceCodeResponse.fromEntity(code);
                }
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "幂等键冲突",
                    "当前幂等键已被用于其他操作或不同批次"
            );
        }

        // 2. 批次行级排他锁校验（强制带 org_id）
        Batch batch = batchMapper.selectByIdAndOrgIdForUpdate(batchId, orgId);
        if (batch == null) {
            Long ownerOrgId = batchMapper.selectOrgIdByIdIgnoreTenant(batchId);
            if (ownerOrgId == null) {
                throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
            }
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权操作其他组织的批次追溯码"
            );
        }

        // 3. 锁定当前公开追溯码（带 org_id）
        PublicTraceCode code = publicTraceCodeMapper.selectByBatchIdAndOrgIdForUpdate(batchId, orgId);
        if (code == null) {
            throw new ResourceNotFoundException("该批次尚未激活公开追溯码，无法执行停用操作");
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        // 4. 若已处于 DISABLED 终态：新 key 停用重放依然持久绑定并返回原结果
        if (PublicTraceCodeStatus.DISABLED.name().equals(code.getStatus())) {
            bindIdempotencyKey(orgId, cleanKey, action, batchId, code.getId(), requestHash, nowUtc);
            return PublicTraceCodeResponse.fromEntity(code);
        }

        // 5. 单条 SQL 原子更新为 DISABLED
        int affected = publicTraceCodeMapper.disableTraceCode(
                code.getId(), orgId, nowUtc, nowUtc, principal.getUserId()
        );
        if (affected == 0) {
            PublicTraceCode cur = publicTraceCodeMapper.selectByBatchIdAndOrgIdForUpdate(batchId, orgId);
            if (cur != null && PublicTraceCodeStatus.DISABLED.name().equals(cur.getStatus())) {
                bindIdempotencyKey(orgId, cleanKey, action, batchId, cur.getId(), requestHash, nowUtc);
                return PublicTraceCodeResponse.fromEntity(cur);
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "状态流转冲突",
                    "公开追溯码当前状态已发生并发变更"
            );
        }

        code.setStatus(PublicTraceCodeStatus.DISABLED.name());
        code.setDisabledAt(nowUtc);
        code.setUpdatedAt(nowUtc);
        code.setUpdatedBy(principal.getUserId());

        // 6. 持久绑定该停用幂等键
        bindIdempotencyKey(orgId, cleanKey, action, batchId, code.getId(), requestHash, nowUtc);

        return PublicTraceCodeResponse.fromEntity(code);
    }

    /**
     * 查询批次当前绑定的公开追溯码（企业端，GET /api/v1/batches/{batchId}/public-trace-code）。
     * <p>
     * 读取范围与终端销售台账一致：批次当前责任组织（任意角色）与平台只读角色；其他组织（含已转出批次的历史参与组织）403。
     * 返回任意生命周期状态（含 DISABLED 终态）的码；尚未激活时返回 404 PUBLIC_TRACE_CODE_NOT_FOUND（与批次不存在的
     * RESOURCE_NOT_FOUND 区分）。只读，不绑定幂等键、不产生任何写入。
     * </p>
     *
     * @param batchId   批次内部主键 ID
     * @param principal 当前认证主体
     * @return 公开追溯码企业端白名单响应
     */
    public PublicTraceCodeResponse getCurrentPublicTraceCode(Long batchId, TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        if (!isPlatformScope(principal) && !Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "只有批次当前责任组织可以查看该批次的公开追溯码"
            );
        }
        PublicTraceCode code = publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, batch.getOrgId());
        if (code == null) {
            throw new ResourceNotFoundException(
                    "PUBLIC_TRACE_CODE_NOT_FOUND",
                    "公开追溯码未激活",
                    "该批次尚未激活公开追溯码"
            );
        }
        return PublicTraceCodeResponse.fromEntity(code);
    }

    /**
     * 消费者匿名根据 26 位 Base32 公开标识查询追溯投影。
     * <p>
     * 业务规则：
     * 1. 匿名免认证免 CSRF 访问；只读事务（一致性快照，任何写入都会被数据库拒绝）；
     * 2. 格式严格限定为 ^[A-Z2-7]{26}$；不符合正则统一返回 404 且绝不访问底层持久层；
     * 3. 未知、停用 (DISABLED) 或已删除的码统一返回 404 PUBLIC_TRACE_NOT_FOUND，外部无法探测停用码存在性；
     *    其他码状态（ACTIVE，以及将来的 RECALLED 码状态）照常可查询；
     * 4. 批次即使处于 FROZEN/CLOSED/RECALLED 状态，依然如实返回 200 与独立的 flowStatus / riskStatus；
     * 5. 模拟召回提示只由批次 riskStatus=RECALLED 决定，与公开追溯码自身状态无关；
     * 6. 谱系：只沿 BatchRelation 向上聚合祖先批次（兄弟批次永不进入），谱系边来自已提交批次操作，不伪造事件；
     *    谱系结构不完整时整体拒绝 (PUBLIC_TRACE_LINEAGE_INTEGRITY)，绝不返回截断谱系；
     * 7. 时间线只包含公开事件白名单内的有效 SUBMITTED 事件（排除 CORRECTED 原版本），确定性排序，
     *    event 仅为受控业务标签，严禁拼接 summary / detailsJson 等自由文本；
     * 8. 温度摘要如实返回 INSUFFICIENT_DATA 与诚实说明；SIMULATED 明确标为模拟，DEVICE 严禁暗示接入真实硬件；
     * 9. 固定 5 次集合查询（码、祖先谱系边、批次、产品、事件），与谱系深度和规模无关。
     * </p>
     *
     * @param publicTraceId 消费者公开追溯标识 (26 位 Base32)
     * @return 白名单脱敏投影
     */
    @Transactional(readOnly = true)
    public PublicTraceProjectionResponse getPublicTrace(String publicTraceId) {
        if (publicTraceId == null || !PUBLIC_TRACE_ID_PATTERN.matcher(publicTraceId).matches()) {
            // 输入不合规直接统一返回 404，不访问底层 mapper
            throw notFoundException();
        }

        String cleanPublicId = publicTraceId;
        PublicTraceCode code = publicTraceCodeMapper.selectByPublicId(cleanPublicId);
        if (code == null || PublicTraceCodeStatus.DISABLED.name().equals(code.getStatus()) || Objects.equals(code.getIsDeleted(), 1)) {
            // 未知与停用码对外表现完全一致，统一返回 404 PUBLIC_TRACE_NOT_FOUND
            throw notFoundException();
        }
        Long targetBatchId = code.getBatchId();

        // 1 次递归查询取得全部祖先谱系边；只向上遍历，兄弟批次不会进入
        List<BatchLineageEdge> edges = batchRelationMapper.selectAncestorEdges(targetBatchId);
        Set<Long> nodeIds = new TreeSet<>();
        nodeIds.add(targetBatchId);
        for (BatchLineageEdge edge : edges) {
            if (edge.getParentBatchId() != null) {
                nodeIds.add(edge.getParentBatchId());
            }
            if (edge.getChildBatchId() != null) {
                nodeIds.add(edge.getChildBatchId());
            }
        }

        // 1 次集合查询读取全部谱系节点（仅未删除批次）
        Map<Long, Batch> batches = new HashMap<>();
        for (Batch b : batchMapper.selectByIdsIgnoreTenant(nodeIds)) {
            batches.put(b.getId(), b);
        }
        Batch batch = batches.get(targetBatchId);
        if (batch == null || Objects.equals(batch.getIsDeleted(), 1)) {
            throw notFoundException();
        }

        // 1 次集合查询读取全部节点产品
        Set<Long> productIds = new TreeSet<>();
        for (Batch b : batches.values()) {
            if (b.getProductId() != null) {
                productIds.add(b.getProductId());
            }
        }
        Map<Long, Product> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Product p : productMapper.selectByIds(productIds)) {
                products.put(p.getId(), p);
            }
        }

        // 1 次集合查询读取全部节点的有效公开事件（只取白名单列与白名单事件类型）
        List<TraceEvent> events = traceEventMapper.selectEffectivePublicEventsByBatchIds(
                nodeIds, PublicTraceProjectionAssembler.PUBLIC_EVENT_TYPES.keySet());

        PublicTraceProjectionAssembler.Assembly assembly;
        try {
            assembly = PublicTraceProjectionAssembler.assemble(targetBatchId, edges, batches, products, events);
        } catch (PublicTraceProjectionAssembler.LineageIntegrityException e) {
            log.error("公开追溯谱系完整性校验失败，拒绝返回不完整谱系: publicTraceId={}, reason={}", cleanPublicId, e.getMessage());
            throw new BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PUBLIC_TRACE_LINEAGE_INTEGRITY",
                    "追溯谱系数据不完整",
                    "公开追溯谱系数据完整性校验未通过，暂时无法提供查询，请稍后重试"
            );
        }

        Product product = products.get(batch.getProductId());
        String prodName = product != null ? product.getPublicName() : "冷冻水产品";
        String prodCategory = product != null ? product.getCategory() : "OTHER";
        String prodSpec = product != null ? product.getSpecification() : "规格以包装标称为准";

        PublicTraceProjectionResponse.ProductProjection productProj =
                new PublicTraceProjectionResponse.ProductProjection(prodName, prodCategory, prodSpec);

        PublicTraceProjectionResponse.BatchProjection batchProj =
                new PublicTraceProjectionResponse.BatchProjection(
                        TraceDataMasker.maskBatchNo(batch.getExternalBatchNo()),
                        batch.getOriginType(),
                        TraceDataMasker.maskOriginText(batch.getOriginText()),
                        batch.getProductionDate() != null ? batch.getProductionDate().toString() : null
                );

        PublicTraceProjectionResponse.TemperatureSummaryProjection tempSummary =
                new PublicTraceProjectionResponse.TemperatureSummaryProjection(
                        "INSUFFICIENT_DATA",
                        TEMPERATURE_INSUFFICIENT_NOTE
                );

        String flowStatus = batch.getFlowStatus();
        String riskStatus = batch.getRiskStatus();
        String recallNotice = PublicTraceProjectionAssembler.recallNotice(flowStatus, riskStatus);
        String queriedAt = Instant.now().toString();

        return new PublicTraceProjectionResponse(
                cleanPublicId,
                productProj,
                batchProj,
                assembly.lineage(),
                assembly.timeline(),
                tempSummary,
                flowStatus,
                riskStatus,
                recallNotice,
                queriedAt,
                PUBLIC_DISCLOSURE_STATEMENT
        );
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private void bindIdempotencyKey(
            Long orgId,
            String key,
            String action,
            Long batchId,
            Long codeId,
            String requestHash,
            LocalDateTime nowUtc
    ) {
        PublicTraceCodeIdempotency idem = new PublicTraceCodeIdempotency(
                orgId, key, action, batchId, codeId, requestHash, nowUtc
        );
        try {
            idempotencyMapper.insert(idem);
        } catch (DuplicateKeyException e) {
            // 并发同 key 竞争恢复：当前锁定读 SELECT ... FOR UPDATE
            PublicTraceCodeIdempotency cur = idempotencyMapper.selectByOrgIdAndKeyForUpdate(orgId, key);
            if (cur == null
                    || !Objects.equals(cur.getAction(), action)
                    || !Objects.equals(cur.getBatchId(), batchId)
                    || !Objects.equals(cur.getRequestHash(), requestHash)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "幂等键冲突",
                        "当前幂等键已被用于其他操作或不同批次"
                );
            }
        }
    }

    private String computeActionHash(String action, Long batchId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((action + ":" + batchId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null || !principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "操作权限不足",
                    "仅企业操作员 (OPERATOR) 允许执行当前操作"
            );
        }
    }

    private String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "Idempotency-Key 请求头必填且长度必须在 16 到 128 个字符之间"
            );
        }
        String clean = idempotencyKey.trim();
        if (clean.length() < 16 || clean.length() > 128) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "Idempotency-Key 请求头长度必须在 16 到 128 个字符之间，当前长度: " + clean.length()
            );
        }
        return clean;
    }

    private BusinessException notFoundException() {
        return new BusinessException(
                HttpStatus.NOT_FOUND,
                "PUBLIC_TRACE_NOT_FOUND",
                "受控资源未找到",
                "未找到对应的公开追溯信息或追溯码已失效"
        );
    }
}
