package com.example.traceability.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.order.domain.OrderProgressNote;
import com.example.traceability.order.domain.OrderBatchAllocation;
import com.example.traceability.order.domain.OrderStatus;
import com.example.traceability.order.domain.PurchaseOrder;
import com.example.traceability.order.domain.PurchaseOrderItem;
import com.example.traceability.order.domain.SalesOrder;
import com.example.traceability.order.domain.SalesOrderItem;
import com.example.traceability.order.dto.OrderDecisionRequest;
import com.example.traceability.order.dto.OrderStatusUpdateRequest;
import com.example.traceability.order.dto.PurchaseOrderCreateRequest;
import com.example.traceability.order.dto.PurchaseOrderResponse;
import com.example.traceability.order.dto.PurchaseOrderResponse.PurchaseItem;
import com.example.traceability.order.dto.SalesOrderCreateRequest;
import com.example.traceability.order.dto.SalesOrderResponse;
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import com.example.traceability.order.dto.SalesOrderResponse.SalesItem;
import com.example.traceability.order.mapper.OrderBatchAllocationMapper;
import com.example.traceability.order.mapper.OrderProgressNoteMapper;
import com.example.traceability.order.mapper.PurchaseOrderItemMapper;
import com.example.traceability.order.mapper.PurchaseOrderMapper;
import com.example.traceability.order.mapper.SalesOrderItemMapper;
import com.example.traceability.order.mapper.SalesOrderMapper;
import com.example.traceability.quality.domain.QualityInspection;
import com.example.traceability.quality.mapper.QualityInspectionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单应用服务：承接多角色 B2B 采购进货单与 B2C 销售/客户订单的统一编排。
 * <p>所有读写均按当前登录组织做数据隔离。</p>
 */
@Service
public class OrderApplicationService {

    private static final Set<String> PURCHASE_NEXT = Set.of(
            OrderStatus.PURCHASE_CONFIRMED,
            OrderStatus.PURCHASE_PROCESSING,
            OrderStatus.PURCHASE_SHIPPED,
            OrderStatus.PURCHASE_RECEIVED,
            OrderStatus.PURCHASE_CANCELLED);

    private static final Set<String> SALES_NEXT = Set.of(
            OrderStatus.SALES_CONFIRMED,
            OrderStatus.SALES_PROCESSING,
            OrderStatus.SALES_SHIPPED,
            OrderStatus.SALES_DELIVERED,
            OrderStatus.SALES_CANCELLED);

    private final PurchaseOrderMapper purchaseOrderMapper;
    private final PurchaseOrderItemMapper purchaseOrderItemMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final ProductMapper productMapper;
    private final PublicTraceApplicationService publicTraceService;
    private final OrderProgressNoteMapper orderProgressNoteMapper;
    private final OrderBatchAllocationMapper orderBatchAllocationMapper;
    private final QualityInspectionMapper qualityInspectionMapper;
    private final OrganizationMapper organizationMapper;
    private final com.example.traceability.batch.mapper.BatchMapper batchMapper;
    private final Map<Long, String> orgCache = new java.util.concurrent.ConcurrentHashMap<>();

