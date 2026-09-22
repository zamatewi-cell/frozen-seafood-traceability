package com.example.traceability.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.order.domain.OrderBatchAllocation;
import com.example.traceability.order.dto.InventoryBatchResponse;
import com.example.traceability.order.mapper.OrderBatchAllocationMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存聚合查询应用服务。
 * <p>
 * 按当前登录组织返回其名下所有可售批次，附带已分配量、可用量、溯源事件数。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Service
public class InventoryApplicationService {

    private final BatchMapper batchMapper;
    private final OrderBatchAllocationMapper allocationMapper;
    private final ProductMapper productMapper;
    private final TraceEventMapper traceEventMapper;

    public InventoryApplicationService(BatchMapper batchMapper,
                                       OrderBatchAllocationMapper allocationMapper,
                                       ProductMapper productMapper,
                                       TraceEventMapper traceEventMapper) {
        this.batchMapper = batchMapper;
        this.allocationMapper = allocationMapper;
        this.productMapper = productMapper;
        this.traceEventMapper = traceEventMapper;
    }

    public List<InventoryBatchResponse> listMyInventory(TraceSecurityPrincipal principal) {
        Long orgId = principal.getOrgId();
        List<Batch> batches = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getOrgId, orgId)
                .orderByDesc(Batch::getCreatedAt));
        if (batches.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> productMap = batches.stream()
                .map(Batch::getProductId)
                .distinct()
                .collect(Collectors.toMap(pid -> pid, pid -> productMapper.selectById(pid)));

        return batches.stream().map(batch -> {
            BigDecimal allocated = allocationMapper.sumAllocatedByBatchId(batch.getId());
            if (allocated == null) allocated = BigDecimal.ZERO;
            BigDecimal available = batch.getQuantity().subtract(allocated);
            int eventCount = traceEventMapper.selectEffectiveEventsByBatchId(batch.getId()).size();
            Product product = productMap.get(batch.getProductId());
            return new InventoryBatchResponse(
                    batch.getId(),
                    batch.getBatchNo(),
                    batch.getProductId(),
                    product != null ? product.getPublicName() : null,
                    batch.getBatchType(),
                    batch.getQuantity(),
                    allocated,
                    available,
                    batch.getUnitCode(),
                    batch.getStatus(),
                    batch.getOriginType(),
                    batch.getOriginText(),
                    batch.getProductionDate(),
                    eventCount
            );
        }).toList();
    }
}
