package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchOperationItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    @Select("SELECT COUNT(*) " +
            "FROM batch_operation_item i " +
            "JOIN batch_operation op ON i.operation_id = op.id " +
            "WHERE i.batch_id = #{batchId} " +
            "  AND i.role = 'INPUT' " +
            "  AND i.is_deleted = 0 " +
            "  AND op.status = 'SUBMITTED' " +
            "  AND op.is_deleted = 0")
    int countSubmittedInputUsageByBatchId(@Param("batchId") Long batchId);

    @Insert("<script>" +
            "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, conversion_rule_id, version, is_deleted, created_at, created_by, updated_at, updated_by) VALUES " +
            "<foreach collection='items' item='item' separator=','>" +
            "(#{item.operationId}, #{item.batchId}, #{item.role}, #{item.quantity}, #{item.unitCode}, #{item.normalizedQuantity}, #{item.conversionRuleId}, #{item.version}, #{item.isDeleted}, #{item.createdAt}, #{item.createdBy}, #{item.updatedAt}, #{item.updatedBy})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("items") List<BatchOperationItem> items);

    /**
     * 批量统计各批次在已提交操作中的累计 INPUT 数量，用于派生 remainingQuantity。
     *
     * @param batchIds 批次 ID 集合（非空）
     * @return 每行包含 batchId 与 total
     */
    @Select("<script>" +
            "SELECT i.batch_id AS batchId, SUM(i.quantity) AS total " +
            "FROM batch_operation_item i " +
            "JOIN batch_operation op ON i.operation_id = op.id " +
            "WHERE i.role = 'INPUT' AND i.is_deleted = 0 AND op.status = 'SUBMITTED' AND op.is_deleted = 0 " +
            "AND i.batch_id IN <foreach collection='batchIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY i.batch_id" +
            "</script>")
    List<Map<String, Object>> sumSubmittedInputQuantityByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    /**
     * 删除批次操作草稿时逻辑删除其全部明细（释放 uk_item_output_batch 唯一产出占用）。
     *
     * @return 影响行数
     */
    @Update("UPDATE batch_operation_item SET is_deleted = 1, updated_at = #{nowUtc}, updated_by = #{updatedBy} " +
            "WHERE operation_id = #{operationId} AND is_deleted = 0")
    int softDeleteByOperationId(
            @Param("operationId") Long operationId,
            @Param("nowUtc") LocalDateTime nowUtc,
            @Param("updatedBy") Long updatedBy
    );
}
