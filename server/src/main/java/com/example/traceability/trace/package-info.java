/**
 * 追溯事件业务包 (Trace Event Domain)。
 * <p>
 * 当前切片负责组织范围内的关键业务事实记录与追加式更正：
 * <ul>
 *   <li>不可变追溯事件：覆盖已确认的十类关键事件，支持双时间体系 (occurredAt / recordedAt)；</li>
 *   <li>追加式更正：保留旧版本，通过明确关联形成无分叉的更正链；</li>
 *   <li>组织隔离与白名单响应：企业数据在 SQL 层按组织过滤，内部持久化字段不对外暴露。</li>
 * </ul>
 * </p>
 * <p>
 * 企业交接、公开追溯查询、温度采集、告警、召回与真实设备接入均不属于当前切片。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.trace;
