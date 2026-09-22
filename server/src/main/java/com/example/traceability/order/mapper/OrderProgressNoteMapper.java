package com.example.traceability.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.order.domain.OrderProgressNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单进度备注持久层接口。
 */
@Mapper
public interface OrderProgressNoteMapper extends BaseMapper<OrderProgressNote> {

    @Select("SELECT * FROM order_progress_note WHERE order_id = #{orderId} AND order_type = #{orderType} AND is_deleted = 0 ORDER BY created_at ASC, id ASC")
    List<OrderProgressNote> selectByOrder(@Param("orderId") Long orderId, @Param("orderType") String orderType);
}
