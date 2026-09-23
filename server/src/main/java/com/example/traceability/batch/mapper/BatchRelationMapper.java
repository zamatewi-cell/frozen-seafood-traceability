package com.example.traceability.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.BatchLineageEdge;
import com.example.traceability.batch.domain.BatchRelation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 批次谱系父子关系图谱边持久层访问接口。
 * <p>
 * 提供谱系边持久化、基于 MySQL 8.4 recursive CTE 的有向图环检测与反向溯源（祖先谱系边）查询能力。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface BatchRelationMapper extends BaseMapper<BatchRelation> {

    @Select("SELECT * FROM batch_relation WHERE operation_id = #{operationId} ORDER BY id ASC")
    List<BatchRelation> selectByOperationId(@Param("operationId") Long operationId);

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
    @Select("WITH RECURSIVE downstream_reachable (current_batch_id) AS ( " +
            "    SELECT r.child_batch_id " +
            "    FROM batch_relation r " +
            "    WHERE r.parent_batch_id = #{startChildBatchId} " +
            "    UNION " +
            "    SELECT r.child_batch_id " +
            "    FROM batch_relation r " +
            "    JOIN downstream_reachable p ON r.parent_batch_id = p.current_batch_id " +
            ") " +
            "SELECT COUNT(*) " +
            "FROM downstream_reachable " +
            "WHERE current_batch_id = #{targetParentBatchId}")
    int checkCycleWithCte(
            @Param("startChildBatchId") Long startChildBatchId,
            @Param("targetParentBatchId") Long targetParentBatchId
    );

    /**
     * 基于 MySQL 8.4 recursive CTE 反向溯源：返回目标批次全部祖先谱系边（只沿 child → parent 向上遍历）。
     * <p>
     * 只向上遍历，因此同一父批次的兄弟产出（如与目标同源拆分的其他批次）永远不会进入结果。
     * 递归部分使用 UNION（DISTINCT），同一祖先只出现一次，DAG（MERGE 菱形）自然去重，
     * 即使存在异常环也会在没有新批次时终止。遍历不按批次操作状态过滤、左连接批次操作：
     * 引用的批次操作缺失、未提交或已删除时由调用方按谱系完整性错误拒绝，而不是在 SQL 中静默丢弃该边。
     * </p>
     *
     * @param targetBatchId 目标批次 ID（扫码批次）
     * @return 祖先谱系边（按子批次、父批次 ID 升序，仅供服务端确定性处理）
     */
    @Select("WITH RECURSIVE ancestry (batch_id) AS ( " +
            "    SELECT b.id FROM batch b WHERE b.id = #{targetBatchId} " +
            "    UNION " +
            "    SELECT r.parent_batch_id " +
            "    FROM batch_relation r " +
            "    JOIN ancestry a ON r.child_batch_id = a.batch_id " +
            ") " +
            "SELECT r.parent_batch_id, r.child_batch_id, r.relation_type, " +
            "       op.id AS operation_id, op.operation_type, op.status AS operation_status, " +
            "       op.is_deleted AS operation_deleted, op.occurred_at AS operation_occurred_at " +
            "FROM batch_relation r " +
            "JOIN ancestry a ON r.child_batch_id = a.batch_id " +
            "LEFT JOIN batch_operation op ON op.id = r.operation_id " +
            "ORDER BY r.child_batch_id ASC, r.parent_batch_id ASC")
    List<BatchLineageEdge> selectAncestorEdges(@Param("targetBatchId") Long targetBatchId);
}
