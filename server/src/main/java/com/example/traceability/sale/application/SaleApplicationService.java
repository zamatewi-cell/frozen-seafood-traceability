package com.example.traceability.sale.application;

import com.example.traceability.batch.application.BatchQuantityService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.sale.domain.Sale;
import com.example.traceability.sale.domain.SaleStatus;
import com.example.traceability.sale.dto.SaleCreateRequest;
import com.example.traceability.sale.dto.SaleResponse;
import com.example.traceability.sale.mapper.SaleMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.application.TraceEventApplicationService.SaleProjection;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 终端销售应用服务（统一业务契约 v1.1 §2.13 / §5 / §9.1 / §11）。
 * <p>
 * Sale 只表示当前责任 RETAILER 在本组织启用的 {@code Site(STORE)} 面向供应链外部消费者的终端数量出库：
 * <ul>
 *   <li>一条 Sale 只对应一个 Batch；不改变批次当前责任组织、风险状态与身份；</li>
 *   <li>允许多次部分销售；超卖由服务端在批次行锁下以派生剩余量拒绝；剩余量恰好归零时批次原子 CLOSED；</li>
 *   <li>第一次有效 Sale 写入批次 {@code first_sale_id}，此后永久禁止 Transfer 与 BatchOperation；</li>
 *   <li>每笔成功 Sale 在同一事务内自动生成且仅生成一条 SALE 追溯事件；</li>
 *   <li>幂等：同组织同键同语义重放原 Sale（先于一切可变状态校验，售罄关闭后重试仍返回原结果）；语义不同 409。</li>
 * </ul>
 * </p>
 * <p>
 * 并发与锁顺序：只锁一行 batch（FOR UPDATE），从不锁 shipment / transfer / batch_operation，
 * 与既有 shipment → transfer → batch 及 batch_operation → batch 顺序不成环。
 * 隔离级别 READ COMMITTED：取得批次行锁后的未结束交接、INPUT 消耗与已售数量读取必须看到等待期间已提交的数据。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class SaleApplicationService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final DateTimeFormatter HASH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final String SITE_TYPE_STORE = "STORE";
    private static final String ORG_TYPE_RETAILER = "RETAILER";

    private final SaleMapper saleMapper;
    private final BatchMapper batchMapper;
    private final SiteMapper siteMapper;
    private final TransferMapper transferMapper;
    private final BatchQuantityService batchQuantityService;
    private final TraceEventApplicationService traceEventService;

    public SaleApplicationService(
            SaleMapper saleMapper,
            BatchMapper batchMapper,
            SiteMapper siteMapper,
            TransferMapper transferMapper,
            BatchQuantityService batchQuantityService,
            TraceEventApplicationService traceEventService
    ) {
        this.saleMapper = Objects.requireNonNull(saleMapper, "saleMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.siteMapper = Objects.requireNonNull(siteMapper, "siteMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.batchQuantityService = Objects.requireNonNull(batchQuantityService, "batchQuantityService 不能为空");
        this.traceEventService = Objects.requireNonNull(traceEventService, "traceEventService 不能为空");
    }

    /**
     * 提交终端销售。
     * <p>
     * 校验顺序：角色 / RETAILER / 请求形状 → 幂等预读 → 批次行锁 → 锁后幂等复读 → 批次存在 → 当前责任组织
     * （越权调用方在任何场所探测之前即得到 403）→ ACTIVE+NORMAL → 未被批次操作消耗 → 本组织启用 STORE →
     * 无未结束交接 → 派生剩余量 → 超卖 → 写入 Sale → 回写批次标记 / 状态 / 版本 → SALE 事件。
     * </p>
     *
     * @param batchId        批次 ID
     * @param req            销售请求
     * @param idempotencyKey 客户端幂等键 (16..128)
     * @param principal      当前认证主体
     * @return 创建或重放的销售记录
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SaleResponse createSale(Long batchId, SaleCreateRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkWriteAccess(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        validateRequest(req);
        Long orgId = principal.getOrgId();

        // 业务时间只规范化一次（UTC、微秒，与 DATETIME(6) 一致），哈希、落库、响应与 SALE 事件共用同一值
        LocalDateTime occurredAtUtc = normalizeOccurredAt(req);
        String requestHash = computeRequestHash(batchId, req.siteId(), req.quantity(), occurredAtUtc);

        // 1. 幂等预读：已成功请求的重放先于一切可变状态校验（售罄关闭后重试仍返回原 Sale）
        Sale existing = saleMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        // 2. 锁定批次行（唯一串行化点），锁后复读幂等键（同键并发的后到者在此识别先到者结果）
        Batch batch = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
        Sale afterLock = saleMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayOrConflict(afterLock, requestHash);
        }
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        // 3. 当前责任组织：越权调用方不得借销售接口探测任意场所
        if (!Objects.equals(batch.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有批次当前责任组织可以提交终端销售");
        }

        // 4. 批次状态：ACTIVE + NORMAL（CLOSED / FROZEN / RECALLED / DRAFT 一律拒绝）
        if (!BatchFlowStatus.ACTIVE.name().equals(batch.getFlowStatus())
                || !BatchRiskStatus.NORMAL.name().equals(batch.getRiskStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED", "批次状态不允许销售",
                    "批次当前流转或风险状态不允许终端销售 (flowStatus=" + batch.getFlowStatus() + ", riskStatus="
                            + batch.getRiskStatus() + ")，仅 ACTIVE 且 NORMAL 状态批次允许销售");
        }

        // 5. 未被批次操作消耗：既无规范全量消耗标记，也无任何历史已提交 INPUT 用量（拒绝 v1.1 之前的部分投入状态）
        if (batch.getConsumedByOperationId() != null || batchQuantityService.operationConsumed(batch.getId()).signum() != 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED", "批次已被物料操作消耗",
                    "批次 " + batch.getTraceBatchNo() + " 已被已提交的批次操作作为投入(INPUT)使用，不能再终端销售");
        }

        // 6. 销售场所：本组织启用的 STORE
        Site store = enforceStoreSite(req.siteId(), orgId);

        // 7. 未结束交接（DRAFT / PENDING）存在时禁止销售
        if (transferMapper.countActiveTransfersByBatchId(batch.getId()) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_TRANSFER_OPEN", "批次存在未结束交接",
                    "批次 " + batch.getTraceBatchNo() + " 存在草稿(DRAFT)或待接收(PENDING)交接，请先删除草稿或等待交接结束");
        }

        // 8. 派生剩余量与超卖
        BigDecimal remaining = batchQuantityService.remainingOf(batch);
        if (req.quantity().compareTo(remaining) > 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SALE_QUANTITY_EXCEEDS_REMAINING", "销售数量超过剩余量",
                    "本次销售 " + plain(req.quantity()) + " " + batch.getUnitCode() + " 超过批次当前剩余 "
                            + plain(remaining) + " " + batch.getUnitCode() + "，禁止超卖");
        }

        // 9. 写入 Sale 台账
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Sale sale = new Sale();
        sale.setOrgId(orgId);
        sale.setBatchId(batch.getId());
        sale.setSiteId(store.getId());
        sale.setQuantity(req.quantity());
        sale.setUnitCode(batch.getUnitCode());
        sale.setOccurredAt(occurredAtUtc);
        sale.setStatus(SaleStatus.SUBMITTED.name());
        sale.setIdempotencyKey(cleanKey);
        sale.setRequestHash(requestHash);
        sale.setCreatedBy(principal.getUserId());
        sale.setCreatedAt(nowUtc);
        try {
            saleMapper.insert(sale);
        } catch (DuplicateKeyException e) {
            Sale dup = saleMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanKey);
            if (dup != null) {
                return replayOrConflict(dup, requestHash);
            }
            throw e;
        }

        // 10. 回写批次：首次销售标记（写一次）、售罄关闭、版本 +1；任一谓词不命中即整体回滚
        BigDecimal remainingAfter = remaining.subtract(req.quantity());
        boolean soldOut = remainingAfter.signum() == 0;
        if (batchMapper.applySale(batch.getId(), orgId, sale.getId(), soldOut, nowUtc, principal.getUserId()) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT", "批次并发冲突",
                    "批次 " + batch.getTraceBatchNo() + " 状态已被并发修改，本次销售已回滚");
        }

        // 11. 自动 SALE 追溯事件（一笔 Sale 恰好一条）
        traceEventService.appendSaleEvent(new SaleProjection(
                sale.getId(),
                batch.getId(),
                batch.getTraceBatchNo(),
                orgId,
                store.getId(),
                store.getName(),
                req.quantity(),
                batch.getUnitCode(),
                occurredAtUtc,
                remainingAfter,
                soldOut
        ), principal.getUserId(), nowUtc);

        return SaleResponse.fromEntity(sale, store.getName());
    }

    /**
     * 查询批次终端销售记录：批次当前责任组织与平台只读角色可读，其余组织 403。
     * <p>
     * Sale 不改变责任组织且首次销售后禁止交接，因此销售组织始终是批次当前责任组织。
     * </p>
     */
    public List<SaleResponse> listSales(Long batchId, TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        if (!isPlatformScope(principal) && !Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "无权查看其他组织批次的终端销售记录");
        }
        Map<Long, String> siteNames = new HashMap<>();
        return saleMapper.selectByBatchId(batchId).stream()
                .map(s -> SaleResponse.fromEntity(s, siteNames.computeIfAbsent(s.getSiteId(), this::siteName)))
                .toList();
    }

    // =========================================================================
    // 内部校验
    // =========================================================================

    private SaleResponse replayOrConflict(Sale existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的终端销售请求");
        }
        return SaleResponse.fromEntity(existing, siteName(existing.getSiteId()));
    }

    private String siteName(Long siteId) {
        Site site = siteMapper.selectByIdIgnoreTenant(siteId);
        return site != null ? site.getName() : null;
    }

    /**
     * 写权限：平台 / 系统管理员不可代办；必须是 OPERATOR；组织类型必须为 RETAILER（契约 §9.1）。
     */
    private void checkWriteAccess(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办企业终端销售");
        }
        if (!principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                    "终端销售仅限企业操作员（OPERATOR）提交");
        }
        if (!ORG_TYPE_RETAILER.equals(principal.getOrgType())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_TYPE_NOT_ALLOWED", "组织类型不允许当前操作",
                    "终端销售仅限零售企业（RETAILER）提交；企业之间的流转请使用交接");
        }
    }

    private void validateRequest(SaleCreateRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("终端销售请求不接受以下字段（由服务端决定或未在契约中声明）: "
                    + String.join(", ", req.unknownFields().keySet()));
        }
        if (req.siteId() == null || req.siteId() <= 0) {
            throw badRequest("销售门店 siteId 必填且必须为正整数");
        }
        if (req.quantity() == null || req.quantity().signum() <= 0) {
            throw badRequest("销售数量 quantity 必须大于 0");
        }
        if (req.quantity().stripTrailingZeros().scale() > 3) {
            throw badRequest("销售数量 quantity 最多 3 位小数");
        }
        if (req.occurredAt() == null) {
            throw badRequest("销售发生时间 occurredAt 不能为空");
        }
    }

    private LocalDateTime normalizeOccurredAt(SaleCreateRequest req) {
        LocalDateTime occurredAtUtc = req.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                .truncatedTo(ChronoUnit.MICROS);
        if (occurredAtUtc.isAfter(LocalDateTime.now(ZoneOffset.UTC).plus(MAX_FUTURE_SKEW))) {
            throw badRequest("销售发生时间 occurredAt 不能晚于当前时间");
        }
        return occurredAtUtc;
    }

    private String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw badRequest("Idempotency-Key 请求头必填，长度 16 到 128 个字符，只能包含字母、数字与 . _ : -");
        }
        return idempotencyKey.trim();
    }

    /**
     * 本组织启用的 STORE 场所（与冷库出入库场所规则同构）：404 → 403 他组织 → 422 未启用 → 422 非门店。
     */
    private Site enforceStoreSite(Long siteId, Long orgId) {
        Site site = siteMapper.selectByIdIgnoreTenant(siteId);
        if (site == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + siteId + " 的场所");
        }
        if (!Objects.equals(site.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "终端销售只能在本组织自有门店进行，无权引用其他组织的场所");
        }
        if (!"ACTIVE".equals(site.getStatus())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SITE_NOT_ACTIVE", "场所未启用",
                    "指定场所未处于启用状态，当前状态为: " + site.getStatus());
        }
        if (!SITE_TYPE_STORE.equals(site.getSiteType())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SALE_SITE_TYPE_INVALID", "场所不是门店",
                    "终端销售只能在类型为 STORE 的门店场所进行，当前场所类型为: " + site.getSiteType());
        }
        return site;
    }

    /**
     * 规范化请求语义哈希：批次、门店、数值等价的数量（消除尾随零歧义）与已规范化到微秒的 UTC 业务时间。
     */
    static String computeRequestHash(Long batchId, Long siteId, BigDecimal quantity, LocalDateTime occurredAtUtc) {
        String canonical = String.join("\u001F",
                "SALE",
                String.valueOf(batchId),
                String.valueOf(siteId),
                quantity.stripTrailingZeros().toPlainString(),
                occurredAtUtc.format(HASH_TIME_FORMAT));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    private boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
