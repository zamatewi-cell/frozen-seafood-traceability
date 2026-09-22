package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchRelation;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.domain.PublicTraceCode;
import com.example.traceability.trace.domain.PublicTraceCodeBatch;
import com.example.traceability.trace.domain.PublicTraceCodeIdempotency;
import com.example.traceability.trace.domain.PublicTraceCodeStatus;
import com.example.traceability.trace.domain.PublicTraceIdGenerator;
import com.example.traceability.trace.domain.TraceDataMasker;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import com.example.traceability.trace.mapper.PublicTraceCodeBatchMapper;
import com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 公开追溯码与消费者投影应用服务。
 * <p>
 * 负责批次公开追溯码的生成与激活、停用终态管理，以及匿名消费者公开追溯信息安全投影。
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

    private static final Pattern PUBLIC_TRACE_ID_PATTERN = Pattern.compile("^[A-Z2-7]{26}$");

    private static final String SIMULATED_RECALL_NOTICE =
            "此批次海产品已启动系统模拟召回演练，流通环节已暂停，请联系销售商或质量管理部门处理（本提示为系统教学演练模拟信息）。";

    private static final String PUBLIC_DISCLOSURE_STATEMENT =
            "本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。";

    private static final String TEMPERATURE_INSUFFICIENT_NOTE =
            "当前切片尚未接入冷链实时温控时序采集流，暂无有效温控监测记录，不构成本项目温控合规依据。";

    private final BatchMapper batchMapper;
    private final BatchRelationMapper batchRelationMapper;
    private final ProductMapper productMapper;
    private final TraceEventMapper traceEventMapper;
    private final PublicTraceCodeMapper publicTraceCodeMapper;
    private final PublicTraceCodeBatchMapper publicTraceCodeBatchMapper;
    private final PublicTraceCodeIdempotencyMapper idempotencyMapper;
    private final com.example.traceability.trace.mapper.TransferMapper transferMapper;
    private final com.example.traceability.identity.mapper.OrganizationMapper organizationMapper;

    public PublicTraceApplicationService(
            BatchMapper batchMapper,
            BatchRelationMapper batchRelationMapper,
            ProductMapper productMapper,
            TraceEventMapper traceEventMapper,
            PublicTraceCodeMapper publicTraceCodeMapper,
            PublicTraceCodeBatchMapper publicTraceCodeBatchMapper,
            PublicTraceCodeIdempotencyMapper idempotencyMapper,
            com.example.traceability.trace.mapper.TransferMapper transferMapper,
            com.example.traceability.identity.mapper.OrganizationMapper organizationMapper
    ) {
        this.batchMapper = batchMapper;
        this.batchRelationMapper = batchRelationMapper;
        this.productMapper = productMapper;
        this.traceEventMapper = traceEventMapper;
        this.publicTraceCodeMapper = publicTraceCodeMapper;
        this.publicTraceCodeBatchMapper = publicTraceCodeBatchMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.transferMapper = transferMapper;
        this.organizationMapper = organizationMapper;
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

        // 4. 仅 ACTIVE 批次允许首次激活
        if (!BatchStatus.ACTIVE.name().equals(batch.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许当前操作",
                    "批次当前状态为 " + batch.getStatus() + "，仅 ACTIVE 状态批次允许激活公开追溯码"
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
     * 终端下单时生成公开追溯码（无物理批次绑定）。
     * <p>
     * 业务规则：
     * 1. 终端（超市/电商等最终售卖方）创建面向消费者的订单时调用入口；
     * 2. 生成的码初始不绑定任何批次，后续随货物交付逐步聚合多个批次；
     * 3. 记录 source_order_type / source_order_id 溯源到生成它的终端订单；
     * 4. 生成 26 位 Base32 公开标识与内部指纹，ACTIVE 且零批次。
     * </p>
     *
     * @param orgId        所属企业组织 ID（终端售卖方）
     * @param sourceType   源代码类型（如 SALES）
     * @param sourceOrderId 生成该码的终端订单 ID
     * @param userId       操作人用户 ID
     * @return 生成的公开追溯码响应
     */
    @Transactional
    public PublicTraceCodeResponse createPublicTraceCodeForSource(
            Long orgId, String sourceType, Long sourceOrderId, Long userId) {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(sourceOrderId, "sourceOrderId");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        String publicId = PublicTraceIdGenerator.generatePublicId();
        String tokenHash = PublicTraceIdGenerator.computeTokenHash(publicId);

        PublicTraceCode newCode = new PublicTraceCode();
        newCode.setBatchId(null);
        newCode.setSourceOrderId(sourceOrderId);
        newCode.setSourceOrderType(sourceType == null ? "SALES" : sourceType);
        newCode.setOrgId(orgId);
        newCode.setPublicId(publicId);
        newCode.setTokenHash(tokenHash);
        newCode.setStatus(PublicTraceCodeStatus.ACTIVE.name());
        newCode.setActivatedAt(nowUtc);
        newCode.setDisabledAt(null);
        newCode.setVersion(0L);
        newCode.setIsDeleted(0);
        newCode.setCreatedAt(nowUtc);
        newCode.setCreatedBy(userId);
        newCode.setUpdatedAt(nowUtc);
        newCode.setUpdatedBy(userId);
        try {
            publicTraceCodeMapper.insert(newCode);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PUBLIC_TRACE_CODE_GENERATION_FAILED",
                    "公开码生成冲突",
                    "终端订单生成公开追溯码时发生唯一性冲突，请重试"
            );
        }
        return PublicTraceCodeResponse.fromEntity(newCode);
    }

    /**
     * 将物理批次绑定到某个公开追溯码（一码可聚合多批）。
     * <p>
     * 业务规则：
     * 1. 仅允许本组织 OPERATOR 操作员执行；
     * 2. 公开码必须存在且处于 ACTIVE；
     * 3. 目标批次必须存在且未逻辑删除；
     * 4. 同一公开码与同一批次幂等绑定（重复绑定视为成功重放）；
     * 5. 绑定后消费者即可在该码下看到该批次的完整溯源履历。
     * </p>
     *
     * @param publicId 26 位 Base32 公开追溯标识
     * @param batchId  待聚合的物理批次 ID
     * @param bindRole 绑定环节角色（可空）
     * @param principal 当前认证主体
     * @return 公开码响应
     */
    @Transactional
    public PublicTraceCodeResponse bindBatchToPublicCode(
            String publicId, Long batchId, String bindRole, TraceSecurityPrincipal principal) {
        checkOperatorRole(principal);
        if (publicId == null || !PUBLIC_TRACE_ID_PATTERN.matcher(publicId).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "参数校验失败", "publicId 必须是 26 位 Base32 公开追溯标识");
        }
        if (batchId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "参数校验失败", "batchId 不能为空");
        }
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        PublicTraceCode code = publicTraceCodeMapper.selectByPublicIdForUpdate(publicId);
        if (code == null || Objects.equals(code.getIsDeleted(), 1)) {
            throw new ResourceNotFoundException("未找到公开追溯码或该码已失效");
        }
        if (!PublicTraceCodeStatus.ACTIVE.name().equals(code.getStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE_TRANSITION",
                    "追溯码状态不允许操作", "仅 ACTIVE 状态的公开追溯码允许聚合批次");
        }

        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null || Objects.equals(batch.getIsDeleted(), 1)) {
            throw new ResourceNotFoundException("未找到批次 ID " + batchId);
        }

        PublicTraceCodeBatch link = new PublicTraceCodeBatch();
        link.setTraceCodeId(code.getId());
        link.setBatchId(batchId);
        link.setBatchOrgId(batch.getOrgId());
        link.setBindRole(bindRole);
        link.setBoundAt(nowUtc);
        link.setBoundBy(principal.getUserId());
        link.setCreatedAt(nowUtc);
        try {
            publicTraceCodeBatchMapper.insert(link);
        } catch (DuplicateKeyException e) {
            // 已绑定：幂等重放成功
        }
        return PublicTraceCodeResponse.fromEntity(code);
    }

    /**
     * 消费者匿名根据 26 位 Base32 公开标识查询追溯投影。
     * <p>
     * 业务规则：
     * 1. 匿名免认证免 CSRF 访问；
     * 2. 格式严格限定为 ^[A-Z2-7]{26}$；不符合正则统一返回 404 且绝不访问底层持久层；
     * 3. 未知与停用码统一返回 404 PUBLIC_TRACE_NOT_FOUND，外部无法探测停用码存在性；
     * 4. 已激活码绑定的批次即使处于 FROZEN/CLOSED/RECALLED 状态，依然如实返回 200 与对应的 batchStatus；
     * 5. 若批次为 RECALLED，返回显著的模拟召回声明 notice；
     * 6. 严格白名单投影：时间线仅包含有效 SUBMITTED 事件（排除 CORRECTED 原版本），按业务时间与登记时间确定性排序；
     *    公开 timeline 中的 event 仅为受控业务标签，严禁拼接 summary 等自由文本；
     * 7. 温度摘要如实返回 INSUFFICIENT_DATA 与诚实说明；SIMULATED 明确标为模拟，DEVICE 严禁暗示接入真实硬件。
     * </p>
     *
     * @param publicTraceId 消费者公开追溯标识 (26 位 Base32)
     * @return 白名单脱敏投影
     */
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

        // 聚合该码下所有绑定的物理批次（一码多批）；无绑定次批次时回退到旧 batch_id
        List<Long> batchIds = publicTraceCodeBatchMapper.selectBatchIdsByTraceCodeId(code.getId());
        if (batchIds.isEmpty() && code.getBatchId() != null) {
            batchIds = List.of(code.getBatchId());
        }
        if (batchIds.isEmpty()) {
            // 终端下单刚生成、尚未聚合任何批次的码：返回空段，产品信息回退为通用占位
            return new PublicTraceProjectionResponse(
                    cleanPublicId,
                    new PublicTraceProjectionResponse.ProductProjection(
                            "冷冻水产品", "OTHER", "规格以到货包装标称为准"),
                    null,
                    List.of(),
                    List.of(),
                    null,
                    new PublicTraceProjectionResponse.TemperatureSummaryProjection(
                            "INSUFFICIENT_DATA", TEMPERATURE_INSUFFICIENT_NOTE),
                    UnifiedBatchStatus.EMPTY,
                    null,
                    Instant.now().toString(),
                    PUBLIC_DISCLOSURE_STATEMENT
            );
        }

        List<Long> orderedBatchIds = new ArrayList<>(new LinkedHashSet<>(batchIds));

        // 兼容单批主段：使用首段作为 product 与 batch/timeline 的来源
        Batch primary = batchMapper.selectByIdIgnoreTenant(orderedBatchIds.get(0));
        Batch first = primary != null ? primary : loadFirstExisting(orderedBatchIds);
        if (first == null) {
            throw notFoundException();
        }

        Product product = productMapper.selectById(first.getProductId());
        String prodName = product != null ? product.getPublicName() : "冷冻水产品";
        String prodCategory = product != null ? product.getCategory() : "OTHER";
        String prodSpec = product != null ? product.getSpecification() : "规格以包装标称为准";
        PublicTraceProjectionResponse.ProductProjection productProj =
                new PublicTraceProjectionResponse.ProductProjection(prodName, prodCategory, prodSpec);

        List<PublicTraceProjectionResponse.BatchSegment> segments = new ArrayList<>();
        boolean anyRecalled = false;
        for (Long batchId : orderedBatchIds) {
            Batch b = batchMapper.selectByIdIgnoreTenant(batchId);
            if (b == null || Objects.equals(b.getIsDeleted(), 1)) {
                continue;
            }
            if (BatchStatus.RECALLED.name().equals(b.getStatus())) {
                anyRecalled = true;
            }
            List<PublicTraceProjectionResponse.TimelineItem> segmentTimeline =
                    traceEventMapper.selectEffectiveEventsByBatchId(batchId).stream()
                            .map(this::toTimelineItem)
                            .toList();
            segments.add(new PublicTraceProjectionResponse.BatchSegment(
                    TraceDataMasker.maskBatchNo(b.getBatchNo()),
                    b.getOriginType(),
                    TraceDataMasker.maskOriginText(b.getOriginText()),
                    b.getProductionDate() != null ? b.getProductionDate().toString() : null,
                    segmentTimeline
            ));
        }
        if (segments.isEmpty()) {
            throw notFoundException();
        }

        Batch statusRef = null;
        for (Long batchId : orderedBatchIds) {
            Batch b = batchMapper.selectByIdIgnoreTenant(batchId);
            if (b != null && !Objects.equals(b.getIsDeleted(), 1)) {
                statusRef = b;
                break;
            }
        }

        PublicTraceProjectionResponse.BatchProjection batchProj =
                new PublicTraceProjectionResponse.BatchProjection(
                        TraceDataMasker.maskBatchNo(statusRef.getBatchNo()),
                        statusRef.getOriginType(),
                        TraceDataMasker.maskOriginText(statusRef.getOriginText()),
                        statusRef.getProductionDate() != null ? statusRef.getProductionDate().toString() : null
                );

        List<PublicTraceProjectionResponse.TimelineItem> timeline =
                segments.stream()
                        .flatMap(s -> s.timeline().stream())
                        .sorted(java.util.Comparator.comparing(PublicTraceProjectionResponse.TimelineItem::occurredAt))
                        .toList();

        PublicTraceProjectionResponse.TemperatureSummaryProjection tempSummary =
                new PublicTraceProjectionResponse.TemperatureSummaryProjection(
                        "INSUFFICIENT_DATA", TEMPERATURE_INSUFFICIENT_NOTE);

        String batchStatus = statusRef.getStatus();
        String recallNotice = anyRecalled ? SIMULATED_RECALL_NOTICE : null;

        String orderNo = null;
        if (code.getSourceOrderId() != null) {
            orderNo = "PURCHASE".equals(code.getSourceOrderType())
                    ? ("PO#" + code.getSourceOrderId())
                    : ("SO#" + code.getSourceOrderId());
        }
        PublicTraceProjectionResponse.TraceTree tree =
                buildTraceTree(orderNo, orderedBatchIds);

        String queriedAt = Instant.now().toString();

        return new PublicTraceProjectionResponse(
                cleanPublicId,
                productProj,
                batchProj,
                timeline,
                segments,
                tree,
                tempSummary,
                batchStatus,
                recallNotice,
                queriedAt,
                PUBLIC_DISCLOSURE_STATEMENT
        );
    }

    /**
     * 递归构建溯源树(从终端零售向下展开到捕捞源头)。
     * <p>
     * 以码下绑定的批次为根节点（终端环节），每个节点向下通过 batch_relation 谱系边查找上游来源批次。
     * 同一批次可在多个分支重复出现（不去重），分支无上限。
     * 用深度限制(≤20)防止数据环路导致无限递归。
     * 环节 stage 由批次 batchType 映射：SOURCE→捕捞, PROCESSING→加工, DISTRIBUTION→批发, SALE→终端零售。
     * </p>
     */
    private PublicTraceProjectionResponse.TraceTree buildTraceTree(
            String orderNo, List<Long> rootBatchIds) {
        List<PublicTraceProjectionResponse.TraceTreeNode> nodes = new ArrayList<>();
        for (Long batchId : rootBatchIds) {
            PublicTraceProjectionResponse.TraceTreeNode node = buildNode(batchId, 0);
            if (node != null) {
                nodes.add(node);
            }
        }
        return new PublicTraceProjectionResponse.TraceTree(orderNo, nodes);
    }

    private static final int MAX_TREE_DEPTH = 20;

    private PublicTraceProjectionResponse.TraceTreeNode buildNode(
            Long batchId, int depth) {
        if (batchId == null || depth > MAX_TREE_DEPTH) {
            return null;
        }
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null || Objects.equals(batch.getIsDeleted(), 1)) {
            return null;
        }
        com.example.traceability.identity.domain.Organization org = organizationMapper.selectById(batch.getOrgId());
        String orgName = org != null ? org.getName() : "未知组织";

        List<PublicTraceProjectionResponse.TimelineItem> timeline =
                traceEventMapper.selectEffectiveEventsByBatchId(batchId).stream()
                        .map(this::toTimelineItem)
                        .toList();

        PublicTraceProjectionResponse.BatchProjection batchProj =
                new PublicTraceProjectionResponse.BatchProjection(
                        TraceDataMasker.maskBatchNo(batch.getBatchNo()),
                        batch.getOriginType(),
                        TraceDataMasker.maskOriginText(batch.getOriginText()),
                        batch.getProductionDate() != null ? batch.getProductionDate().toString() : null
                );

        String stage = mapBatchTypeToStage(batch.getBatchType());

        List<PublicTraceProjectionResponse.TraceTreeNode> children = new ArrayList<>();
        List<BatchRelation> upstreamRelations = batchRelationMapper.selectByChildBatchId(batchId);
        for (BatchRelation r : upstreamRelations) {
            if (r.getParentBatchId() != null) {
                PublicTraceProjectionResponse.TraceTreeNode child = buildNode(r.getParentBatchId(), depth + 1);
                if (child != null) {
                    children.add(child);
                }
            }
        }

        return new PublicTraceProjectionResponse.TraceTreeNode(
                stage,
                orgName,
                batch.getQuantity() != null ? batch.getQuantity().toPlainString() : null,
                batchProj,
                timeline,
                children
        );
    }

    private String mapBatchTypeToStage(String batchType) {
        if (batchType == null) return "UNKNOWN";
        return switch (batchType) {
            case "SOURCE" -> "SOURCE";
            case "PROCESSING" -> "PROCESSING";
            case "DISTRIBUTION" -> "DISTRIBUTION";
            case "SALE" -> "RETAIL";
            default -> "UNKNOWN";
        };
    }

    private Batch loadFirstExisting(List<Long> orderedBatchIds) {
        for (Long id : orderedBatchIds) {
            Batch b = batchMapper.selectByIdIgnoreTenant(id);
            if (b != null && !Objects.equals(b.getIsDeleted(), 1)) {
                return b;
            }
        }
        return null;
    }

    /** 内部枚举：空码（终端下单已生成、尚未聚合批次）的占位流转状态。 */
    private static final class UnifiedBatchStatus {
        static final String EMPTY = "EMPTY";
    }

    /**
     * 将追溯事件转换为公开时间线节点。
     * <p>
     * 关键安全要求：event 仅输出受控类型标准标签，严禁拼接 summary 等自由文本字段。
     * </p>
     */
    private PublicTraceProjectionResponse.TimelineItem toTimelineItem(TraceEvent event) {
        String eventLabel = resolveEventLabel(event.getEventType());
        String occurredAt = event.getOccurredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String sourceLabel = resolveDataSourceLabel(event.getDataSource());
        return new PublicTraceProjectionResponse.TimelineItem(eventLabel, occurredAt, sourceLabel);
    }

    private String resolveEventLabel(String eventType) {
        if (eventType == null) {
            return "追溯节点";
        }
        return switch (eventType) {
            case "SOURCE" -> "原料采收/出塘";
            case "PURCHASE" -> "原料采购入库";
            case "PROCESS" -> "粗加工/精加工";
            case "FREEZE" -> "速冻冷冻";
            case "PACK" -> "分装与包装";
            case "WAREHOUSE_IN" -> "冷库入库";
            case "WAREHOUSE_OUT" -> "冷库出库";
            case "TRANSPORT" -> "冷链干线运输";
            case "ARRIVAL" -> "冷链到货验收";
            case "SALE" -> "经销零售出库";
            default -> eventType;
        };
    }

    private String resolveDataSourceLabel(String dataSource) {
        if (dataSource == null) {
            return "企业人工填报";
        }
        return switch (dataSource) {
            case "SIMULATED" -> "教学演练与仿真模拟数据（SIMULATED）";
            case "DEVICE" -> "标准预留设备标识（DEVICE，未接入真实硬件）";
            case "MANUAL" -> "企业人工填报";
            case "IMPORT" -> "企业系统导入";
            default -> dataSource;
        };
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
