/**
 * 追溯事件与谱系图谱业务包 (Trace Event & Genealogy Domain)。
 * <p>
 * 本业务域负责业务事实记录与追溯图谱的构建与查询：
 * <ul>
 *   <li>不可变追溯事件：包含捕捞出海、入库冷藏、出库运输等关键事件，支持双时间体系 (occurredAt / recordedAt)</li>
 *   <li>企业转接交接：跨企业主体流转过程中的发货签收与双向确认机制</li>
 *   <li>全链路追溯图谱：基于有向无环图 (DAG) 的正向原料追踪与反向溯源图谱遍历</li>
 *   <li>消费者公开投影：严格遵循安全白名单策略，面向公众终端提供脱敏后的透明溯源视图</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：追溯核心查询域。已生效追溯事实严禁物理删除，公开端严禁泄露内部主键、设备标识与敏感操作人信息。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.trace;
