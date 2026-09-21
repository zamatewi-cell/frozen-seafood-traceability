package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.SalesOrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 销售订单明细持久层接口。
 */
@Mapper
public interface SalesOrderItemMapper extends BaseMapper<SalesOrderItem> {
}