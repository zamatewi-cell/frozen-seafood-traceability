package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.PublicTraceCodeBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 公开追溯码 - 批次聚合关联持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.2.0
 */
@Mapper
public interface PublicTraceCodeBatchMapper extends BaseMapper<PublicTraceCodeBatch> {

    /**
     * 查询一个公开追溯码当前聚合的全部批次 ID（按绑定顺序）。
     */
    @Select("SELECT batch_id FROM public_trace_code_batch WHERE trace_code_id = #{traceCodeId} ORDER BY bound_at ASC, id ASC")
    List<Long> selectBatchIdsByTraceCodeId(@Param("traceCodeId") Long traceCodeId);

    /**
     * 按公共分组条件查询某一批次被哪些公开码聚合（用于展示/去重）。
     */
    @Select("SELECT DISTINCT trace_code_id FROM public_trace_code_batch WHERE batch_id = #{batchId}")
    List<Long> selectTraceCodeIdsByBatchId(@Param("batchId") Long batchId);

    /**
     * 解绑某公开码上的特定批次。
     */
    @Delete("DELETE FROM public_trace_code_batch WHERE trace_code_id = #{traceCodeId} AND batch_id = #{batchId}")
    int unbind(@Param("traceCodeId") Long traceCodeId, @Param("batchId") Long batchId);
}