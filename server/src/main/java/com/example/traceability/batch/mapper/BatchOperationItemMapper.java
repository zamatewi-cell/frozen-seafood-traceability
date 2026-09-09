package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchOperationItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次操作明细项目持久层访问接口。
 * <p>
 * 提供明细查询、批量入库及输入批次在历史已提交操作中的累计 INPUT 数量统计。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchOperationItemMapper extends BaseMapper<BatchOperationItem> {

    @Select("SELECT * FROM batch_operation_item WHERE operation_id = #{operationId} AND is_deleted = 0 ORDER BY id ASC")
    List<BatchOperationItem> selectByOperationId(@Param("operationId") Long operationId);

    @Select("SELECT COALESCE(SUM(i.quantity), 0) " +
            "FROM batch_operation_item i " +
            "JOIN batch_operation op ON i.operation_id = op.id " +
            "WHERE i.batch_id = #{batchId} " +
            "  AND i.role = 'INPUT' " +
            "  AND i.is_deleted = 0 " +
            "  AND op.status = 'SUBMITTED' " +
            "  AND op.is_deleted = 0")
    BigDecimal sumSubmittedInputQuantityByBatchId(@Param("batchId") Long batchId);

    @Insert("<script>" +
            "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, conversion_rule_id, version, is_deleted, created_at, created_by, updated_at, updated_by) VALUES " +
            "<foreach collection='items' item='item' separator=','>" +
            "(#{item.operationId}, #{item.batchId}, #{item.role}, #{item.quantity}, #{item.unitCode}, #{item.normalizedQuantity}, #{item.conversionRuleId}, #{item.version}, #{item.isDeleted}, #{item.createdAt}, #{item.createdBy}, #{item.updatedAt}, #{item.updatedBy})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("items") List<BatchOperationItem> items);
}
