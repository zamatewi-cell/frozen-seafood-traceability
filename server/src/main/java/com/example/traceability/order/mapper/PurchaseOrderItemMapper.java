package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.PurchaseOrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采购进货单明细持久层接口。
 */
@Mapper
public interface PurchaseOrderItemMapper extends BaseMapper<PurchaseOrderItem> {
}