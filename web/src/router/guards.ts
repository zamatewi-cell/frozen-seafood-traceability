import type { RouteLocationNormalized, Router } from 'vue-router'
import { ensureSessionRestored, useSession } from '@/stores/session'

/**
 * 企业端路由守卫。
 *
 * - 消费者路由（/trace/**）不触发任何会话检查，保持匿名可访问；
 * - 访问 requiresAuth 路由前确认服务端会话，未登录跳转 /login 并携带 redirect；
 * - 已登录访问 guestOnly 路由（/login）跳转 /app 或安全的 redirect 目标。
 */

/** 只接受站内企业路由作为登录后跳转目标，防止开放重定向。 */
export function safeRedirectTarget(raw: unknown): string {
  if (typeof raw !== 'string') return '/app'
  if (raw === '/app' || raw.startsWith('/app/') || raw.startsWith('/app?')) return raw
  return '/app'
}

export function installAuthGuard(router: Router): void {
  const session = useSession()

  router.beforeEach(async (to: RouteLocationNormalized) => {
    const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
    const guestOnly = to.matched.some((record) => record.meta.guestOnly)
    if (!requiresAuth && !guestOnly) return true

    try {
      await ensureSessionRestored()
    } catch {
      // 会话状态无法确认（网络或服务端错误）：受保护路由按未登录处理，登录页照常展示
      if (requiresAuth) return { path: '/login', query: { redirect: to.fullPath } }
      return true
    }

    if (requiresAuth && !session.isAuthenticated.value) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (guestOnly && session.isAuthenticated.value) {
      return safeRedirectTarget(to.query.redirect)
    }
    return true
  })
}
