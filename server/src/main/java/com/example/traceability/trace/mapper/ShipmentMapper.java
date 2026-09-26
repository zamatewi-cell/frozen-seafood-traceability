package com.example.traceability.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.trace.domain.Shipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 冷链运输任务持久层访问接口。
 * <p>
 * 读取在 SQL 层强制 sender/carrier/receiver 三方组织范围隔离；
 * 状态变更采用 "期望状态 + 乐观锁版本" 条件更新，并统一遵循 shipment → transfer → batch 行锁顺序。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Mapper
public interface ShipmentMapper extends BaseMapper<Shipment> {

    /**
     * 根据主键排他锁定运输任务 (FOR UPDATE)。
     */
    @Select("SELECT * FROM `shipment` WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Shipment selectByIdForUpdate(@Param("id") Long id);

    /**
     * 根据主键共享锁定运输任务 (FOR SHARE)，用于接收方决定时稳定读取到达状态。
     */
    @Select("SELECT * FROM `shipment` WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    Shipment selectByIdForShare(@Param("id") Long id);

    /**
     * 当前读（FOR SHARE）运输任务最新一条在途温度记录的测量时间；没有记录时返回 null（Phase B PB2）。
     * <p>
     * 只能在已持有该运输任务行锁（FOR UPDATE）之后调用，保持 shipment → temperature_record 的锁顺序：温度登记同样先锁
     * 运输任务行再插入记录，因此持锁后读到的最新测量时间稳定。必须是锁定 / 当前读而不是普通一致性读：确认到达在取锁之前的
     * 幂等预读已在 REPEATABLE READ 下建立读视图，普通读会看不到等待行锁期间已提交的温度记录。
     * </p>
     */
    @Select("SELECT measured_at FROM temperature_record WHERE shipment_id = #{shipmentId} AND is_deleted = 0 "
            + "ORDER BY measured_at DESC, id DESC LIMIT 1 FOR SHARE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    LocalDateTime selectLatestTemperatureMeasuredAtForShare(@Param("shipmentId") Long shipmentId);

    /**
     * 在发送方、承运方或接收方组织范围内查询运输任务；第三方组织返回 null。
     */
    @Select("SELECT * FROM `shipment` WHERE id = #{id} AND is_deleted = 0 " +
            "AND (sender_org_id = #{orgId} OR carrier_org_id = #{orgId} OR receiver_org_id = #{orgId})")
    Shipment selectByIdAndPartyScope(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * 判断运输任务是否存在（用于区分 404 与跨组织 403）。
     */
    @Select("SELECT COUNT(1) FROM `shipment` WHERE id = #{id} AND is_deleted = 0")
    int existsByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 按主键批量读取运输任务（仅供已完成组织范围校验的调用方做展示字段补全）。
     */
    @Select("<script>SELECT * FROM `shipment` WHERE is_deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Shipment> selectByIdsIgnoreTenant(@Param("ids") Collection<Long> ids);

    /**
     * 以期望状态与期望版本为条件更新生命周期字段 (发运 / 到达 / 取消)。
     *
     * @return 影响行数 (1: 成功, 0: 状态或版本已被并发修改)
     */
    @Update("""
            UPDATE `shipment`
            SET status = #{entity.status},
                loaded_at = #{entity.loadedAt},
                unloaded_at = #{entity.unloadedAt},
                dispatched_recorded_at = #{entity.dispatchedRecordedAt},
                dispatched_by = #{entity.dispatchedBy},
                delivered_recorded_at = #{entity.deliveredRecordedAt},
                delivered_by = #{entity.deliveredBy},
                cancelled_recorded_at = #{entity.cancelledRecordedAt},
                cancelled_by = #{entity.cancelledBy},
                cancel_reason = #{entity.cancelReason},
                version = version + 1,
                updated_at = NOW(6),
                updated_by = #{entity.updatedBy}
            WHERE id = #{entity.id}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
              AND is_deleted = 0
            """)
    int updateLifecycleByIdAndVersion(
            @Param("entity") Shipment entity,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") Long expectedVersion
    );

    /**
     * 装载清单变化 (绑定 / 解绑 / 删除已绑定草稿) 时递增运输任务版本，
     * 使持有旧版本号的并发发运请求被乐观锁检测为冲突。仅 PLANNED 运输任务允许变更装载清单。
     *
     * @return 影响行数 (1: 成功, 0: 运输任务已非 PLANNED)
     */
    @Update("UPDATE `shipment` SET version = version + 1, updated_at = NOW(6), updated_by = #{updatedBy} " +
            "WHERE id = #{id} AND status = 'PLANNED' AND is_deleted = 0")
    int bumpManifestVersion(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /**
     * 组织范围内运输任务总数。
     *
     * @param orgId  当前组织 ID
     * @param role   视角 (SENDER / CARRIER / RECEIVER；null 表示任一参与方)
     * @param status 状态筛选
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM `shipment` WHERE is_deleted = 0 " +
            "<choose>" +
            "  <when test='\"SENDER\".equals(role)'> AND sender_org_id = #{orgId} </when>" +
            "  <when test='\"CARRIER\".equals(role)'> AND carrier_org_id = #{orgId} </when>" +
            "  <when test='\"RECEIVER\".equals(role)'> AND receiver_org_id = #{orgId} </when>" +
            "  <otherwise> AND (sender_org_id = #{orgId} OR carrier_org_id = #{orgId} OR receiver_org_id = #{orgId}) </otherwise>" +
            "</choose>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "</script>")
    long countShipments(@Param("orgId") Long orgId, @Param("role") String role, @Param("status") String status);

    /**
     * 组织范围内运输任务分页列表 (按 updated_at DESC, id DESC 稳定排序)。
     */
    @Select("<script>" +
            "SELECT * FROM `shipment` WHERE is_deleted = 0 " +
            "<choose>" +
            "  <when test='\"SENDER\".equals(role)'> AND sender_org_id = #{orgId} </when>" +
            "  <when test='\"CARRIER\".equals(role)'> AND carrier_org_id = #{orgId} </when>" +
            "  <when test='\"RECEIVER\".equals(role)'> AND receiver_org_id = #{orgId} </when>" +
            "  <otherwise> AND (sender_org_id = #{orgId} OR carrier_org_id = #{orgId} OR receiver_org_id = #{orgId}) </otherwise>" +
            "</choose>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "ORDER BY updated_at DESC, id DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Shipment> selectShipmentsPage(
            @Param("orgId") Long orgId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("size") int size
    );
}
