package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** 服务端自动事件保留的幂等键前缀；人工事件接口不得使用。 */
    static final String RESERVED_IDEMPOTENCY_PREFIX = "SYS:";

    /**
     * 只能由结构化业务对象自动投影、人工普通事件接口不得伪造的事件类型（统一业务契约 v1.1 §11）：
     * SOURCE 由来源批次激活产生；PROCESS 由 PROCESS 批次操作提交产生；TRANSPORT / ARRIVAL 由 Shipment 发运 / 到达产生；
     * SALE 只能由终端 Sale 台账成功提交产生（{@link #appendSaleEvent}）。
     */
    private static final Set<String> AUTO_ONLY_EVENT_TYPES = Set.of(
            TraceEventType.SOURCE.name(),
            TraceEventType.PROCESS.name(),
            TraceEventType.TRANSPORT.name(),
            TraceEventType.ARRIVAL.name(),
            TraceEventType.SALE.name()
    );

    /**
     * 受控人工仓储事件（统一业务契约 v1.1 §8、§11）：只记录当前责任组织在本组织自有 {@code Site(COLD_STORE)} 的场所流转事实，
     * 不改变批次责任组织、数量与状态。v1.1 未定义仓储状态机，本实现不强制 IN / OUT 配对或顺序。
     */
    private static final Set<String> WAREHOUSE_EVENT_TYPES = Set.of(
            TraceEventType.WAREHOUSE_IN.name(),
            TraceEventType.WAREHOUSE_OUT.name()
    );

    private static final String SITE_TYPE_COLD_STORE = "COLD_STORE";

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
     * 读取范围（统一业务契约 v1.1 §14）：
     * <ul>
     *   <li>当前责任组织与平台只读角色：该批次完整时间线（含其他组织在前序环节记录的 SOURCE / TRANSPORT / ARRIVAL 等事件）；</li>
     *   <li>历史参与组织（批次已转出，但本组织曾在该批次记录过事件）：仅本组织记录的历史事件，只读；</li>
     *   <li>其他组织：403 ORG_SCOPE_DENIED。</li>
     * </ul>
     * 写入与更正仍严格限定当前责任组织，历史参与组织不能修改已转出的批次。
     * </p>
     *
     * @param batchId   批次 ID
     * @param principal 当前认证主体
     * @return 追溯事件白名单列表
     */
    // 授权所依据的可变责任组织事实与构造响应的全部数据必须来自同一 InnoDB 一致性快照：只读 REPEATABLE READ 事务
    // 让首个一致性读建立读视图，后续非锁定读复用它，看不到交接接受后新责任组织才提交的行（不加任何锁）
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TraceEventResponse> listEvents(Long batchId, TraceSecurityPrincipal principal) {
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        List<TraceEvent> events;
        if (isPlatformScope(principal) || Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            events = traceEventMapper.selectByBatchId(batchId);
        } else if (traceEventMapper.countByBatchIdAndOrgId(batchId, principal.getOrgId()) > 0) {
            events = traceEventMapper.selectByBatchIdAndOrgId(batchId, principal.getOrgId());
        } else {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ORG_SCOPE_DENIED",
                    "组织数据访问越权",
                    "无权访问其他组织的批次追溯事件"
            );
        }

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
        rejectAutoOnlyEventType(normalizedEventType);
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
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许当前操作",
                    "批次当前流转或风险状态不允许创建追溯事件 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")，仅 ACTIVE 且 NORMAL 状态批次允许创建追溯事件"
            );
        }

        // 4. 场所校验：仓储事件必须引用本组织启用的自有冷库，其余事件场所可选
        if (WAREHOUSE_EVENT_TYPES.contains(normalizedEventType)) {
            enforceWarehouseRules(req.siteId(), orgId, normalizedDataSource, req.detailsJson());
        } else {
            validateSiteId(req.siteId(), orgId);
        }

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
        rejectAutoOnlyEventType(normalizedEventType);
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
        boolean flowAllowed = BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || BatchFlowStatus.CLOSED.name().equals(batch.getFlowStatus());
        boolean riskAllowed = BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus());
        if (!flowAllowed || !riskAllowed) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BATCH_FLOW_BLOCKED",
                    "批次状态不允许更正",
                    "批次当前状态不允许追加更正 (flowStatus=" + batch.getFlowStatus() + ", riskStatus=" + batch.getRiskStatus() + ")，仅 ACTIVE/CLOSED 且 NORMAL 状态批次允许追加更正"
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
        // 自动投影事件（如 SOURCE）由结构化业务对象唯一决定，禁止经人工更正链替换或作废
        if (targetEvent.getEventType() != null && AUTO_ONLY_EVENT_TYPES.contains(targetEvent.getEventType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "AUTO_EVENT_NOT_CORRECTABLE",
                    "自动事件不可人工更正",
                    "事件类型 " + targetEvent.getEventType() + " 由业务单据自动生成，不能通过人工更正接口修改"
            );
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

        // 7. 仓储事件更正属于追加式审计更正（不是新的仓储流转，CLOSED 批次仍允许）：
        //    只允许在 WAREHOUSE_IN / WAREHOUSE_OUT 之间更正，且新版本同样必须引用本组织启用的自有冷库
        boolean targetIsWarehouse = targetEvent.getEventType() != null && WAREHOUSE_EVENT_TYPES.contains(targetEvent.getEventType());
        boolean newIsWarehouse = WAREHOUSE_EVENT_TYPES.contains(normalizedEventType);
        if (targetIsWarehouse != newIsWarehouse) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "WAREHOUSE_EVENT_TYPE_CHANGE_FORBIDDEN",
                    "仓储事件类型不可跨类更正",
                    "冷库出入库事件只能在 WAREHOUSE_IN 与 WAREHOUSE_OUT 之间更正，其他事件也不能更正为冷库出入库事件（原类型 "
                            + targetEvent.getEventType() + "，新类型 " + normalizedEventType + "）"
            );
        }
        if (newIsWarehouse) {
            enforceWarehouseRules(req.siteId(), orgId, normalizedDataSource, req.detailsJson());
        } else {
            validateSiteId(req.siteId(), orgId);
        }

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
        if (clean.toUpperCase(Locale.ROOT).startsWith(RESERVED_IDEMPOTENCY_PREFIX)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "Idempotency-Key 不得使用服务端保留前缀 " + RESERVED_IDEMPOTENCY_PREFIX
            );
        }
        return clean;
    }

    private void rejectAutoOnlyEventType(String normalizedEventType) {
        if (AUTO_ONLY_EVENT_TYPES.contains(normalizedEventType)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "EVENT_TYPE_NOT_MANUAL",
                    "事件类型不允许人工录入",
                    "事件类型 " + normalizedEventType + " 只能由业务单据自动生成（SOURCE 由来源批次提交激活自动产生，PROCESS 由加工批次操作提交自动产生，TRANSPORT / ARRIVAL 由运输任务发运 / 到达自动产生，SALE 只能由终端销售提交自动产生），人工事件接口不得创建或更正为该类型"
            );
        }
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
     * 冷库出入库事件（WAREHOUSE_IN / WAREHOUSE_OUT）的受控人工录入规则（统一业务契约 v1.1 §8）：
     * <ol>
     *   <li>必须指定场所；</li>
     *   <li>场所必须属于调用方组织（调用方已校验为批次当前责任组织），跨组织 403；</li>
     *   <li>场所必须启用；</li>
     *   <li>场所类型必须为 COLD_STORE（仅支持自有冷库，不支持第三方仓储）；</li>
     *   <li>数据来源必须为 MANUAL（受控人工事实，不接受设备 / 导入 / 模拟来源）；</li>
     *   <li>不接受 detailsJson：仓储不改变数量，温度记录属于 Phase B，说明写入 summary。</li>
     * </ol>
     */
    private void enforceWarehouseRules(Long siteId, Long orgId, String normalizedDataSource, Map<String, Object> detailsJson) {
        if (siteId == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "冷库出入库事件必须指定本组织启用的冷库场所 siteId"
            );
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
                    "冷库出入库只能使用当前责任组织自有的冷库场所，无权引用其他组织的场所"
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
        if (!SITE_TYPE_COLD_STORE.equals(site.getSiteType())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "WAREHOUSE_SITE_TYPE_INVALID",
                    "场所不是冷库",
                    "冷库出入库事件只能引用类型为 COLD_STORE 的场所，当前场所类型为: " + site.getSiteType()
            );
        }
        if (!DataSource.MANUAL.name().equals(normalizedDataSource)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "冷库出入库为受控人工事件，数据来源 dataSource 必须为 MANUAL"
            );
        }
        if (detailsJson != null && !detailsJson.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "参数校验失败",
                    "冷库出入库事件不接受 detailsJson（仓储不改变数量，温度记录不在本接口登记），说明请写入 summary"
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
     * 来源批次激活时，在调用方事务内自动投影唯一一条 SOURCE 追溯事件。
     * <p>
     * 唯一可靠触发源是 SOURCE Batch 从 DRAFT/NORMAL 激活为 ACTIVE/NORMAL（统一业务契约 v1.1 §11）。
     * 事件幂等身份由服务端基于批次 ID 决定（{@link #sourceEventIdempotencyKey(Long)}），
     * 并由既有唯一约束 {@code uk_trace_event_org_idempotency (org_id, idempotency_key)} 保证同一批次至多一条 SOURCE；
     * 人工事件接口拒绝 {@code SYS:} 前缀幂等键，客户端无法预先占用该身份。
     * 业务发生时间取提交激活时刻；生产/捕捞/速冻日期仅以原始 LocalDate 存入结构化 detailsJson，不伪造为精确时间。
     * 必须在已有事务中调用（{@link Propagation#MANDATORY}）；写入失败抛出异常，由调用方事务整体回滚批次激活。
     * </p>
     *
     * @param batch          已激活的来源批次（提交后重新读取的最新行）
     * @param operatorId     提交激活的操作人 ID
     * @param activatedAtUtc 提交激活时刻 (UTC)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSourceEvent(Batch batch, Long operatorId, LocalDateTime activatedAtUtc) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceObjectType", "BATCH");
        details.put("sourceObjectId", batch.getId());
        details.put("traceBatchNo", batch.getTraceBatchNo());
        details.put("productId", batch.getProductId());
        details.put("originType", batch.getOriginType());
        details.put("originText", batch.getOriginText());
        details.put("quantity", batch.getQuantity().toPlainString());
        details.put("unitCode", batch.getUnitCode());
        if (batch.getProductionDate() != null) {
            details.put("productionDate", batch.getProductionDate().toString());
        }
        if (batch.getCaptureDate() != null) {
            details.put("captureDate", batch.getCaptureDate().toString());
        }
        if (batch.getFreezeDate() != null) {
            details.put("freezeDate", batch.getFreezeDate().toString());
        }
        if (batch.getShelfLifeDays() != null) {
            details.put("shelfLifeDays", batch.getShelfLifeDays());
        }
        details.put("occurredAtBasis", "BATCH_ACTIVATION");

        TraceEvent event = new TraceEvent();
        event.setBatchId(batch.getId());
        event.setOrgId(batch.getOrgId());
        event.setSiteId(null);
        event.setEventType(TraceEventType.SOURCE.name());
        event.setOccurredAt(activatedAtUtc);
        event.setRecordedAt(activatedAtUtc);
        event.setOperatorId(operatorId);
        // 来源事实由企业操作员在来源批次中手工登记，沿用既有 MANUAL 语义
        event.setDataSource(DataSource.MANUAL.name());
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(sourceEventIdempotencyKey(batch.getId()));
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary("来源批次激活：" + batch.getOriginText());
        event.setDetailsJson(objectMapper.writeValueAsString(details));
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(activatedAtUtc);
        event.setCreatedBy(operatorId);
        event.setUpdatedAt(activatedAtUtc);
        event.setUpdatedBy(operatorId);

        try {
            traceEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SOURCE_EVENT_CONFLICT",
                    "来源事件冲突",
                    "该来源批次的 SOURCE 追溯事件身份已被占用，批次激活已回滚"
            );
        }
    }

    /**
     * 来源批次 SOURCE 事件的服务端幂等身份。以保留前缀 {@code SYS:} 开头，人工接口不可使用。
     *
     * @param batchId 来源批次 ID
     * @return 服务端内部幂等键
     */
    public static String sourceEventIdempotencyKey(Long batchId) {
        return RESERVED_IDEMPOTENCY_PREFIX + "SOURCE:BATCH:" + batchId;
    }

    /**
     * PROCESS 批次操作提交成功时，在调用方事务内为一个 OUTPUT 批次自动投影唯一一条 PROCESS 事件。
     * <p>
     * 唯一可靠触发源：PROCESS BatchOperation 成功提交（统一业务契约 v1.1 §11）。事件落在产出批次上，
     * 输入批次的全量消耗由批次 {@code consumedByOperationId} 与谱系边表达，不伪造额外事件。
     * 普通 PROCESS 不推导 FREEZE；SPLIT 不调用本方法（不伪装成 PACK 或 PROCESS）。
     * 幂等身份由 {@link #processEventIdempotencyKey(Long, Long)} 决定，并由唯一约束
     * {@code uk_trace_event_org_idempotency} 保证同一操作同一产出批次至多一条 PROCESS。
     * 摘要只包含数量事实，不含内部标识。
     * </p>
     *
     * @param projection      批次操作投影上下文
     * @param outputBatchId   产出批次 ID
     * @param operatorId      提交操作的操作人 ID
     * @param recordedAtUtc   系统登记时间 (UTC)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendProcessEvent(ProcessProjection projection, Long outputBatchId, Long operatorId, LocalDateTime recordedAtUtc) {
        String outputTraceBatchNo = null;
        BigDecimal outputQuantity = null;
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (ProcessBatchLine line : projection.outputs()) {
            outputs.add(line.toDetails());
            if (Objects.equals(line.batchId(), outputBatchId)) {
                outputTraceBatchNo = line.traceBatchNo();
                outputQuantity = line.quantity();
            }
        }
        if (outputQuantity == null) {
            throw new IllegalArgumentException("批次 " + outputBatchId + " 不是操作 " + projection.operationNo() + " 的产出批次");
        }
        List<Map<String, Object>> inputs = projection.inputs().stream().map(ProcessBatchLine::toDetails).toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceObjectType", "BATCH_OPERATION");
        details.put("sourceObjectId", projection.operationId());
        details.put("operationNo", projection.operationNo());
        details.put("operationType", "PROCESS");
        details.put("traceBatchNo", outputTraceBatchNo);
        details.put("inputs", inputs);
        details.put("outputs", outputs);
        details.put("lossQuantity", projection.lossQuantity().toPlainString());
        details.put("wasteQuantity", projection.wasteQuantity().toPlainString());
        details.put("sampleQuantity", projection.sampleQuantity().toPlainString());
        details.put("unitCode", "kg");
        details.put("occurredAtBasis", "OPERATION_OCCURRED");

        StringBuilder summary = new StringBuilder("加工产出 ")
                .append(plain(outputQuantity)).append(" kg（投入 ").append(plain(projection.inputTotal())).append(" kg");
        appendIfPositive(summary, "损耗", projection.lossQuantity());
        appendIfPositive(summary, "废弃", projection.wasteQuantity());
        appendIfPositive(summary, "留样", projection.sampleQuantity());
        summary.append("）");

        TraceEvent event = new TraceEvent();
        event.setBatchId(outputBatchId);
        event.setOrgId(projection.orgId());
        event.setSiteId(null);
        event.setEventType(TraceEventType.PROCESS.name());
        event.setOccurredAt(projection.occurredAtUtc());
        event.setRecordedAt(recordedAtUtc);
        event.setOperatorId(operatorId);
        // 加工单由企业操作员在页面填写并提交，沿用既有 MANUAL 语义
        event.setDataSource(DataSource.MANUAL.name());
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(processEventIdempotencyKey(projection.operationId(), outputBatchId));
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary(summary.toString());
        event.setDetailsJson(objectMapper.writeValueAsString(details));
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(recordedAtUtc);
        event.setCreatedBy(operatorId);
        event.setUpdatedAt(recordedAtUtc);
        event.setUpdatedBy(operatorId);

        try {
            traceEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PROCESS_EVENT_CONFLICT",
                    "加工追溯事件冲突",
                    "批次操作 " + projection.operationNo() + " 对产出批次 " + outputBatchId
                            + " 的 PROCESS 追溯事件身份已被占用，批次操作提交已回滚"
            );
        }
    }

    /**
     * PROCESS 事件的服务端幂等身份（保留前缀 {@code SYS:}，人工接口不可使用）。
     */
    public static String processEventIdempotencyKey(Long operationId, Long outputBatchId) {
        return RESERVED_IDEMPOTENCY_PREFIX + "PROCESS:OPERATION:" + operationId + ":BATCH:" + outputBatchId;
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static void appendIfPositive(StringBuilder sb, String label, BigDecimal value) {
        if (value != null && value.signum() > 0) {
            sb.append("，").append(label).append(" ").append(plain(value)).append(" kg");
        }
    }

    /**
     * PROCESS 事件投影所需的批次操作结构化事实快照。
     *
     * @param operationId    批次操作 ID
     * @param operationNo    操作单号
     * @param orgId          执行组织（当前责任组织），作为事件记录组织
     * @param occurredAtUtc  操作业务发生时间 (UTC)
     * @param inputs         INPUT 批次行
     * @param outputs        OUTPUT 批次行
     * @param inputTotal     INPUT 合计
     * @param lossQuantity   LOSS 合计
     * @param wasteQuantity  WASTE 合计
     * @param sampleQuantity SAMPLE 合计
     */
    public record ProcessProjection(
            Long operationId,
            String operationNo,
            Long orgId,
            LocalDateTime occurredAtUtc,
            List<ProcessBatchLine> inputs,
            List<ProcessBatchLine> outputs,
            BigDecimal inputTotal,
            BigDecimal lossQuantity,
            BigDecimal wasteQuantity,
            BigDecimal sampleQuantity
    ) {
    }

    /**
     * PROCESS 事件中的一条批次数量事实。
     */
    public record ProcessBatchLine(Long batchId, String traceBatchNo, BigDecimal quantity) {
        Map<String, Object> toDetails() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceBatchNo", traceBatchNo);
            m.put("quantity", quantity.toPlainString());
            return m;
        }
    }

    /**
     * 运输任务从 PLANNED 进入 IN_TRANSIT 时，在调用方事务内为装载清单中的一个 Batch 自动投影唯一一条 TRANSPORT 事件。
     * <p>
     * 唯一可靠触发源：Shipment PLANNED → IN_TRANSIT（统一业务契约 v1.1 §11）。
     * 事件记录组织为运输期间仍承担责任的发送方；操作人为确认装载的承运商用户；
     * 业务发生时间为 shipment.loaded_at，场所为启运场所。
     * 幂等身份由 {@link #shipmentTransportEventIdempotencyKey(Long, Long)} 决定，并由唯一约束
     * {@code uk_trace_event_org_idempotency} 保证同一 Shipment 同一 Batch 至多一条 TRANSPORT。
     * </p>
     *
     * @param projection     运输任务投影上下文
     * @param batchId        装载批次 ID
     * @param traceBatchNo   装载批次追溯批次号
     * @param transferId     对应交接 ID
     * @param operatorId     确认发运的承运商操作人 ID
     * @param recordedAtUtc  系统登记时间 (UTC)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendShipmentTransportEvent(
            ShipmentProjection projection,
            Long batchId,
            String traceBatchNo,
            Long transferId,
            Long operatorId,
            LocalDateTime recordedAtUtc
    ) {
        Map<String, Object> details = shipmentDetails(projection, traceBatchNo, transferId, "SHIPMENT_LOADED");
        insertShipmentEvent(
                projection,
                batchId,
                TraceEventType.TRANSPORT,
                projection.loadedAtUtc(),
                projection.originSiteId(),
                shipmentTransportEventIdempotencyKey(projection.shipmentId(), batchId),
                "冷链运输发运：" + projection.originSiteName() + " → " + projection.destinationSiteName(),
                details,
                operatorId,
                recordedAtUtc
        );
    }

    /**
     * 运输任务从 IN_TRANSIT 进入 DELIVERED 时，在调用方事务内为装载清单中的一个 Batch 自动投影唯一一条 ARRIVAL 事件。
     * <p>
     * 唯一可靠触发源：Shipment IN_TRANSIT → DELIVERED（统一业务契约 v1.1 §11）。ARRIVAL 只表示物理到达，
     * 不表示接收方接受；Transfer ACCEPTED 不再生成 ARRIVAL。业务发生时间为 shipment.unloaded_at，场所为目的场所。
     * </p>
     *
     * @param projection     运输任务投影上下文
     * @param batchId        装载批次 ID
     * @param traceBatchNo   装载批次追溯批次号
     * @param transferId     对应交接 ID
     * @param operatorId     确认到达的承运商操作人 ID
     * @param recordedAtUtc  系统登记时间 (UTC)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendShipmentArrivalEvent(
            ShipmentProjection projection,
            Long batchId,
            String traceBatchNo,
            Long transferId,
            Long operatorId,
            LocalDateTime recordedAtUtc
    ) {
        Map<String, Object> details = shipmentDetails(projection, traceBatchNo, transferId, "SHIPMENT_UNLOADED");
        insertShipmentEvent(
                projection,
                batchId,
                TraceEventType.ARRIVAL,
                projection.unloadedAtUtc(),
                projection.destinationSiteId(),
                shipmentArrivalEventIdempotencyKey(projection.shipmentId(), batchId),
                "冷链运输到达：" + projection.destinationSiteName(),
                details,
                operatorId,
                recordedAtUtc
        );
    }

    /**
     * 运输任务 TRANSPORT 事件的服务端幂等身份（保留前缀 {@code SYS:}，人工接口不可使用）。
     */
    public static String shipmentTransportEventIdempotencyKey(Long shipmentId, Long batchId) {
        return RESERVED_IDEMPOTENCY_PREFIX + "TRANSPORT:SHIPMENT:" + shipmentId + ":BATCH:" + batchId;
    }

    /**
     * 运输任务 ARRIVAL 事件的服务端幂等身份（保留前缀 {@code SYS:}，人工接口不可使用）。
     */
    public static String shipmentArrivalEventIdempotencyKey(Long shipmentId, Long batchId) {
        return RESERVED_IDEMPOTENCY_PREFIX + "ARRIVAL:SHIPMENT:" + shipmentId + ":BATCH:" + batchId;
    }

    /**
     * 运输任务自动事件投影所需的结构化事实快照。
     *
     * @param shipmentId           运输任务 ID
     * @param shipmentNo           运输单号
     * @param senderOrgId          发送方组织 ID（运输期间仍为当前责任组织，作为事件记录组织）
     * @param carrierOrgId         承运组织 ID
     * @param carrierOrgName       承运组织名称
     * @param vehicleOrContainerNo 车辆或容器编号
     * @param originSiteId         启运场所 ID
     * @param originSiteName       启运场所名称
     * @param destinationSiteId    目的场所 ID
     * @param destinationSiteName  目的场所名称
     * @param loadedAtUtc          装载发运业务时间 (UTC)
     * @param unloadedAtUtc        到达卸货业务时间 (UTC，发运时为 null)
     */
    public record ShipmentProjection(
            Long shipmentId,
            String shipmentNo,
            Long senderOrgId,
            Long carrierOrgId,
            String carrierOrgName,
            String vehicleOrContainerNo,
            Long originSiteId,
            String originSiteName,
            Long destinationSiteId,
            String destinationSiteName,
            LocalDateTime loadedAtUtc,
            LocalDateTime unloadedAtUtc
    ) {
    }

    private Map<String, Object> shipmentDetails(
            ShipmentProjection p,
            String traceBatchNo,
            Long transferId,
            String occurredAtBasis
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceObjectType", "SHIPMENT");
        details.put("sourceObjectId", p.shipmentId());
        details.put("shipmentId", p.shipmentId());
        details.put("shipmentNo", p.shipmentNo());
        details.put("transferId", transferId);
        details.put("traceBatchNo", traceBatchNo);
        details.put("carrierOrgId", p.carrierOrgId());
        details.put("carrierOrgName", p.carrierOrgName());
        details.put("vehicleOrContainerNo", p.vehicleOrContainerNo());
        details.put("originSiteId", p.originSiteId());
        details.put("originSiteName", p.originSiteName());
        details.put("destinationSiteId", p.destinationSiteId());
        details.put("destinationSiteName", p.destinationSiteName());
        details.put("occurredAtBasis", occurredAtBasis);
        return details;
    }

    private void insertShipmentEvent(
            ShipmentProjection p,
            Long batchId,
            TraceEventType type,
            LocalDateTime occurredAtUtc,
            Long siteId,
            String idempotencyKey,
            String summary,
            Map<String, Object> details,
            Long operatorId,
            LocalDateTime recordedAtUtc
    ) {
        TraceEvent event = new TraceEvent();
        event.setBatchId(batchId);
        event.setOrgId(p.senderOrgId());
        event.setSiteId(siteId);
        event.setEventType(type.name());
        event.setOccurredAt(occurredAtUtc);
        event.setRecordedAt(recordedAtUtc);
        event.setOperatorId(operatorId);
        // 承运商在页面人工确认装载 / 到达，沿用既有 MANUAL 语义
        event.setDataSource(DataSource.MANUAL.name());
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(idempotencyKey);
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        event.setDetailsJson(objectMapper.writeValueAsString(details));
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(recordedAtUtc);
        event.setCreatedBy(operatorId);
        event.setUpdatedAt(recordedAtUtc);
        event.setUpdatedBy(operatorId);

        try {
            traceEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SHIPMENT_EVENT_CONFLICT",
                    "运输追溯事件冲突",
                    "运输任务 " + p.shipmentNo() + " 对批次 " + batchId + " 的 " + type.name()
                            + " 追溯事件身份已被占用，运输状态变更已回滚"
            );
        }
    }

    /**
     * 终端销售成功提交时，在调用方事务内为该笔 Sale 自动投影唯一一条 SALE 追溯事件。
     * <p>
     * 唯一可靠触发源：Sale 成功提交（统一业务契约 v1.1 §11）。SALE 事件不承担数量扣减（§15 第 4 条），
     * 数量台账是 {@code sale} 表。事件记录组织为销售 RETAILER，场所为销售门店，业务发生时间与 Sale 完全一致；
     * 幂等身份由 {@link #saleEventIdempotencyKey(Long)} 决定，并由唯一约束
     * {@code uk_trace_event_org_idempotency} 保证每笔 Sale 至多一条 SALE；人工接口拒绝 {@code SYS:} 前缀与 SALE 类型。
     * </p>
     *
     * @param projection    销售投影上下文
     * @param operatorId    提交销售的操作人 ID
     * @param recordedAtUtc 系统登记时间 (UTC)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSaleEvent(SaleProjection projection, Long operatorId, LocalDateTime recordedAtUtc) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceObjectType", "SALE");
        details.put("sourceObjectId", projection.saleId());
        details.put("traceBatchNo", projection.traceBatchNo());
        details.put("quantity", projection.quantity().toPlainString());
        details.put("unitCode", projection.unitCode());
        details.put("siteId", projection.siteId());
        details.put("siteName", projection.siteName());
        details.put("remainingAfter", projection.remainingAfter().toPlainString());
        details.put("soldOut", projection.soldOut());
        details.put("occurredAtBasis", "SALE_OCCURRED");

        String summary = "终端销售 " + plain(projection.quantity()) + " " + projection.unitCode() + "：" + projection.siteName();

        TraceEvent event = new TraceEvent();
        event.setBatchId(projection.batchId());
        event.setOrgId(projection.orgId());
        event.setSiteId(projection.siteId());
        event.setEventType(TraceEventType.SALE.name());
        event.setOccurredAt(projection.occurredAtUtc());
        event.setRecordedAt(recordedAtUtc);
        event.setOperatorId(operatorId);
        // 门店操作员在页面人工登记销售，沿用既有 MANUAL 语义
        event.setDataSource(DataSource.MANUAL.name());
        event.setStatus(TraceEventStatus.SUBMITTED.name());
        event.setIdempotencyKey(saleEventIdempotencyKey(projection.saleId()));
        event.setCorrectsEventId(null);
        event.setCorrectionReason(null);
        event.setSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        event.setDetailsJson(objectMapper.writeValueAsString(details));
        event.setVersion(0L);
        event.setIsDeleted(0);
        event.setCreatedAt(recordedAtUtc);
        event.setCreatedBy(operatorId);
        event.setUpdatedAt(recordedAtUtc);
        event.setUpdatedBy(operatorId);

        try {
            traceEventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SALE_EVENT_CONFLICT",
                    "销售追溯事件冲突",
                    "终端销售 " + projection.saleId() + " 的 SALE 追溯事件身份已被占用，销售提交已回滚"
            );
        }
    }

    /**
     * SALE 事件的服务端幂等身份（保留前缀 {@code SYS:}，人工接口不可使用）：一笔 Sale 恰好一条。
     */
    public static String saleEventIdempotencyKey(Long saleId) {
        return RESERVED_IDEMPOTENCY_PREFIX + "SALE:SALE:" + saleId;
    }

    /**
     * SALE 事件投影所需的终端销售结构化事实快照。
     *
     * @param saleId         销售 ID
     * @param batchId        销售批次 ID
     * @param traceBatchNo   销售批次追溯批次号
     * @param orgId          销售组织（当前责任 RETAILER）
     * @param siteId         销售门店场所 ID
     * @param siteName       销售门店名称
     * @param quantity       销售数量
     * @param unitCode       计量单位
     * @param occurredAtUtc  销售业务发生时间 (UTC，与 sale.occurred_at 一致)
     * @param remainingAfter 本次销售后派生剩余量
     * @param soldOut        本次销售是否售罄并关闭批次
     */
    public record SaleProjection(
            Long saleId,
            Long batchId,
            String traceBatchNo,
            Long orgId,
            Long siteId,
            String siteName,
            BigDecimal quantity,
            String unitCode,
            LocalDateTime occurredAtUtc,
            BigDecimal remainingAfter,
            boolean soldOut
    ) {
    }
}
