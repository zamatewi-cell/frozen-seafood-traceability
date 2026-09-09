package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchRelation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 批次谱系父子关系图谱边持久层访问接口。
 * <p>
 * 提供谱系边持久化、输出批次已有上游检测，以及基于 MySQL 8.4 recursive CTE 的有向图环检测能力。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchRelationMapper extends BaseMapper<BatchRelation> {

    @Select("SELECT * FROM batch_relation WHERE operation_id = #{operationId} ORDER BY id ASC")
    List<BatchRelation> selectByOperationId(@Param("operationId") Long operationId);

    @Select("SELECT COUNT(*) FROM batch_relation WHERE child_batch_id = #{childBatchId}")
    int countUpstreamRelationsByChildBatchId(@Param("childBatchId") Long childBatchId);

    @Insert("<script>" +
            "INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES " +
            "<foreach collection='relations' item='r' separator=','>" +
            "(#{r.operationId}, #{r.parentBatchId}, #{r.childBatchId}, #{r.relationType}, #{r.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("relations") List<BatchRelation> relations);

    /**
     * 基于 MySQL 8.4 递归公用表表达式（recursive CTE）检测拟新增的有向边是否会导致图成环。
     * <p>
     * 拟添加 parent -> child 边：若现有图谱中从 child 沿着已有下游边可达 parent，
     * 则加入该边必将闭环。
     * </p>
     *
     * @param startChildBatchId   拟新增边的子批次 ID（遍历起点）
     * @param targetParentBatchId 拟新增边的父批次 ID（目标检测点）
     * @return 可达路径计数；若大于 0 则表明会成环
     */
    @Select("WITH RECURSIVE downstream_path (current_batch_id, depth) AS ( " +
            "    SELECT #{startChildBatchId} AS current_batch_id, 0 AS depth " +
            "    UNION ALL " +
            "    SELECT r.child_batch_id, p.depth + 1 " +
            "    FROM batch_relation r " +
            "    JOIN downstream_path p ON r.parent_batch_id = p.current_batch_id " +
            "    WHERE p.depth < 50 " +
            ") " +
            "SELECT COUNT(*) " +
            "FROM downstream_path " +
            "WHERE current_batch_id = #{targetParentBatchId} AND depth > 0")
    int checkCycleWithCte(
            @Param("startChildBatchId") Long startChildBatchId,
            @Param("targetParentBatchId") Long targetParentBatchId
    );
}