    public OrderApplicationService(
            PurchaseOrderMapper purchaseOrderMapper,
            PurchaseOrderItemMapper purchaseOrderItemMapper,
            SalesOrderMapper salesOrderMapper,
            SalesOrderItemMapper salesOrderItemMapper,
            ProductMapper productMapper,
            PublicTraceApplicationService publicTraceService,
            OrderProgressNoteMapper orderProgressNoteMapper,
            OrderBatchAllocationMapper orderBatchAllocationMapper,
            QualityInspectionMapper qualityInspectionMapper,
            OrganizationMapper organizationMapper,
            com.example.traceability.batch.mapper.BatchMapper batchMapper) {
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.purchaseOrderItemMapper = purchaseOrderItemMapper;
        this.salesOrderMapper = salesOrderMapper;
        this.salesOrderItemMapper = salesOrderItemMapper;
        this.productMapper = productMapper;
        this.publicTraceService = publicTraceService;
        this.orderProgressNoteMapper = orderProgressNoteMapper;
        this.orderBatchAllocationMapper = orderBatchAllocationMapper;
        this.qualityInspectionMapper = qualityInspectionMapper;
        this.organizationMapper = organizationMapper;
        this.batchMapper = batchMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderCreateRequest request, TraceSecurityPrincipal principal) {
        Long buyerOrgId = principal.getOrgId();
        if (request.sellerOrgId().equals(buyerOrgId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_INVALID_PARTNER",
                    "非法交易对象", "采购方与供货方不能为同一组织");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNo(nextNo("PO"));
        order.setBuyerOrgId(buyerOrgId);
        order.setSellerOrgId(request.sellerOrgId());
        order.setOrderType(request.orderType() == null ? "MATERIAL" : request.orderType());
        order.setStatus(OrderStatus.PURCHASE_SUBMITTED);
        order.setOrderedAt(now);
        order.setExpectedDeliveryAt(request.expectedDeliveryAt());
        order.setNote(request.note());
        order.setBuyerContactName(request.buyerContactName());
        order.setBuyerContactPhone(request.buyerContactPhone());
        order.setBuyerContactAddress(request.buyerContactAddress());
        order.setCurrencyCode("CNY");
        order.setCreatedBy(principal.getUserId());
        BigDecimal total = BigDecimal.ZERO;
        order.setAmountTotal(total);
        purchaseOrderMapper.insert(order);

        for (PurchaseOrderCreateRequest.PurchaseItem item : request.items()) {
            String unit = item.unitCode() == null ? "kg" : item.unitCode();
            BigDecimal row = item.quantity().multiply(item.unitPrice());
            total = total.add(row);
            PurchaseOrderItem poItem = new PurchaseOrderItem();
            poItem.setOrderId(order.getId());
            poItem.setProductId(item.productId());
            poItem.setQuantity(item.quantity());
            poItem.setUnitCode(unit);
            poItem.setUnitPrice(item.unitPrice());
            poItem.setRowAmount(row);
            poItem.setCreatedBy(principal.getUserId());
            purchaseOrderItemMapper.insert(poItem);
        }
        order.setAmountTotal(total);
        purchaseOrderMapper.updateById(order);
        return toPurchaseResponse(order);
    }

    public List<PurchaseOrderResponse> listPurchaseOrders(TraceSecurityPrincipal principal) {
        return purchaseOrderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getBuyerOrgId, principal.getOrgId())
                        .orderByDesc(PurchaseOrder::getOrderedAt))
                .stream().map(this::toPurchaseResponse).toList();
    }

    public PurchaseOrderResponse getPurchaseOrder(Long orderId, TraceSecurityPrincipal principal) {
        return toPurchaseResponse(requirePurchaseAccess(orderId, principal));
    }

    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse updatePurchaseStatus(Long orderId, OrderStatusUpdateRequest request,
                                                      TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        if (!PURCHASE_NEXT.contains(request.status())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_INVALID_STATUS",
                    "非法状态流转", "目标状态不被采购进货单支持");
        }
        if (OrderStatus.PURCHASE_RECEIVED.equals(order.getStatus())
                || OrderStatus.PURCHASE_CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_ALREADY_TERMINAL",
                    "订单已终结", "已收货或已取消的采购单不能再流转");
        }
        order.setStatus(request.status());
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);
        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 供货方审批接受采购单：仅下游采购单处于待审核(SUBMITTED)、当前组织为供货方时可由其操作员确认。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse approvePurchaseOrder(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireSeller(order, principal);
        requireOperator(principal);
        if (!OrderStatus.PURCHASE_SUBMITTED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "采购单不可审核", "仅待审核(SUBMITTED)状态的采购单可审批，当前状态: " + order.getStatus());
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        order.setStatus(OrderStatus.PURCHASE_CONFIRMED);
        order.setApprovedBy(principal.getUserId());
        order.setApprovedAt(now);
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);
        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 供货方驳回采购单：必填驳回原因；驳回后采购方可见原因。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse rejectPurchaseOrder(Long orderId, OrderDecisionRequest request,
                                                     TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireSeller(order, principal);
        requireOperator(principal);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REJECT_REASON_REQUIRED",
                    "驳回原因缺失", "驳回采购单必须说明原因");
        }
        if (!OrderStatus.PURCHASE_SUBMITTED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "采购单不可驳回", "仅待审核(SUBMITTED)状态的采购单可驳回，当前状态: " + order.getStatus());
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        order.setStatus(OrderStatus.PURCHASE_REJECTED);
        order.setApprovedBy(principal.getUserId());
        order.setApprovedAt(now);
        order.setRejectReason(request.reason().trim());
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);
        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 按单排产：供货方操作员对已确认(CONFIRMED)的采购单生成关联加工批次草稿。
     * <p>批次归属供货方组织，来源描述挂接采购单号，形成"订单->批次->溯源"闭环。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse schedulePurchaseOrder(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireSeller(order, principal);
        requireOperator(principal);
        if (!OrderStatus.PURCHASE_CONFIRMED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "采购单不可排产", "仅已确认(CONFIRMED)状态的采购单可排产，当前状态: " + order.getStatus());
        }
        order.setStatus(OrderStatus.PURCHASE_PROCESSING);
        order.setHandlingStatus("IN_PROGRESS");
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("已接单，开始处理");
        note.setStatusAt(OrderStatus.PURCHASE_PROCESSING);
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse completePurchaseOrderDelivery(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireSeller(order, principal);
        requireOperator(principal);
        if (!OrderStatus.PURCHASE_PROCESSING.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "订单不可交付", "仅处理中(PROCESSING)状态的采购单可完成交付，当前状态: " + order.getStatus());
        }
        BigDecimal totalOrdered = purchaseOrderItemMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, order.getId()))
                .stream().map(PurchaseOrderItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAllocated = orderBatchAllocationMapper.sumAllocatedByOrderId(orderId);
        if (totalAllocated.compareTo(totalOrdered) < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALLOCATION_INSUFFICIENT",
                    "分配量不足", "已分配 " + totalAllocated + " kg，订单需要 " + totalOrdered + " kg，请补充分配后再交付");
        }
        // 环节质检强制:出货方组织需通过对应环节质检(清单全部打钩通过)
        List<OrderBatchAllocation> allocs = orderBatchAllocationMapper.selectByOrderId(orderId);
        for (OrderBatchAllocation alloc : allocs) {
            List<QualityInspection> inspections = qualityInspectionMapper.selectList(
                    new LambdaQueryWrapper<QualityInspection>()
                            .eq(QualityInspection::getBatchId, alloc.getBatchId())
                            .eq(QualityInspection::getOrgId, principal.getOrgId())
                            .eq(QualityInspection::getResult, "PASS")
                            .isNotNull(QualityInspection::getInspectionStage)
                            .eq(QualityInspection::getIsDeleted, 0));
            if (inspections.isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "QUALITY_NOT_PASSED",
                        "质检未通过", "批次 " + alloc.getBatchId() + " 尚未通过环节质检，不可交付");
            }
        }
        order.setStatus(OrderStatus.PURCHASE_SHIPPED);
        order.setHandlingStatus("COMPLETED");
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("已出货，等待买方收货");
        note.setStatusAt(OrderStatus.PURCHASE_SHIPPED);
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 买方收货入库:采购单 SHIPPED→RECEIVED,生成溯源码并绑定该订单分配的批次。
     * 一码对应终端的一个采购订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse receivePurchaseOrder(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        if (!order.getBuyerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_BUYER_ONLY",
                    "仅买方可操作", "收货入库仅可由采购单买方执行");
        }
        requireOperator(principal);
        if (!OrderStatus.PURCHASE_SHIPPED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "订单不可收货", "仅已出货(SHIPPED)状态的采购单可收货入库，当前状态: " + order.getStatus());
        }
        order.setStatus(OrderStatus.PURCHASE_RECEIVED);
        order.setUpdatedBy(principal.getUserId());

        // 生成溯源码(一码对应一终端采购订单),绑定该订单分配的批次
        if (order.getTraceCodeId() == null) {
            PublicTraceCodeResponse codeResp = publicTraceService.createPublicTraceCodeForSource(
                    principal.getOrgId(), "PURCHASE", orderId, principal.getUserId());
            if (codeResp != null && codeResp.publicId() != null) {
                order.setTraceCodeId(codeResp.id());
                order.setPublicTraceId(codeResp.publicId());
                // 绑定该订单分配的所有批次
                List<OrderBatchAllocation> allocs = orderBatchAllocationMapper.selectByOrderId(orderId);
                for (OrderBatchAllocation alloc : allocs) {
                    publicTraceService.bindBatchToPublicCode(
                            codeResp.publicId(), alloc.getBatchId(), "RECEIPT", principal);
                }
            }
        }
        purchaseOrderMapper.updateById(order);

        // 将订单分配的批次归属权从卖方转移到买方(收货后批次进入买方库存)
        transferAllocatedBatchesToBuyer(orderId, order.getSellerOrgId(), principal.getOrgId(), principal.getUserId());

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("已收货入库，批次已转入库存，溯源码: " + order.getPublicTraceId());
        note.setStatusAt(OrderStatus.PURCHASE_RECEIVED);
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 发起取消请求:买方或卖方可发起,状态置为 PENDING,等待对方或管理员审核。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse requestCancelOrder(Long orderId, String reason,
                                                     TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireOperator(principal);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CANCEL_REASON_REQUIRED",
                    "取消原因缺失", "发起取消必须说明原因");
        }
        if (OrderStatus.PURCHASE_RECEIVED.equals(order.getStatus())
                || OrderStatus.PURCHASE_CANCELLED.equals(order.getStatus())
                || OrderStatus.PURCHASE_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "订单不可取消", "已收货/已取消/已驳回的订单不可再发起取消");
        }
        if (order.getCancelRequestStatus() != null
                && "PENDING".equals(order.getCancelRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CANCEL_ALREADY_PENDING",
                    "取消请求待审核", "该订单已有待审核的取消请求");
        }
        String role = order.getBuyerOrgId().equals(principal.getOrgId()) ? "BUYER" : "SELLER";
        order.setCancelRequestRole(role);
        order.setCancelRequestReason(reason.trim());
        order.setCancelRequestStatus("PENDING");
        order.setCancelRequestAt(LocalDateTime.now(ZoneOffset.UTC));
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText(role.equals("BUYER") ? "买方发起取消请求: " + reason.trim()
                : "卖方发起取消请求: " + reason.trim());
        note.setStatusAt(order.getStatus());
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 同意取消请求:对方(非发起方)或管理员可同意,订单置为 CANCELLED。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse approveCancelRequest(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        if (order.getCancelRequestStatus() == null
                || !"PENDING".equals(order.getCancelRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_PENDING_CANCEL",
                    "无待审核取消请求", "该订单没有待审核的取消请求");
        }
        boolean isAdmin = principal.getRoles() != null && principal.getRoles().contains("ROLE_SYS_ADMIN");
        if (!isAdmin) {
            requireOperator(principal);
            String requesterRole = order.getCancelRequestRole();
            String myRole = order.getBuyerOrgId().equals(principal.getOrgId()) ? "BUYER" : "SELLER";
            if (requesterRole.equals(myRole)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "CANNOT_APPROVE_OWN",
                        "不可审核自己的请求", "取消请求发起方不可自行同意，需对方或管理员审核");
            }
        }
        order.setCancelRequestStatus("APPROVED");
        order.setStatus(OrderStatus.PURCHASE_CANCELLED);
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("取消请求已同意，订单已取消");
        note.setStatusAt(OrderStatus.PURCHASE_CANCELLED);
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 拒绝取消请求:对方或管理员可拒绝,订单恢复原状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse rejectCancelRequest(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        if (order.getCancelRequestStatus() == null
                || !"PENDING".equals(order.getCancelRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_PENDING_CANCEL",
                    "无待审核取消请求", "该订单没有待审核的取消请求");
        }
        boolean isAdmin = principal.getRoles() != null && principal.getRoles().contains("ROLE_SYS_ADMIN");
        if (!isAdmin) {
            requireOperator(principal);
            String requesterRole = order.getCancelRequestRole();
            String myRole = order.getBuyerOrgId().equals(principal.getOrgId()) ? "BUYER" : "SELLER";
            if (requesterRole.equals(myRole)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "CANNOT_REJECT_OWN",
                        "不可审核自己的请求", "取消请求发起方不可自行拒绝，需对方或管理员审核");
            }
        }
        order.setCancelRequestStatus("REJECTED");
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("取消请求已拒绝，订单继续执行");
        note.setStatusAt(order.getStatus());
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    /**
     * 撤回取消请求:仅发起方可撤回自己 PENDING 状态的取消请求,撤回后订单恢复执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderResponse withdrawCancelRequest(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = requirePurchaseAccess(orderId, principal);
        requireOperator(principal);
        if (order.getCancelRequestStatus() == null
                || !"PENDING".equals(order.getCancelRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_PENDING_CANCEL",
                    "无待审核取消请求", "该订单没有待审核的取消请求，无法撤回");
        }
        String myRole = order.getBuyerOrgId().equals(principal.getOrgId()) ? "BUYER" : "SELLER";
        if (!myRole.equals(order.getCancelRequestRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CANCEL_WITHDRAW_NOT_OWNER",
                    "不可撤回他人请求", "仅取消请求发起方可撤回自己的取消请求");
        }
        order.setCancelRequestStatus("WITHDRAWN");
        order.setUpdatedBy(principal.getUserId());
        purchaseOrderMapper.updateById(order);

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType("PURCHASE");
        note.setNoteText("取消请求已撤回，订单继续执行");
        note.setStatusAt(order.getStatus());
        note.setIsTerminalVisible(1);
        note.setOrgId(principal.getOrgId());
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        orderProgressNoteMapper.insert(note);

        return getPurchaseOrder(orderId, principal);
    }

    @Transactional(rollbackFor = Exception.class)
    public SalesOrderResponse createSalesOrder(SalesOrderCreateRequest request, TraceSecurityPrincipal principal) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SalesOrder order = new SalesOrder();
        order.setOrderNo(nextNo("SO"));
        order.setSellerOrgId(principal.getOrgId());
        order.setCustomerName(request.customerName());
        order.setCustomerPhone(request.customerPhone());
        order.setDeliveryAddress(request.deliveryAddress());
        order.setStatus(OrderStatus.SALES_PLACED);
        order.setPlacedAt(now);
        order.setNote(request.note());
        order.setCurrencyCode("CNY");
        order.setCreatedBy(principal.getUserId());
        BigDecimal total = BigDecimal.ZERO;
        order.setAmountTotal(total);
        salesOrderMapper.insert(order);

        for (SalesOrderCreateRequest.SalesItem item : request.items()) {
            String unit = item.unitCode() == null ? "kg" : item.unitCode();
            BigDecimal row = item.quantity().multiply(item.unitPrice());
            total = total.add(row);
            SalesOrderItem soItem = new SalesOrderItem();
            soItem.setOrderId(order.getId());
            soItem.setProductId(item.productId());
            soItem.setQuantity(item.quantity());
            soItem.setUnitCode(unit);
            soItem.setUnitPrice(item.unitPrice());
            soItem.setRowAmount(row);
            soItem.setCreatedBy(principal.getUserId());
            salesOrderItemMapper.insert(soItem);
        }
        order.setAmountTotal(total);
        salesOrderMapper.updateById(order);

        return toSalesResponse(order);
    }

    public List<SalesOrderResponse> listSalesOrders(TraceSecurityPrincipal principal) {
        List<SalesOrderResponse> result = new java.util.ArrayList<>();
        // 1. 原有 B2C 销售单
        salesOrderMapper.selectList(new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getSellerOrgId, principal.getOrgId())
                        .orderByDesc(SalesOrder::getPlacedAt))
                .forEach(so -> result.add(toSalesResponse(so)));
        // 2. 下游发来的采购单(本组织作为卖方),映射为销售单视图
        purchaseOrderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getSellerOrgId, principal.getOrgId())
                        .orderByDesc(PurchaseOrder::getOrderedAt))
                .forEach(po -> result.add(toSalesResponseFromPurchase(po)));
        return result;
    }

    /**
     * 将下游采购单(本组织为卖方)映射为销售单响应,用于"销售单"tab 统一展示下游订单。
     */
    private SalesOrderResponse toSalesResponseFromPurchase(PurchaseOrder po) {
        List<SalesItem> items = purchaseOrderItemMapper.selectList(
                        new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, po.getId()))
                .stream().map(item -> new SalesItem(item.getId(), item.getProductId(),
                        productName(item.getProductId()), item.getQuantity(), item.getUnitCode(),
                        item.getUnitPrice(), item.getRowAmount())).toList();
        String buyerName = orgNameById(po.getBuyerOrgId());
        String contactName = po.getBuyerContactName() != null ? po.getBuyerContactName() : buyerName;
        String contactPhone = po.getBuyerContactPhone();
        String contactAddr = po.getBuyerContactAddress() != null ? po.getBuyerContactAddress() : "下游采购订单";
        return new SalesOrderResponse(po.getId(), po.getOrderNo(), po.getSellerOrgId(),
                po.getBuyerOrgId(),
                contactName, contactPhone, contactAddr, po.getStatus(), po.getTraceCodeId(),
                po.getPublicTraceId(), po.getOrderedAt(), null, po.getNote(),
                po.getCancelRequestRole(), po.getCancelRequestReason(), po.getCancelRequestStatus(),
                po.getAmountTotal(), po.getCurrencyCode(), items);
    }

    private String orgNameById(Long orgId) {
        if (orgId == null) return "未知组织";
        return orgCache.computeIfAbsent(orgId, id -> {
            com.example.traceability.identity.domain.Organization org =
                    organizationMapper.selectById(id);
            return org != null ? org.getName() : "组织#" + id;
        });
    }

    public SalesOrderResponse getSalesOrder(Long orderId, TraceSecurityPrincipal principal) {
        return toSalesResponse(requireSalesAccess(orderId, principal));
    }

    @Transactional(rollbackFor = Exception.class)
    public SalesOrderResponse updateSalesStatus(Long orderId, OrderStatusUpdateRequest request,
                                                TraceSecurityPrincipal principal) {
        SalesOrder order = requireSalesAccess(orderId, principal);
        if (!SALES_NEXT.contains(request.status())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_INVALID_STATUS",
                    "非法状态流转", "目标状态不被销售订单支持");
        }
        if (OrderStatus.SALES_DELIVERED.equals(order.getStatus())
                || OrderStatus.SALES_CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_ALREADY_TERMINAL",
                    "订单已终结", "已送达或已取消的销售单不能再流转");
        }
        order.setStatus(request.status());
        if (OrderStatus.SALES_DELIVERED.equals(request.status())) {
            order.setDeliveredAt(LocalDateTime.now(ZoneOffset.UTC));
            if (order.getTraceCodeId() == null) {
                PublicTraceCodeResponse codeResp = publicTraceService.createPublicTraceCodeForSource(
                        order.getSellerOrgId(), "SALES", order.getId(), principal.getUserId());
                if (codeResp != null && codeResp.publicId() != null) {
                    order.setTraceCodeId(codeResp.id());
                    order.setPackedPackageNo(codeResp.publicId());
                }
            }
        }
        order.setUpdatedBy(principal.getUserId());
        salesOrderMapper.updateById(order);
        return getSalesOrder(orderId, principal);
    }

    private PurchaseOrder requirePurchaseAccess(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在", "采购进货单不存在");
        }
        if (!order.getBuyerOrgId().equals(principal.getOrgId())
                && !order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED",
                    "无权访问", "当前组织与该采购单无关");
        }
        return order;
    }

    /**
     * 收货入库时将订单分配的批次归属权从卖方转移到买方。
     * 使用乐观锁保证安全,转移后批次进入买方库存可见。
     */
    private void transferAllocatedBatchesToBuyer(Long orderId, Long sellerOrgId, Long buyerOrgId, Long userId) {
        List<OrderBatchAllocation> allocs = orderBatchAllocationMapper.selectByOrderId(orderId);
        if (allocs == null || allocs.isEmpty()) {
            return;
        }
        for (OrderBatchAllocation alloc : allocs) {
            com.example.traceability.batch.domain.Batch batch = batchMapper.selectById(alloc.getBatchId());
            if (batch == null) {
                continue;
            }
            // 批次已在买方名下则跳过(幂等)
            if (buyerOrgId.equals(batch.getOrgId())) {
                continue;
            }
            // 批次不在卖方名下说明已被转移,跳过避免覆盖
            if (!sellerOrgId.equals(batch.getOrgId())) {
                continue;
            }
            int affected = batchMapper.updateOrgIdByIdAndVersion(
                    batch.getId(), sellerOrgId, buyerOrgId, batch.getVersion(), userId);
            if (affected == 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "BATCH_TRANSFER_CONFLICT",
                        "批次转移冲突", "批次 " + batch.getBatchNo() + " 在转移过程中版本冲突，请重试");
            }
        }
    }

    private SalesOrder requireSalesAccess(Long orderId, TraceSecurityPrincipal principal) {
        SalesOrder order = salesOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在", "销售订单不存在");
        }
        if (!order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED",
                    "无权访问", "当前组织不是该订单的销售方");
        }
        return order;
    }

    private void requireSeller(PurchaseOrder order, TraceSecurityPrincipal principal) {
        if (!order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_SELLER_ONLY",
                    "仅供货方操作", "审批/驳回/排产仅可由采购单供货方执行");
        }
    }

    private void requireOperator(TraceSecurityPrincipal principal) {
        if (principal.getRoles() == null || !principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED",
                    "角色权限不足", "仅企业操作员 (OPERATOR) 可执行订单审批与排产");
        }
    }

    private PurchaseOrderResponse toPurchaseResponse(PurchaseOrder order) {
        List<PurchaseItem> items = purchaseOrderItemMapper.selectList(
                        new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, order.getId()))
                .stream().map(item -> new PurchaseItem(item.getId(), item.getProductId(),
                        productName(item.getProductId()), item.getQuantity(), item.getUnitCode(),
                        item.getUnitPrice(), item.getRowAmount())).toList();
        return new PurchaseOrderResponse(order.getId(), order.getOrderNo(), order.getBuyerOrgId(),
                order.getSellerOrgId(), order.getOrderType(), order.getStatus(), order.getOrderedAt(),
                order.getExpectedDeliveryAt(), order.getNote(),
                order.getBuyerContactName(), order.getBuyerContactPhone(), order.getBuyerContactAddress(),
                order.getApprovedBy(), order.getApprovedAt(),
                order.getRejectReason(), order.getReceiptBatchId(), order.getTraceCodeId(),
                order.getPublicTraceId(), order.getCancelRequestRole(), order.getCancelRequestReason(),
                order.getCancelRequestStatus(), order.getAmountTotal(),
                order.getCurrencyCode(), items);
    }

    private SalesOrderResponse toSalesResponse(SalesOrder order) {
        List<SalesItem> items = salesOrderItemMapper.selectList(
                        new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, order.getId()))
                .stream().map(item -> new SalesItem(item.getId(), item.getProductId(),
                        productName(item.getProductId()), item.getQuantity(), item.getUnitCode(),
                        item.getUnitPrice(), item.getRowAmount())).toList();
        return new SalesOrderResponse(order.getId(), order.getOrderNo(), order.getSellerOrgId(),
                null,
                order.getCustomerName(), order.getCustomerPhone(), order.getDeliveryAddress(),
                order.getStatus(), order.getTraceCodeId(), order.getPackedPackageNo(),
                order.getPlacedAt(), order.getDeliveredAt(), order.getNote(),
                null, null, null,
                order.getAmountTotal(),
                order.getCurrencyCode(), items);
    }

    private static final Map<Long, String> NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private String productName(Long productId) {
        return NAME_CACHE.computeIfAbsent(productId, id -> {
            Product product = productMapper.selectById(id);
            return product == null ? "未知产品(" + id + ")" : product.getPublicName();
        });
    }

    private static String nextNo(String prefix) {
        return prefix + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}