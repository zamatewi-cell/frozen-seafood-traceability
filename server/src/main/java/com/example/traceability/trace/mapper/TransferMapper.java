package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.Transfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业间批次交接持久层访问接口。
 * <p>
 * 强制在 SQL 层实现 sender_org_id / receiver_org_id 租户数据范围隔离，
 * 阻止跨组织偷窥，支持根据 direction(SENT/RECEIVED) 与 status 组合检索并按 updated_at DESC, id DESC 稳定排序。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface TransferMapper extends BaseMapper<Transfer> {

    /**
     * 根据主键排他锁定交接凭证行记录 (FOR UPDATE)。
     *
     * @param id 交接凭证 ID
     * @return 交接实体
     */
    @Select("SELECT * FROM `transfer` WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Transfer selectByIdForUpdate(@Param("id") Long id);

    /**
     * 根据交接 ID 和组织范围查询交接详情 (SQL 层强制组织隔离)。
     * 仅当组织作为发送方或接收方时返回，第三组织直接返回 null。
     *
     * @param id    交接 ID
     * @param orgId 当前请求主体所属企业组织 ID
     * @return 交接实体；若不存在或当前组织非发货/收货方则返回 null
     */
    @Select("SELECT * FROM `transfer` WHERE id = #{id} AND is_deleted = 0 " +
            "AND (sender_org_id = #{orgId} OR receiver_org_id = #{orgId})")
    Transfer selectByIdAndOrgScope(@Param("id") Long id, @Param("orgId") Long orgId);


    /**
     * 判断指定交接 ID 是否存在（用于跨租户越权探测区分 403 与 404，仅返回标量计数，避免加载完整敏感实体）。
     *
     * @param id 交接 ID
     * @return 存在记录数（0 或 1）
     */
    @Select("SELECT COUNT(1) FROM `transfer` WHERE id = #{id} AND is_deleted = 0")
    int existsByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 统计指定批次下未结束（DRAFT 或 PENDING）的交接数量。
     *
     * @param batchId 批次 ID
     * @return 未结束交接记录数
     */
    @Select("SELECT COUNT(*) FROM `transfer` WHERE batch_id = #{batchId} " +
            "AND status IN ('DRAFT', 'PENDING') AND is_deleted = 0")
    int countActiveTransfersByBatchId(@Param("batchId") Long batchId);

    /**
     * 统计指定批次下处于 PENDING 状态的交接数量（业务排他预留）。
     *
     * @param batchId 批次 ID
     * @return PENDING 交接记录数
     */
    @Select("SELECT COUNT(*) FROM `transfer` WHERE batch_id = #{batchId} " +
            "AND status = 'PENDING' AND is_deleted = 0")
    int countPendingTransfersByBatchId(@Param("batchId") Long batchId);

    /**
     * 强约束组织谓词、期望状态与乐观锁版本号的条件更新交接记录。
     *
     * @param entity                待更新实体
     * @param expectedSenderOrgId   期望发货方企业组织 ID
     * @param expectedReceiverOrgId 期望收货方企业组织 ID
     * @param expectedStatus        期望当前交接状态
     * @param expectedVersion       期望版本号
     * @return 影响行数 (1: 成功, 0: 组织谓词不匹配/状态已被并发修改/版本冲突)
     */
    @Update("""
            UPDATE `transfer`
            SET receiver_org_id = #{entity.receiverOrgId},
                shipment_id = #{entity.shipmentId},
                shipped_at = #{entity.shippedAt},
                submitted_recorded_at = #{entity.submittedRecordedAt},
                submitted_by = #{entity.submittedBy},
                received_at = #{entity.receivedAt},
                decision_recorded_at = #{entity.decisionRecordedAt},
                decided_by = #{entity.decidedBy},
                received_quantity = #{entity.receivedQuantity},
                difference_reason = #{entity.differenceReason},
                status = #{entity.status},
                rejection_reason = #{entity.rejectionReason},
                is_deleted = #{entity.isDeleted},
                version = version + 1,
                updated_at = NOW(6),
                updated_by = #{entity.updatedBy}
            WHERE id = #{entity.id}
              AND sender_org_id = #{expectedSenderOrgId}
              AND receiver_org_id = #{expectedReceiverOrgId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int updateByIdAndVersion(
            @Param("entity") Transfer entity,
            @Param("expectedSenderOrgId") Long expectedSenderOrgId,
            @Param("expectedReceiverOrgId") Long expectedReceiverOrgId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") Long expectedVersion
    );

    /**
     * 组织范围条件下的交接记录总数统计。
     *
     * @param orgId     当前请求组织 ID
     * @param direction 流向筛选 (SENT: 发出, RECEIVED: 收到, null: 全部相关)
     * @param status    状态筛选
     * @return 记录数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM `transfer` WHERE is_deleted = 0 " +
            "<choose>" +
            "  <when test='\"SENT\".equalsIgnoreCase(direction)'> AND sender_org_id = #{orgId} </when>" +
            "  <when test='\"RECEIVED\".equalsIgnoreCase(direction)'> AND receiver_org_id = #{orgId} </when>" +
            "  <otherwise> AND (sender_org_id = #{orgId} OR receiver_org_id = #{orgId}) </otherwise>" +
            "</choose>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='batchId != null'> AND batch_id = #{batchId} </if>" +
            "</script>")
    long countTransfers(
            @Param("orgId") Long orgId,
            @Param("direction") String direction,
            @Param("status") String status,
            @Param("batchId") Long batchId
    );

    /**
     * 组织范围条件下的交接记录分页列表查询。
     *
     * @param orgId     当前请求组织 ID
     * @param direction 流向筛选 (SENT: 发出, RECEIVED: 收到, null: 全部相关)
     * @param status    状态筛选
     * @param offset    分页偏移量
     * @param size      每页大小
     * @return 交接实体列表 (稳定按 updated_at DESC, id DESC 排序)
     */
    @Select("<script>" +
            "SELECT * FROM `transfer` WHERE is_deleted = 0 " +
            "<choose>" +
            "  <when test='\"SENT\".equalsIgnoreCase(direction)'> AND sender_org_id = #{orgId} </when>" +
            "  <when test='\"RECEIVED\".equalsIgnoreCase(direction)'> AND receiver_org_id = #{orgId} </when>" +
            "  <otherwise> AND (sender_org_id = #{orgId} OR receiver_org_id = #{orgId}) </otherwise>" +
            "</choose>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='batchId != null'> AND batch_id = #{batchId} </if>" +
            "ORDER BY updated_at DESC, id DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Transfer> selectTransfersPage(
            @Param("orgId") Long orgId,
            @Param("direction") String direction,
            @Param("status") String status,
            @Param("batchId") Long batchId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    /**
     * 将 DRAFT 交接绑定到运输任务（调用方已按 shipment → transfer 顺序持有两行排他锁）。
     *
     * @return 影响行数 (1: 成功, 0: 已非 DRAFT / 已绑定 / 版本冲突)
     */
    @Update("UPDATE `transfer` SET shipment_id = #{shipmentId}, version = version + 1, updated_at = NOW(6), updated_by = #{updatedBy} " +
            "WHERE id = #{id} AND status = 'DRAFT' AND shipment_id IS NULL AND version = #{expectedVersion} AND is_deleted = 0")
    int bindShipment(
            @Param("id") Long id,
            @Param("shipmentId") Long shipmentId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("updatedBy") Long updatedBy
    );

    /**
     * 将 DRAFT 交接从运输任务解绑（调用方已按 shipment → transfer 顺序持有两行排他锁）。
     *
     * @return 影响行数 (1: 成功, 0: 已非 DRAFT / 未绑定该运输任务 / 版本冲突)
     */
    @Update("UPDATE `transfer` SET shipment_id = NULL, version = version + 1, updated_at = NOW(6), updated_by = #{updatedBy} " +
            "WHERE id = #{id} AND shipment_id = #{shipmentId} AND status = 'DRAFT' AND version = #{expectedVersion} AND is_deleted = 0")
    int unbindShipment(
            @Param("id") Long id,
            @Param("shipmentId") Long shipmentId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("updatedBy") Long updatedBy
    );

    /**
     * 按主键顺序排他锁定运输任务装载清单中的全部交接 (FOR UPDATE)。
     */
    @Select("SELECT * FROM `transfer` WHERE shipment_id = #{shipmentId} AND is_deleted = 0 ORDER BY id ASC FOR UPDATE")
    List<Transfer> selectByShipmentIdForUpdate(@Param("shipmentId") Long shipmentId);

    /**
     * 查询运输任务装载清单中的全部交接（普通读，按主键排序）。
     */
    @Select("SELECT * FROM `transfer` WHERE shipment_id = #{shipmentId} AND is_deleted = 0 ORDER BY id ASC")
    List<Transfer> selectByShipmentId(@Param("shipmentId") Long shipmentId);
}
