/**
 * 批次与物料平衡业务包 (Batch & Mass Balance Domain)。
 * <p>
 * 本业务域负责冷冻海鲜全生命周期的批次流转与物料守恒计算：
 * <ul>
 *   <li>批次生命周期状态机：草稿 (DRAFT)、生效 (ACTIVE)、冻结 (FROZEN)、召回 (RECALLED) 与关闭 (CLOSED)</li>
 *   <li>批次生产与操作行为：合并 (MERGE)、拆分 (SPLIT)、加工 (PROCESS) 与重新包装 (REPACK)</li>
 *   <li>物料平衡与守恒校验：严格计算 INPUT 投入与 OUTPUT/LOSS/WASTE 产出平衡，容差控制在 0.001 kg</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：核心交易与物料核心域。严禁信任客户端合计数值，严格在领域服务层完成物料守恒计算。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.batch;
