package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.OrderBatchAllocation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单-批次分配持久层接口。
 */
@Mapper
public interface OrderBatchAllocationMapper extends BaseMapper<OrderBatchAllocation> {

    @Select("SELECT * FROM order_batch_allocation WHERE order_id = #{orderId} AND is_deleted = 0 ORDER BY allocation_order ASC, id ASC")
    List<OrderBatchAllocation> selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(SUM(allocated_quantity), 0) FROM order_batch_allocation WHERE batch_id = #{batchId} AND is_deleted = 0")
    BigDecimal sumAllocatedByBatchId(@Param("batchId") Long batchId);

    @Select("SELECT COALESCE(SUM(allocated_quantity), 0) FROM order_batch_allocation WHERE order_id = #{orderId} AND is_deleted = 0")
    BigDecimal sumAllocatedByOrderId(@Param("orderId") Long orderId);

    @Delete("UPDATE order_batch_allocation SET is_deleted = 1, updated_at = #{updatedAt} WHERE order_id = #{orderId} AND is_deleted = 0")
    int releaseAllocationsByOrderId(@Param("orderId") Long orderId, @Param("updatedAt") LocalDateTime updatedAt);
}
