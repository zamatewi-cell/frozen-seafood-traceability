package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.SalesOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 销售订单持久层接口。
 */
@Mapper
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {
}