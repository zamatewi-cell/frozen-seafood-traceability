package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采购进货单持久层接口。
 */
@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {
}