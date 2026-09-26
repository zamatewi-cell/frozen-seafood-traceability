package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.RuleStageCode;
import com.example.traceability.masterdata.domain.TemperatureRuleStageMatch;
import com.example.traceability.masterdata.domain.TemperatureUnit;
import com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper;
import com.example.traceability.quality.domain.TemperatureEvaluation;
import com.example.traceability.quality.domain.TemperatureRecord;
import com.example.traceability.quality.dto.ShipmentTemperatureRecordRequest;
import com.example.traceability.quality.dto.TemperatureRecordResponse;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.domain.DataSource;
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shipment 在途温度记录应用服务（Phase B PB2；统一业务契约 v1.1 §2.9 / §10.1 / §10.2 步骤 1 / §13 步骤 1 / §14）。
 * <p>
 * 运输任务指定的承运组织操作员在运输途中（IN_TRANSIT）逐条登记温度测量：记录绑定运输任务而不是复制到各个批次；
 * 服务端按装载批次的产品、TRANSPORT 环节与测量业务时间匹配当时生效的已发布规则版本，得出<b>单点</b>判定
 * NORMAL / HIGH / LOW，或在没有唯一适用规则时记为 MISSING_CONTEXT，并把规则环节、上下限与允许越界时长快照随记录一起固定、
 * 之后不追溯改写（ADR-006）。单点越界不等于持续超温：本服务不判定持续超温、不创建 Alert、不调用风险核心、
 * 不写批次风险状态、不生成 TraceEvent，也不修改运输任务、交接或批次的任何字段（运输任务版本号不变）。
 * </p>
 * <p>
 * 锁顺序：幂等预读（非锁定）→ 运输任务行锁（FOR UPDATE）→ 锁后幂等复读 → 插入温度记录（唯一索引与外键检查的锁只在
 * 取得运输任务行锁之后获取）。确认到达同样先锁运输任务行、再以当前读读取最新测量时间，因此不存在
 * temperature_record → shipment 的反向锁边；本服务从不锁交接、批次或规则表（规则、交接与批次产品均为非锁定读：
 * IN_TRANSIT 后装载清单冻结，批次产品不可变，已发布规则不可修改）。
 * 唯一的残余等待环是 InnoDB 自身的同键插入模式（三个事务插入同一 (org_id, idempotency_key) 且先插入者回滚），
 * 数据库回滚其中一个事务，本服务把它映射为可重试的 409。
 * </p>
 * <p>
 * 隔离级别 READ COMMITTED：持锁后的幂等复读必须看到等待运输任务行锁期间已提交的同键记录。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class ShipmentTemperatureService {

    static final String AUDIT_ACTION = "TEMPERATURE_RECORD";
    static final String AUDIT_OBJECT_TYPE = "SHIPMENT";
    /** 服务端生成的系统幂等键前缀，人工请求不得使用。 */
    static final String RESERVED_SYSTEM_KEY_PREFIX = "SYS:";
    static final BigDecimal MIN_TEMPERATURE = new BigDecimal("-80.00");
    static final BigDecimal MAX_TEMPERATURE = new BigDecimal("60.00");
    static final int DEVICE_NO_MAX_LENGTH = 64;
    /** 业务时间允许超前服务端时钟的最大偏差（与运输任务发运 / 到达一致）。 */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    /** PB2 开放的数据来源：IMPORT 为 ADR-006 的 P1，DEVICE 需真实设备接入与身份校验。 */
    static final Set<DataSource> ACCEPTED_SOURCES = EnumSet.of(DataSource.MANUAL, DataSource.SIMULATED);

    private static final String ROLE_OPERATOR = "OPERATOR";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern DEVICE_NO_PATTERN = Pattern.compile("^[A-Za-z0-9._:/#-]{1," + DEVICE_NO_MAX_LENGTH + "}$");
    private static final DateTimeFormatter CANONICAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    /**
     * 规范化后的请求语义：测量时间只在这里规范化一次（UTC、截断到 MySQL DATETIME(6) 的微秒精度），
     * 之后的哈希、业务时间校验、规则匹配、持久化、审计与响应全部使用同一个值。
     */
    record CanonicalReading(LocalDateTime measuredAtUtc, BigDecimal temperature, DataSource dataSource, String deviceNo) {
    }

    private final ShipmentMapper shipmentMapper;
    private final TransferMapper transferMapper;
    private final BatchMapper batchMapper;
    private final TemperatureRecordMapper recordMapper;
    private final TemperatureRuleStageMapper ruleStageMapper;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public ShipmentTemperatureService(
            ShipmentMapper shipmentMapper,
            TransferMapper transferMapper,
            BatchMapper batchMapper,
            TemperatureRecordMapper recordMapper,
            TemperatureRuleStageMapper ruleStageMapper,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.shipmentMapper = Objects.requireNonNull(shipmentMapper, "shipmentMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper 不能为空");
        this.ruleStageMapper = Objects.requireNonNull(ruleStageMapper, "ruleStageMapper 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 承运商登记一条在途温度测量 (POST /api/v1/shipments/{shipmentId}/temperature-records)。
     * <p>
     * 校验顺序：认证 / 平台代办 / OPERATOR → 幂等键 → 请求规范化 → 幂等预读 → 运输任务行锁 → 锁后幂等复读 →
     * 运输任务存在 → 承运组织 → IN_TRANSIT → 测量时间窗口 → 规则匹配与单点判定 → 插入 → 审计。
     * 幂等重放先于一切可变状态校验：同组织同键同语义的重试（包括运输任务之后已到达）返回原记录，不产生新写入。
     * </p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemperatureRecordResponse record(Long shipmentId, ShipmentTemperatureRecordRequest req, String idempotencyKey,
                                            TraceSecurityPrincipal principal) {
        checkWriteAccess(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        CanonicalReading reading = canonicalize(req);
        Long orgId = principal.getOrgId();
        String requestHash = computeRequestHash(shipmentId, reading);

        // 1. 幂等预读（不加锁）
        TemperatureRecord existing = recordMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        // 2. 锁定运输任务行（与发运 / 到达 / 装载清单变更串行化），锁后复读幂等键
        Shipment shipment = shipmentMapper.selectByIdForUpdate(shipmentId);
        TemperatureRecord afterLock = recordMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayOrConflict(afterLock, requestHash);
        }
        if (shipment == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + shipmentId + " 的运输任务");
        }

        // 3. 只有运输任务指定的承运组织可以登记在途温度（契约 §14）
        if (!Objects.equals(shipment.getCarrierOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "仅运输任务指定的承运组织可以登记在途温度");
        }
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHIPMENT_NOT_IN_TRANSIT", "运输任务不在途",
                    "仅运输途中（IN_TRANSIT）的运输任务可以登记在途温度，当前状态为: " + shipment.getStatus());
        }

        // 4. 测量时间窗口：不早于装载发运时间，不晚于当前时间（允许 5 分钟时钟偏差）
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (reading.measuredAtUtc().isBefore(shipment.getLoadedAt())) {
            throw invalidBusinessTime("测量时间 measuredAt 不能早于装载发运时间 ("
                    + shipment.getLoadedAt().atOffset(ZoneOffset.UTC) + ")");
        }
        if (reading.measuredAtUtc().isAfter(nowUtc.plus(MAX_FUTURE_SKEW))) {
            throw invalidBusinessTime("测量时间 measuredAt 不能晚于当前时间");
        }

        // 5. 规则匹配（产品 + TRANSPORT + 测量业务时间）与单点判定
        TemperatureRuleStageMatch match = resolveTransportRule(shipmentId, reading.measuredAtUtc());
        TemperatureEvaluation evaluation = match == null
                ? TemperatureEvaluation.MISSING_CONTEXT
                : TemperatureEvaluation.classify(reading.temperature(), match.getLowerLimit(), match.getUpperLimit());

        TemperatureRecord record = new TemperatureRecord();
        record.setShipmentId(shipment.getId());
        record.setOrgId(orgId);
        record.setActorUserId(principal.getUserId());
        record.setStageCode(RuleStageCode.TRANSPORT.name());
        record.setMeasuredAt(reading.measuredAtUtc());
        record.setRecordedAt(nowUtc);
        record.setTemperature(reading.temperature());
        record.setUnitCode(TemperatureUnit.CELSIUS.name());
        record.setDataSource(reading.dataSource().name());
        record.setDeviceNo(reading.deviceNo());
        record.setEvaluation(evaluation.name());
        record.setIdempotencyKey(cleanKey);
        record.setRequestHash(requestHash);
        if (match != null) {
            // 判定依据快照（持久化）：匹配环节、上下限与允许越界时长，登记后固定，不随规则环节后续变化
            record.setRuleStageId(match.getRuleStageId());
            record.setRuleLowerLimit(match.getLowerLimit());
            record.setRuleUpperLimit(match.getUpperLimit());
            record.setRuleAllowedDurationSeconds(match.getAllowedDurationSeconds());
            // 来源追溯展示（非本表列）
            record.setRuleId(match.getRuleId());
            record.setRuleName(match.getRuleName());
            record.setRuleVersionNo(match.getRuleVersionNo());
        }

        // 6. 追加记录（唯一索引上的锁只在运输任务行锁之后获取）
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 同组织同键被另一运输任务上的并发请求抢先提交（同一运输任务的同键请求已被行锁串行化并在锁后复读识别）
            TemperatureRecord dup = recordMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanKey);
            if (dup != null) {
                return replayOrConflict(dup, requestHash);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // InnoDB 三方同键插入且先插入者回滚时，两个等待者在唯一索引上互相等待，其中一方被选为死锁牺牲者，
            // 其整个事务（含运输任务行锁）已被数据库回滚：映射为可重试冲突而不是 500；使用同一幂等键重试即得到确定结果。
            throw new BusinessException(HttpStatus.CONFLICT, "TEMPERATURE_RECORD_CONCURRENT_CONFLICT", "温度记录并发冲突",
                    "同一幂等键的并发请求发生冲突，本次温度登记已回滚，请使用相同幂等键重试");
        }

        // 7. 审计（与记录同一事务）
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("temperatureRecordId", record.getId());
        summary.put("measuredAt", reading.measuredAtUtc().atOffset(ZoneOffset.UTC).toString());
        summary.put("temperature", reading.temperature().toPlainString());
        summary.put("dataSource", record.getDataSource());
        summary.put("evaluation", record.getEvaluation());
        summary.put("ruleStageId", record.getRuleStageId());
        auditService.recordAudit(principal.getUserId(), orgId, AUDIT_ACTION, AUDIT_OBJECT_TYPE, shipment.getId(),
                nowUtc, "SUCCESS", serializeSummary(summary));

        return TemperatureRecordResponse.fromEntity(record);
    }

    /**
     * 查询运输任务的全部在途温度记录（按测量时间、主键稳定排序）：发送方、承运方、接收方（任意角色）与平台只读角色。
     * <p>
     * 运输任务参与方在创建后不再变化，授权与数据仍在同一只读一致性快照内读取。
     * </p>
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TemperatureRecordResponse> listRecords(Long shipmentId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Shipment shipment = isPlatformScope(principal)
                ? shipmentMapper.selectById(shipmentId)
                : shipmentMapper.selectByIdAndPartyScope(shipmentId, principal.getOrgId());
        if (shipment == null || Objects.equals(shipment.getIsDeleted(), 1)) {
            if (shipmentMapper.existsByIdIgnoreTenant(shipmentId) == 0) {
                throw new ResourceNotFoundException("未找到 ID 为 " + shipmentId + " 的运输任务");
            }
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "仅运输任务的发货方、承运方与接收方可以查看在途温度记录");
        }
        return recordMapper.selectByShipmentId(shipmentId).stream().map(TemperatureRecordResponse::fromEntity).toList();
    }

    // =========================================================================
    // 规则匹配
    // =========================================================================

    /**
     * 装载清单只有一种产品时，按该产品、TRANSPORT 环节与测量时间匹配唯一已发布规则环节；
     * 没有适用规则或装载清单含多种产品（Demo MVP 在装载时已禁止，只可能来自限制之前的历史数据）时返回 null（MISSING_CONTEXT），
     * 从不猜测。多于一条匹配说明规则区间重叠，属于数据完整性错误，失败关闭而不是任选其一。
     */
    private TemperatureRuleStageMatch resolveTransportRule(Long shipmentId, LocalDateTime measuredAtUtc) {
        List<Transfer> manifest = transferMapper.selectByShipmentId(shipmentId);
        Set<Long> batchIds = manifest.stream().map(Transfer::getBatchId).collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return null;
        }
        Set<Long> productIds = batchMapper.selectByIdsIgnoreTenant(batchIds).stream()
                .map(Batch::getProductId)
                .collect(Collectors.toSet());
        if (productIds.size() != 1) {
            return null;
        }
        List<TemperatureRuleStageMatch> matches = ruleStageMapper.selectApplicableStages(
                productIds.iterator().next(), RuleStageCode.TRANSPORT.name(), measuredAtUtc);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "TEMPERATURE_RULE_AMBIGUOUS", "温控规则不唯一",
                    "测量时间同时命中多个已发布运输温控规则版本，已拒绝登记，请联系平台管理员核对规则生效区间");
        }
        return matches.get(0);
    }

    // =========================================================================
    // 校验与辅助
    // =========================================================================

    private TemperatureRecordResponse replayOrConflict(TemperatureRecord existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的温度登记请求");
        }
        return TemperatureRecordResponse.fromEntity(existing);
    }

    /**
     * 写权限：未认证 401；平台 / 系统管理员不可代办 403 ADMIN_RESTRICTED；必须是 OPERATOR（403 ROLE_NOT_ALLOWED）。
     * 承运组织在运输任务行锁下另行校验（403 ORG_SCOPE_DENIED）。
     */
    private static void checkWriteAccess(TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办承运企业的在途温度登记");
        }
        if (!principal.getRoles().contains(ROLE_OPERATOR)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED", "角色权限不足",
                    "仅承运企业操作员 (OPERATOR) 可以登记在途温度");
        }
    }

    private static void requireAuthenticated(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
    }

    private static String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "幂等键不合法",
                    "Idempotency-Key 请求头必填，长度 16 到 128 个字符，只能包含字母、数字与 . _ : -");
        }
        String clean = idempotencyKey.trim();
        if (clean.toUpperCase(Locale.ROOT).startsWith(RESERVED_SYSTEM_KEY_PREFIX)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "幂等键不合法",
                    "Idempotency-Key 不能以保留前缀 SYS: 开头（系统生成键专用）");
        }
        return clean;
    }

    /**
     * 请求规范化：测量时间 → UTC 微秒精度；温度 → 两位小数（超过两位小数拒绝，不做舍入）；来源 → 枚举；设备编号 → 去空白。
     */
    static CanonicalReading canonicalize(ShipmentTemperatureRecordRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("温度登记请求只接受 measuredAt、temperature、dataSource、deviceNo，不接受以下字段"
                    + "（由服务端决定或未在契约中声明）: " + String.join(", ", req.unknownFields().keySet()));
        }
        if (req.measuredAt() == null) {
            throw badRequest("测量时间 measuredAt 不能为空");
        }
        LocalDateTime measuredAtUtc = req.measuredAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                .truncatedTo(ChronoUnit.MICROS);

        if (req.temperature() == null) {
            throw badRequest("温度 temperature 不能为空");
        }
        BigDecimal stripped = req.temperature().stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw badRequest("温度 temperature 最多保留两位小数");
        }
        BigDecimal temperature = req.temperature().setScale(2, RoundingMode.UNNECESSARY);
        if (temperature.compareTo(MIN_TEMPERATURE) < 0 || temperature.compareTo(MAX_TEMPERATURE) > 0) {
            throw badRequest("温度 temperature 必须在 " + MIN_TEMPERATURE.toPlainString() + " 到 "
                    + MAX_TEMPERATURE.toPlainString() + " 摄氏度之间");
        }

        if (req.dataSource() == null || !DataSource.isValid(req.dataSource())) {
            throw badRequest("数据来源 dataSource 只能为 MANUAL 或 SIMULATED");
        }
        DataSource dataSource = DataSource.fromCode(req.dataSource());
        if (!ACCEPTED_SOURCES.contains(dataSource)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPERATURE_SOURCE_NOT_SUPPORTED", "数据来源暂不支持",
                    "在途温度登记只接受人工登记 (MANUAL) 与教学模拟数据 (SIMULATED)；批量导入 (IMPORT) 尚未开放，"
                            + "设备来源 (DEVICE) 需真实设备接入与身份校验，本系统未接入");
        }

        String deviceNo = req.deviceNo() == null ? null : req.deviceNo().trim();
        if (deviceNo != null && deviceNo.isEmpty()) {
            deviceNo = null;
        }
        if (deviceNo != null && !DEVICE_NO_PATTERN.matcher(deviceNo).matches()) {
            throw badRequest("设备编号 deviceNo 最长 " + DEVICE_NO_MAX_LENGTH + " 个字符，只能包含字母、数字与 . _ : / # -");
        }
        return new CanonicalReading(measuredAtUtc, temperature, dataSource, deviceNo);
    }

    /**
     * 规范化请求语义哈希：运输任务、UTC 微秒测量时间、两位小数温度、来源与设备编号。
     */
    static String computeRequestHash(Long shipmentId, CanonicalReading reading) {
        String canonical = String.join("\u001F",
                "SHIPMENT_TEMPERATURE_RECORD", "v1",
                String.valueOf(shipmentId),
                reading.measuredAtUtc().format(CANONICAL_TIME),
                reading.temperature().toPlainString(),
                reading.dataSource().name(),
                reading.deviceNo() == null ? "" : reading.deviceNo());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    private String serializeSummary(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }

    private static boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private static BusinessException invalidBusinessTime(String detail) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BUSINESS_TIME", "业务时间不合法", detail);
    }

    private static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
