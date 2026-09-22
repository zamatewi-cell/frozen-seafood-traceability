package com.example.traceability.order.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.order.domain.OrderBatchAllocation;
import com.example.traceability.order.domain.PurchaseOrder;
import com.example.traceability.order.dto.BatchAllocationRequest;
import com.example.traceability.order.dto.BatchAllocationResponse;
import com.example.traceability.order.mapper.OrderBatchAllocationMapper;
import com.example.traceability.order.mapper.PurchaseOrderMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单-批次分配应用服务。
 * <p>
 * 交付时从卖方库存选批次分配给订单，一个订单可分多批形成树状图分支。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Service
public class OrderBatchAllocationService {

    private final OrderBatchAllocationMapper allocationMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final BatchMapper batchMapper;
    private final ProductMapper productMapper;

    public OrderBatchAllocationService(OrderBatchAllocationMapper allocationMapper,
                                      PurchaseOrderMapper purchaseOrderMapper,
                                      BatchMapper batchMapper,
                                      ProductMapper productMapper) {
        this.allocationMapper = allocationMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.batchMapper = batchMapper;
        this.productMapper = productMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchAllocationResponse allocate(Long orderId, BatchAllocationRequest request,
                                             TraceSecurityPrincipal principal) {
        PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                    "订单不存在", "采购进货单不存在");
        }
        if (!order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ALLOCATION_FORBIDDEN",
                    "无权分配", "仅供货方可分配批次给订单");
        }
        if (!"CONFIRMED".equals(order.getStatus()) && !"PROCESSING".equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_INVALID_STATE",
                    "订单不可分配", "仅已确认或处理中的采购单可分配批次，当前状态: " + order.getStatus());
        }

        Batch batch = batchMapper.selectByIdAndOrgIdForUpdate(request.batchId(), principal.getOrgId());
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                    "批次不存在", "库存中找不到该批次");
        }
        if (!"ACTIVE".equals(batch.getStatus()) && !"FROZEN".equals(batch.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_NOT_AVAILABLE",
                    "批次不可用", "仅 ACTIVE 或 FROZEN 状态的批次可分配，当前状态: " + batch.getStatus());
        }

        BigDecimal alreadyAllocated = allocationMapper.sumAllocatedByBatchId(request.batchId());
        BigDecimal available = batch.getQuantity().subtract(alreadyAllocated);
        if (request.allocatedQuantity().compareTo(available) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_INSUFFICIENT",
                    "批次可用量不足", "该批次剩余可用量: " + available + " kg，申请分配: " + request.allocatedQuantity());
        }

        Integer maxOrder = allocationMapper.selectByOrderId(orderId).stream()
                .mapToInt(OrderBatchAllocation::getAllocationOrder).max().orElse(0);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        OrderBatchAllocation alloc = new OrderBatchAllocation();
        alloc.setOrderId(orderId);
        alloc.setBatchId(request.batchId());
        alloc.setAllocatedQuantity(request.allocatedQuantity());
        alloc.setUnitCode(batch.getUnitCode());
        alloc.setAllocationOrder(maxOrder + 1);
        alloc.setOrgId(principal.getOrgId());
        alloc.setAllocatedBy(principal.getUserId());
        alloc.setAllocatedAt(now);
        alloc.setUpdatedAt(now);
        alloc.setVersion(0L);
        alloc.setIsDeleted(0);
        allocationMapper.insert(alloc);

        if ("CONFIRMED".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
            order.setHandlingStatus("IN_PROGRESS");
            order.setUpdatedBy(principal.getUserId());
            purchaseOrderMapper.updateById(order);
        }

        return toResponse(alloc, batch, null);
    }

    public List<BatchAllocationResponse> listAllocations(Long orderId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                    "订单不存在", "采购进货单不存在");
        }
        if (!order.getBuyerOrgId().equals(principal.getOrgId())
                && !order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED",
                    "无权访问", "当前组织与该采购单无关");
        }
        List<OrderBatchAllocation> allocs = allocationMapper.selectByOrderId(orderId);
        if (allocs.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> batchMap = allocs.stream()
                .map(OrderBatchAllocation::getBatchId)
                .distinct()
                .collect(Collectors.toMap(
                        bid -> bid,
                        bid -> batchMapper.selectByIdIgnoreTenant(bid)
                ));
        Map<Long, Product> productMap = batchMap.values().stream()
                .filter(b -> b != null)
                .map(Batch::getProductId)
                .distinct()
                .collect(Collectors.toMap(
                        pid -> pid,
                        pid -> productMapper.selectById(pid)
                ));
        return allocs.stream()
                .map(a -> toResponse(a, batchMap.get(a.getBatchId()),
                        productMap.get(batchMap.get(a.getBatchId()) != null
                                ? batchMap.get(a.getBatchId()).getProductId() : null)))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeAllocation(Long orderId, Long allocId, TraceSecurityPrincipal principal) {
        PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                    "订单不存在", "采购进货单不存在");
        }
        if (!order.getSellerOrgId().equals(principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ALLOCATION_FORBIDDEN",
                    "无权删除", "仅供货方可删除分配");
        }
        OrderBatchAllocation alloc = allocationMapper.selectById(allocId);
        if (alloc == null || !alloc.getOrderId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ALLOCATION_NOT_FOUND",
                    "分配不存在", "找不到该分配记录");
        }
        allocationMapper.deleteById(allocId);
    }

    private BatchAllocationResponse toResponse(OrderBatchAllocation alloc, Batch batch, Product product) {
        return new BatchAllocationResponse(
                alloc.getId(),
                alloc.getOrderId(),
                alloc.getBatchId(),
                batch != null ? batch.getBatchNo() : null,
                product != null ? product.getPublicName() : null,
                alloc.getAllocatedQuantity(),
                alloc.getUnitCode(),
                alloc.getAllocationOrder(),
                alloc.getOrgId(),
                alloc.getAllocatedBy(),
                alloc.getAllocatedAt()
        );
    }
}
