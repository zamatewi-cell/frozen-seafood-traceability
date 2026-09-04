# ADR-004：身份认证方式

- 状态：接受
- 日期：2026-09-03

## 决策

PC 企业端采用 Spring Security 服务端会话；浏览器仅持有 `HttpOnly + Secure + SameSite=Lax` Cookie，写请求启用 CSRF 防护。消费者公开查询无需登录，但需要限流和不可猜测公开 ID。

## 原因

- 前后端计划同源部署，无需把长期令牌暴露给浏览器脚本。
- 会话便于注销、停用用户和集中权限变更；比把 JWT 放在 `localStorage` 更不易被脚本直接读取。
- MVP 单体部署不需要 OAuth2 授权服务器复杂度。

## 后果

- 本地和部署环境需保持同源或正确配置反向代理；跨站部署需重新评估 Cookie 与 CSRF。
- 单实例开发可使用内存会话；共享环境使用 Spring Session JDBC，并纳入清理策略。
- 未来接入第三方客户端时另建 OAuth2/OIDC ADR。

