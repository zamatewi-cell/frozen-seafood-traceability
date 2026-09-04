/**
 * 通用基础设施与全局协议包 (Common Infrastructure & Global Protocols)。
 * <p>
 * 本包包含系统全局通用的基础设施组件与交互协议规范：
 * <ul>
 *   <li>统一成功响应封装：{@code com.example.traceability.common.envelope.SuccessEnvelope}</li>
 *   <li>RFC 9457 统一错误响应模型：{@code com.example.traceability.common.exception.Problem}</li>
 *   <li>全局异常拦截与处理：{@code com.example.traceability.common.exception.GlobalExceptionHandler}</li>
 *   <li>全链路请求追踪拦截器：{@code com.example.traceability.common.filter.RequestIdFilter}</li>
 *   <li>通用分页元数据、工具类、校验注解与配置基类</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：横切关注点与基础设施支撑层。严禁依赖任何业务域包，保证全局通用性与独立性。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.common;
