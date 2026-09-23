package com.example.traceability.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.sale.domain.Sale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 终端销售台账持久层访问接口。
 * <p>
 * 台账追加式不可修改，只提供插入、幂等查询、按批次查询与已提交销售数量汇总（用于派生 remainingQuantity）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface SaleMapper extends BaseMapper<Sale> {

    @Select("SELECT * FROM sale WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey}")
    Sale selectByOrgIdAndIdempotencyKey(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 当前锁定读：并发插入触发唯一键冲突后读取最新已提交的同组织同幂等键销售。
     */
    @Select("SELECT * FROM sale WHERE org_id = #{orgId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    Sale selectByOrgIdAndIdempotencyKeyForUpdate(@Param("orgId") Long orgId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM sale WHERE batch_id = #{batchId} ORDER BY occurred_at ASC, id ASC")
    List<Sale> selectByBatchId(@Param("batchId") Long batchId);

    @Select("SELECT COALESCE(SUM(quantity), 0) FROM sale WHERE batch_id = #{batchId} AND status = 'SUBMITTED'")
    BigDecimal sumSubmittedQuantityByBatchId(@Param("batchId") Long batchId);

    /**
     * 批量统计各批次已提交终端销售数量，用于派生 remainingQuantity。
     *
     * @param batchIds 批次 ID 集合（非空）
     * @return 每行包含 batchId 与 total
     */
    @Select("<script>" +
            "SELECT batch_id AS batchId, SUM(quantity) AS total FROM sale " +
            "WHERE status = 'SUBMITTED' AND batch_id IN " +
            "<foreach collection='batchIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY batch_id" +
            "</script>")
    List<Map<String, Object>> sumSubmittedQuantityByBatchIds(@Param("batchIds") Collection<Long> batchIds);
}
