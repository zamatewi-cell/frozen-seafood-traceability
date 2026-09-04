/**
 * 身份与访问控制业务包 (Identity & Access Management Domain)。
 * <p>
 * 本业务域负责系统身份认证、用户账号、角色权限 (RBAC) 以及多租户组织管辖范围：
 * <ul>
 *   <li>系统账号体系：包含操作员、企业管理员与监管员账户生命周期管理</li>
 *   <li>安全会话保持：基于 Spring Security 7 会话与安全 Cookie (TRACESESSION) 机制</li>
 *   <li>租户数据范围：组织数据隔离与权限越权校验 (OrgScope)</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：核心安全与认证上下文。严禁与消费者公开查询模型混用，严禁在数据库中明文存储敏感密码凭据。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.identity;
