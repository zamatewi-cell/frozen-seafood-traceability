package com.example.traceability.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.order.domain.OrderStatus;
import com.example.traceability.order.domain.PurchaseOrder;
import com.example.traceability.order.domain.PurchaseOrderItem;
import com.example.traceability.order.domain.SalesOrder;
import com.example.traceability.order.domain.SalesOrderItem;
import com.example.traceability.order.dto.OrderStatusUpdateRequest;
import com.example.traceability.order.dto.PurchaseOrderCreateRequest;
import com.example.traceability.order.dto.PurchaseOrderResponse;
import com.example.traceability.order.dto.PurchaseOrderResponse.PurchaseItem;
import com.example.traceability.order.dto.SalesOrderCreateRequest;
import com.example.traceability.order.dto.SalesOrderResponse;
import com.example.traceability.order.dto.SalesOrderResponse.SalesItem;
import com.example.traceability.order.mapper.PurchaseOrderItemMapper;
import com.example.traceability.order.mapper.PurchaseOrderMapper;
import com.example.traceability.order.mapper.SalesOrderItemMapper;
import com.example.traceability.order.mapper.SalesOrderMapper;
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

    public OrderApplicationService(
            PurchaseOrderMapper purchaseOrderMapper,
            PurchaseOrderItemMapper purchaseOrderItemMapper,
            SalesOrderMapper salesOrderMapper,
            SalesOrderItemMapper salesOrderItemMapper,
            ProductMapper productMapper) {
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.purchaseOrderItemMapper = purchaseOrderItemMapper;
        this.salesOrderMapper = salesOrderMapper;
        this.salesOrderItemMapper = salesOrderItemMapper;
        this.productMapper = productMapper;
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
                        .and(w -> w.eq(PurchaseOrder::getBuyerOrgId, principal.getOrgId())
                                .or().eq(PurchaseOrder::getSellerOrgId, principal.getOrgId()))
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
        return salesOrderMapper.selectList(new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getSellerOrgId, principal.getOrgId())
                        .orderByDesc(SalesOrder::getPlacedAt))
                .stream().map(this::toSalesResponse).toList();
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

    private PurchaseOrderResponse toPurchaseResponse(PurchaseOrder order) {
        List<PurchaseItem> items = purchaseOrderItemMapper.selectList(
                        new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, order.getId()))
                .stream().map(item -> new PurchaseItem(item.getId(), item.getProductId(),
                        productName(item.getProductId()), item.getQuantity(), item.getUnitCode(),
                        item.getUnitPrice(), item.getRowAmount())).toList();
        return new PurchaseOrderResponse(order.getId(), order.getOrderNo(), order.getBuyerOrgId(),
                order.getSellerOrgId(), order.getOrderType(), order.getStatus(), order.getOrderedAt(),
                order.getExpectedDeliveryAt(), order.getNote(), order.getAmountTotal(),
                order.getCurrencyCode(), items);
    }

    private SalesOrderResponse toSalesResponse(SalesOrder order) {
        List<SalesItem> items = salesOrderItemMapper.selectList(
                        new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, order.getId()))
                .stream().map(item -> new SalesItem(item.getId(), item.getProductId(),
                        productName(item.getProductId()), item.getQuantity(), item.getUnitCode(),
                        item.getUnitPrice(), item.getRowAmount())).toList();
        return new SalesOrderResponse(order.getId(), order.getOrderNo(), order.getSellerOrgId(),
                order.getCustomerName(), order.getCustomerPhone(), order.getDeliveryAddress(),
                order.getStatus(), order.getTraceCodeId(), order.getPackedPackageNo(),
                order.getPlacedAt(), order.getDeliveredAt(), order.getNote(), order.getAmountTotal(),
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