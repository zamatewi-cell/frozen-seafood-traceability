/**
 * 安全合规与业务审计业务包 (Audit Logging & Compliance Domain)。
 * <p>
 * 本业务域负责系统关键写操作与高危行为的不可篡改追加式审计记录：
 * <ul>
 *   <li>追加式操作日志：记录操作人 (actorId)、组织 (orgId)、动作 (action)、目标资源 (targetObject) 与结果</li>
 *   <li>全链路关联审计：自动捕获请求追踪标识 (requestId) 与客户端网络特征</li>
 *   <li>合规一致性保障：审计日志与核心业务操作保持同一事务一致性提交</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：合规与安全支撑域。审计日志表仅允许追加写入，严禁向任何外部或业务界面开放更新与物理删除接口。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.audit;
