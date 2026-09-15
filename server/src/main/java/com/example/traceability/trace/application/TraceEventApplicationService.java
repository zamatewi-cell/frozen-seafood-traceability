package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.domain.DataSource;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.domain.TraceEventStatus;
import com.example.traceability.trace.domain.TraceEventType;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TraceEventResponse;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 追溯事件与更正工作流应用服务。
 * <p>
 * 实现 Phase 3 核心业务事实追加与防分叉更正闭环：
 * <ul>
 *   <li>追溯事件创建：支持双时间体系，严格按批次生命周期状态矩阵（仅 ACTIVE 允许）校验；</li>
 *   <li>追加式非分叉更正：单事务写入新版本并将旧版本从 SUBMITTED 流转为 CORRECTED；</li>
 *   <li>多维度组织级防重幂等：支持同语义重放与异构语义 409 IDEMPOTENCY_CONFLICT 精准分类；</li>
 *   <li>受控 detailsJson：单层、最多 20 属性、键名正则校验、标量/null 限定、UTF-8 序列化最大 8 KiB。</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class TraceEventApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TraceEventApplicationService.class);

    private static final Pattern DETAIL_KEY_PATTERN = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");

    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "id", "batchid", "batch_id", "orgid", "org_id", "siteid", "site_id",
            "eventtype", "event_type", "occurredat", "occurred_at", "recordedat", "recorded_at",
            "operatorid", "operator_id", "datasource", "data_source", "status",
            "correctseventid", "corrects_event_id", "correctionreason", "correction_reason",
            "version", "isdeleted", "is_deleted",
            "createdat", "created_at", "createdby", "created_by",
            "updatedat", "updated_at", "updatedby", "updated_by"
    );

    private final TraceEventMapper traceEventMapper;
    private final BatchMapper batchMapper;
    private final SiteMapper siteMapper;
    private final ObjectMapper objectMapper;

    public TraceEventApplicationService(
            TraceEventMapper traceEventMapper,
            BatchMapper batchMapper,
            SiteMapper siteMapper,
            ObjectMapper objectMapper
    ) {
        this.traceEventMapper = traceEventMapper;
        this.batchMapper = batchMapper;
        this.siteMapper = siteMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定批次下的追溯事件列表（按 occurred_at ASC, recorded_at ASC, id ASC 稳定排序）。
     * <p>
     * 组织数据范围隔离在 Mapper SQL 层强制执行。企业端只能查看本组织批次的追溯事件；平台管理员可只读查看。
     * </p>
     *
     * @param batchId   批次 ID
     * @param principal 当前认证主体
     * @return 追溯事件白名单列表
     */
    public List<TraceEventResponse> listEvents(Long batchId, TraceSecurityPrincipal principal) {
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        boolean isPlatform = isPlatformScope(principal);
        if (!isPlatform && !Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权访问其他组织的批次追溯事件"
            );
        }

        Long targetOrgId = isPlatform ? batch.getOrgId() : principal.getOrgId();
        List<TraceEvent> events = traceEventMapper.selectByBatchIdAndOrgId(batchId, targetOrgId);

        return events.stream()
                .map(e -> TraceEventResponse.fromEntity(e, parseDetails(e.getDetailsJson())))
                .toList();
    }

    /**
     * 提交创建批次追溯事件。
     * <p>
     * 仅限本组织 OPERATOR 角色操作。
     * 状态矩阵：仅 ACTIVE 批次允许创建普通事件；DRAFT、FROZEN、RECALLED、CLOSED 批次禁止创建。
     * 强制携带 Idempotency-Key；同组织相同语义重放原结果，不同语义返回 409 IDEMPOTENCY_CONFLICT。
     * </p>
     *
     * @param batchId        批次 ID
     * @param req            事件创建请求
     * @param idempotencyKey 客户端幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 创建或重放的追溯事件详情
     */
    @Transactional
    public TraceEventResponse createEvent(
            Long batchId,
            CreateTraceEventRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanIdempotencyKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();

        // 1. 基础字段业务校验与规范化
        String normalizedEventType = validateEventType(req.eventType());
        String normalizedDataSource = validateDataSource(req.dataSource());
        LocalDateTime reqOccurredAtUtc = toUtcLocalDateTime(req.occurredAt(), "业务发生时间 occurredAt");
        String cleanSummary = validateSummary(req.summary());
        String serializedDetails = validateAndSerializeDetails(req.detailsJson());

        // 2. 幂等预检：查询同组织下是否已存在该幂等键记录
        TraceEvent existing = traceEventMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanIdempotencyKey);
        if (existing != null) {
            if (isSameCreateSemantics(existing, batchId, principal.getUserId(), normalizedEventType,
                    reqOccurredAtUtc, req.siteId(), normalizedDataSource, cleanSummary, req.detailsJson())) {
                return TraceEventResponse.fromEntity(existing, parseDetails(existing.getDetailsJson()));
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前幂等键已被使用且请求载荷与历史记录不一致"
            );
        }

        // 3. 批次行级排他锁校验（消除与并发冻结/召回/关闭的 TOCTOU 竞态，仅限 ACTIVE）
        Batch batch = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        if (!Objects.equals(batch.getOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权向其他组织的批次记录追溯事件"
            );
        }
        if (!BatchStatus.ACTIVE.name().equals(batch.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许当前操作",
                    "批次当前状态为 " + batch.getStatus() + "，仅 ACTIVE 状态批次允许创建追溯事件"
            );
        }

        // 4. 可选场所校验
        validateSiteId(req.siteId(), orgId);

        // 5. 构造实体并持久化
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        TraceEvent event = new TraceEvent();
        event.setBatchId(batchId);
        event.setOrgId(orgId);
        event.setSiteId(req.siteId());
        event.setEventType(normalizedEventType);
        event.setOccurredAt(reqOccurredAtUtc);
        event.setRecordedAt(nowUtc);
        event.setOperatorId(principal.getUserId());
        event.setDataSource(normalizedDataSource);
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(cleanIdempotencyKey);
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary(cleanSummary);
        event.setDetailsJson(serializedDetails);
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(nowUtc);
        event.setCreatedBy(principal.getUserId());
        event.setUpdatedAt(nowUtc);
        event.setUpdatedBy(principal.getUserId());

        try {
            traceEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            // 当前锁定读恢复 (穿透 MySQL REPEATABLE READ 快照读盲区)
            TraceEvent dup = traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
            if (dup != null) {
                if (isSameCreateSemantics(dup, batchId, principal.getUserId(), normalizedEventType,
                        reqOccurredAtUtc, req.siteId(), normalizedDataSource, cleanSummary, req.detailsJson())) {
                    return TraceEventResponse.fromEntity(dup, parseDetails(dup.getDetailsJson()));
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "幂等提交冲突",
                        "并发检测到相同幂等键，但请求载荷与已落库数据不一致"
                );
            }
            throw e;
        }

        return TraceEventResponse.fromEntity(event, parseDetails(serializedDetails));
    }

    /**
     * 追加更正追溯事件。
     * <p>
     * 仅限本组织 OPERATOR 角色操作。
     * 状态矩阵：ACTIVE 与 CLOSED 批次允许更正；DRAFT、FROZEN、RECALLED 批次禁止更正。
     * 必须在目标事件状态校验之前执行同组织幂等重试识别，确保超时重放能够稳定返回原更正结果。
     * 单事务原子插入新版本并将被更正版本流转为 CORRECTED；通过目标行排他锁与数据库唯一约束彻底阻止分叉。
     * </p>
     *
     * @param batchId        批次 ID
     * @param eventId        待更正的目标原事件 ID
     * @param req            更正请求
     * @param idempotencyKey 客户端更正幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 新生成的追溯事件详情
     */
    @Transactional
    public TraceEventResponse correctEvent(
            Long batchId,
            Long eventId,
            CorrectTraceEventRequest req,
            String idempotencyKey,
            TraceSecurityPrincipal principal
    ) {
        checkOperatorRole(principal);
        String cleanIdempotencyKey = validateIdempotencyKey(idempotencyKey);
        Long orgId = principal.getOrgId();

        // 1. 基础字段业务校验与规范化
        String normalizedEventType = validateEventType(req.eventType());
        String normalizedDataSource = validateDataSource(req.dataSource());
        LocalDateTime reqOccurredAtUtc = toUtcLocalDateTime(req.occurredAt(), "业务发生时间 occurredAt");
        String cleanSummary = validateSummary(req.summary());
        String cleanReason = validateCorrectionReason(req.correctionReason());
        String serializedDetails = validateAndSerializeDetails(req.detailsJson());

        // 2. 核心要求：更正重试必须在状态检查前识别原结果
        // 目标原事件在初次更正后已变为 CORRECTED；重试请求必须通过幂等键直接识别并返回原结果，杜绝报错
        TraceEvent existingCorrection = traceEventMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanIdempotencyKey);
        if (existingCorrection != null) {
            if (isSameCorrectionSemantics(existingCorrection, batchId, eventId, principal.getUserId(),
                    normalizedEventType, reqOccurredAtUtc, req.siteId(), normalizedDataSource, cleanSummary,
                    req.detailsJson(), cleanReason)) {
                return TraceEventResponse.fromEntity(existingCorrection, parseDetails(existingCorrection.getDetailsJson()));
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前更正幂等键已被使用且请求载荷与历史记录不一致"
            );
        }

        // 3. 批次行级排他锁校验（消除与并发冻结/召回/关闭的 TOCTOU 竞态；更正允许 ACTIVE 与 CLOSED；禁止 DRAFT, FROZEN, RECALLED）
        Batch batch = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        if (!Objects.equals(batch.getOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权在其他组织的批次下更正追溯事件"
            );
        }
        if (!BatchStatus.ACTIVE.name().equals(batch.getStatus()) && !BatchStatus.CLOSED.name().equals(batch.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许更正",
                    "批次当前状态为 " + batch.getStatus() + "，仅 ACTIVE 与 CLOSED 状态批次允许追加更正"
            );
        }

        // 4. 锁定目标原事件（SELECT ... FOR UPDATE 排他锁），防并发分叉
        TraceEvent targetEvent = traceEventMapper.selectByIdIgnoreTenantForUpdate(eventId);
        if (targetEvent == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + eventId + " 的待更正追溯事件");
        }
        if (!Objects.equals(targetEvent.getOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权更正其他企业的追溯事件"
            );
        }
        if (!Objects.equals(targetEvent.getBatchId(), batchId)) {
            throw new ResourceNotFoundException("待更正事件 " + eventId + " 不属于批次 " + batchId);
        }

        // 5. 获得目标事件排他锁后、检查状态前，再次当前读复核幂等键（消除同 key 并发更正竞争时等待唤醒后的误判）
        // 两个相同 key 的并发更正，后获得锁的事务在行锁等待期间，前一事务可能已将 targetEvent 变为 CORRECTED；
        // 此时必须在状态检查前二次当前读识别该 key 是否已由前序并发事务成功落库，避免误报 EVENT_ALREADY_CORRECTED
        TraceEvent postLockIdempotency = traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
        if (postLockIdempotency != null) {
            if (isSameCorrectionSemantics(postLockIdempotency, batchId, eventId, principal.getUserId(),
                    normalizedEventType, reqOccurredAtUtc, req.siteId(), normalizedDataSource, cleanSummary,
                    req.detailsJson(), cleanReason)) {
                return TraceEventResponse.fromEntity(postLockIdempotency, parseDetails(postLockIdempotency.getDetailsJson()));
            }
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等提交冲突",
                    "当前更正幂等键已被使用且请求载荷与历史记录不一致"
            );
        }

        // 6. 校验被更正事件是否为当前有效版本 (必须为 SUBMITTED，不能是已被更正的 CORRECTED)
        if (TraceEventStatus.CORRECTED.name().equals(targetEvent.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "EVENT_ALREADY_CORRECTED",
                    "事件已被更正",
                    "目标追溯事件已被更正，链式更正仅允许对当前生效版本 (SUBMITTED) 发起更正"
            );
        }

        // 7. 可选场所校验
        validateSiteId(req.siteId(), orgId);

        // 8. 单事务执行：插入新更正版本 + 将原事件状态修改为 CORRECTED
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        TraceEvent newEvent = new TraceEvent();
        newEvent.setBatchId(batchId);
        newEvent.setOrgId(orgId);
        newEvent.setSiteId(req.siteId());
        newEvent.setEventType(normalizedEventType);
        newEvent.setOccurredAt(reqOccurredAtUtc);
        newEvent.setRecordedAt(nowUtc);
        newEvent.setOperatorId(principal.getUserId());
        newEvent.setDataSource(normalizedDataSource);
        newEvent.setStatus(TraceEventStatus.SUBMITTED.name());
        newEvent.setIdempotencyKey(cleanIdempotencyKey);
        newEvent.setCorrectsEventId(targetEvent.getId());
        newEvent.setCorrectionReason(cleanReason);
        newEvent.setSummary(cleanSummary);
        newEvent.setDetailsJson(serializedDetails);
        newEvent.setVersion(0L);
        newEvent.setIsDeleted(0);
        newEvent.setCreatedAt(nowUtc);
        newEvent.setCreatedBy(principal.getUserId());
        newEvent.setUpdatedAt(nowUtc);
        newEvent.setUpdatedBy(principal.getUserId());

        try {
            traceEventMapper.insert(newEvent);
        } catch (DuplicateKeyException e) {
            // 当前锁定读重查
            TraceEvent dup = traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanIdempotencyKey);
            if (dup != null) {
                if (isSameCorrectionSemantics(dup, batchId, eventId, principal.getUserId(),
                        normalizedEventType, reqOccurredAtUtc, req.siteId(), normalizedDataSource, cleanSummary,
                        req.detailsJson(), cleanReason)) {
                    return TraceEventResponse.fromEntity(dup, parseDetails(dup.getDetailsJson()));
                }
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "幂等提交冲突",
                        "并发检测到相同幂等键，但请求载荷与已落库数据不一致"
                );
            }

            // 若由 uk_trace_event_corrects 唯一索引冲突引起，表明并发竞争导致已被其他事务成功更正
            // 必须使用当前锁定读 (FOR UPDATE) 穿透 MySQL REPEATABLE READ 快照读盲区，防止误报 500
            TraceEvent existingForTarget = traceEventMapper.selectByCorrectsEventIdForUpdate(targetEvent.getId());
            if (existingForTarget != null) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "EVENT_ALREADY_CORRECTED",
                        "事件已被更正",
                        "目标追溯事件已被并发事务更正，禁止分叉更正"
                );
            }
            throw e;
        }

        // 原子将旧版本流转为 CORRECTED，并递增版本号（带 batchId 与 orgId 三重防线约束）
        int affected = traceEventMapper.updateStatusToCorrected(targetEvent.getId(), batchId, orgId, principal.getUserId(), nowUtc);
        if (affected != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "EVENT_ALREADY_CORRECTED",
                    "事件状态变更冲突",
                    "目标追溯事件已被并发修改，更正事务已自动回滚"
            );
        }

        return TraceEventResponse.fromEntity(newEvent, parseDetails(serializedDetails));
    }

    // =========================================================================
    // 内部校验与辅助算法
    // =========================================================================

    private void checkOperatorRole(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null || !principal.getRoles().contains("OPERATOR")
                || isPlatformScope(principal)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "权限不足",
                    "仅具备企业操作员 (OPERATOR) 角色的用户允许执行追溯事件写入与更正操作，平台管理角色无权录入或更正企业追溯事件"
            );
        }
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
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

    private String validateEventType(String eventType) {
        if (eventType == null || eventType.isBlank() || !TraceEventType.isValid(eventType)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "不支持的追溯事件类型: " + eventType + "，允许的值域为: SOURCE, PURCHASE, PROCESS, FREEZE, PACK, WAREHOUSE_IN, WAREHOUSE_OUT, TRANSPORT, ARRIVAL, SALE"
            );
        }
        return TraceEventType.fromCode(eventType).name();
    }

    private String validateDataSource(String dataSource) {
        if (dataSource == null || dataSource.isBlank() || !DataSource.isValid(dataSource)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "不支持的数据来源: " + dataSource + "，允许的值域为: MANUAL, IMPORT, SIMULATED, DEVICE"
            );
        }
        return DataSource.fromCode(dataSource).name();
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime offsetDateTime, String fieldName) {
        if (offsetDateTime == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    fieldName + " 不能为空"
            );
        }
        return offsetDateTime.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }

    private String validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "事件摘要 summary 不能为空"
            );
        }
        String clean = summary.trim();
        if (clean.length() > 500) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "事件摘要 summary 长度不得超过 500 个字符"
            );
        }
        return clean;
    }

    private String validateCorrectionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "更正原因说明 correctionReason 不能为空"
            );
        }
        String clean = reason.trim();
        if (clean.length() > 500) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "更正原因说明 correctionReason 长度不得超过 500 个字符"
            );
        }
        return clean;
    }

    private void validateSiteId(Long siteId, Long orgId) {
        if (siteId == null) {
            return;
        }
        Site site = siteMapper.selectByIdIgnoreTenant(siteId);
        if (site == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + siteId + " 的场所");
        }
        if (!Objects.equals(site.getOrgId(), orgId)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权引用其他组织的场所"
            );
        }
        if (!"ACTIVE".equals(site.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SITE_NOT_ACTIVE",
                    "场所未启用",
                    "指定场所未处于启用状态，当前状态为: " + site.getStatus()
            );
        }
    }

    /**
     * 严格校验受控 detailsJson 扩展属性：
     * 1. 仅单层对象，最多 20 个属性；
     * 2. key 匹配 ^[a-z][A-Za-z0-9]{0,63}$；
     * 3. key 不能占用系统核心字段与持久层字段；
     * 4. value 仅允许标量 (String, Number, Boolean) 或 null，严禁嵌套 Map 或 Collection；
     * 5. 字符串类型属性值最长 500 字符；
     * 6. UTF-8 序列化最大 8 KiB (8192 字节)。
     */
    private String validateAndSerializeDetails(Map<String, Object> detailsJson) {
        if (detailsJson == null || detailsJson.isEmpty()) {
            return null;
        }
        if (detailsJson.size() > 20) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "detailsJson 最多仅允许 20 个属性，当前数量: " + detailsJson.size()
            );
        }

        for (Map.Entry<String, Object> entry : detailsJson.entrySet()) {
            String key = entry.getKey();
            if (key == null || !DETAIL_KEY_PATTERN.matcher(key).matches()) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "detailsJson 属性键名不合规: " + key + "，必须以小写字母开头，由字母数字组成且长度不超过 64 个字符"
                );
            }

            String lowerKey = key.toLowerCase();
            if (FORBIDDEN_DETAIL_KEYS.contains(lowerKey)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "detailsJson 属性键名不能占用核心系统与业务字段: " + key
                );
            }

            Object val = entry.getValue();
            if (val != null) {
                if (val instanceof Map || val instanceof Iterable || val.getClass().isArray()) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "参数校验失败",
                            "detailsJson 仅支持单层标量属性，禁止嵌套复杂对象或列表: " + key
                    );
                }
                if (val instanceof String strVal) {
                    if (strVal.length() > 500) {
                        throw new BusinessException(
                                HttpStatus.BAD_REQUEST,
                                "INVALID_REQUEST",
                                "参数校验失败",
                                "detailsJson 字符串属性值长度不得超过 500 个字符: " + key
                        );
                    }
                } else if (!(val instanceof Number || val instanceof Boolean)) {
                    throw new BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_REQUEST",
                            "参数校验失败",
                            "detailsJson 仅允许标量类型 (字符串、数值、布尔) 或 null: " + key
                    );
                }
            }
        }

        try {
            String serialized = objectMapper.writeValueAsString(detailsJson);
            byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8192) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "detailsJson 序列化 UTF-8 大小超出 8 KiB 限制，当前字节数: " + bytes.length
                );
            }
            return serialized;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "detailsJson 序列化失败: " + e.getMessage()
            );
        }
    }

    private Map<String, Object> parseDetails(String detailsJsonStr) {
        if (detailsJsonStr == null || detailsJsonStr.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(detailsJsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("反序列化 detailsJson 失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private boolean isSameCreateSemantics(
            TraceEvent existing,
            Long batchId,
            Long operatorId,
            String eventType,
            LocalDateTime occurredAtUtc,
            Long siteId,
            String dataSource,
            String summary,
            Map<String, Object> requestDetails
    ) {
        if (existing.getCorrectsEventId() != null) {
            return false;
        }
        if (!Objects.equals(existing.getBatchId(), batchId)) {
            return false;
        }
        if (!Objects.equals(existing.getOperatorId(), operatorId)) {
            return false;
        }
        if (!Objects.equals(existing.getEventType(), eventType)) {
            return false;
        }
        if (existing.getOccurredAt() == null || !existing.getOccurredAt().truncatedTo(ChronoUnit.MILLIS).isEqual(occurredAtUtc)) {
            return false;
        }
        if (!Objects.equals(existing.getSiteId(), siteId)) {
            return false;
        }
        if (!Objects.equals(existing.getDataSource(), dataSource)) {
            return false;
        }
        if (!Objects.equals(existing.getSummary(), summary)) {
            return false;
        }
        return isSameDetails(existing.getDetailsJson(), requestDetails);
    }

    private boolean isSameCorrectionSemantics(
            TraceEvent existing,
            Long batchId,
            Long targetEventId,
            Long operatorId,
            String eventType,
            LocalDateTime occurredAtUtc,
            Long siteId,
            String dataSource,
            String summary,
            Map<String, Object> requestDetails,
            String correctionReason
    ) {
        if (!Objects.equals(existing.getCorrectsEventId(), targetEventId)) {
            return false;
        }
        if (!Objects.equals(existing.getBatchId(), batchId)) {
            return false;
        }
        if (!Objects.equals(existing.getOperatorId(), operatorId)) {
            return false;
        }
        if (!Objects.equals(existing.getEventType(), eventType)) {
            return false;
        }
        if (existing.getOccurredAt() == null || !existing.getOccurredAt().truncatedTo(ChronoUnit.MILLIS).isEqual(occurredAtUtc)) {
            return false;
        }
        if (!Objects.equals(existing.getSiteId(), siteId)) {
            return false;
        }
        if (!Objects.equals(existing.getDataSource(), dataSource)) {
            return false;
        }
        if (!Objects.equals(existing.getSummary(), summary)) {
            return false;
        }
        if (!Objects.equals(existing.getCorrectionReason(), correctionReason)) {
            return false;
        }
        return isSameDetails(existing.getDetailsJson(), requestDetails);
    }

    private boolean isSameDetails(String existingJsonStr, Map<String, Object> requestMap) {
        Map<String, Object> existingMap = parseDetails(existingJsonStr);
        if (existingMap == null || existingMap.isEmpty()) {
            return requestMap == null || requestMap.isEmpty();
        }
        if (requestMap == null || requestMap.isEmpty()) {
            return false;
        }
        return Objects.equals(existingMap, requestMap);
    }

    /**
     * 接收方接受企业交接时，在同一事务内追加一条 ARRIVAL 追溯事件。
     *
     * @param batchId            批次 ID
     * @param receiverOrgId      接收组织 ID
     * @param decidedBy          接收决定操作人 ID
     * @param receivedAt         收货业务发生时间
     * @param decisionRecordedAt 接收决定系统记录时间
     * @param transferId         关联交接凭证 ID
     * @param differenceReason   数量差异原因说明（可空）
     */
    @Transactional
    public void appendArrivalEvent(
            Long batchId,
            Long receiverOrgId,
            Long decidedBy,
            OffsetDateTime receivedAt,
            OffsetDateTime decisionRecordedAt,
            Long transferId,
            String differenceReason
    ) {
        LocalDateTime occurredAtUtc = receivedAt != null
                ? receivedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                : LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime recordedAtUtc = decisionRecordedAt != null
                ? decisionRecordedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                : LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("transferId", transferId);
        if (differenceReason != null && !differenceReason.isBlank()) {
            details.put("differenceReason", differenceReason.trim());
        }

        String serializedDetails;
        try {
            serializedDetails = objectMapper.writeValueAsString(details);
        } catch (Exception ex) {
            serializedDetails = "{\"transferId\":" + transferId + "}";
        }

        String idempotencyKey = "TRANSFER_ARRIVAL_" + transferId;

        TraceEvent event = new TraceEvent();
        event.setBatchId(batchId);
        event.setOrgId(receiverOrgId);
        event.setSiteId(null);
        event.setEventType(TraceEventType.ARRIVAL.name());
        event.setOccurredAt(occurredAtUtc);
        event.setRecordedAt(recordedAtUtc);
        event.setOperatorId(decidedBy);
        event.setDataSource(DataSource.MANUAL.name());
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(idempotencyKey);
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary("完成企业间整批交接并确认到货验收");
        event.setDetailsJson(serializedDetails);
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(recordedAtUtc);
        event.setCreatedBy(decidedBy);
        event.setUpdatedAt(recordedAtUtc);
        event.setUpdatedBy(decidedBy);

        traceEventMapper.insert(event);
    }
}
