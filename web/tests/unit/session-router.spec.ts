import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createAppRouter } from '@/router'
import { installAuthGuard, safeRedirectTarget } from '@/router/guards'
import { clearCsrfToken } from '@/api/client'
import { login, logout, resetSessionForTests, useSession } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser } from './helpers/fakeFetch'

const originalFetch = globalThis.fetch

function serverWithSession(initiallyLoggedIn: boolean) {
  let loggedIn = initiallyLoggedIn
  const fake = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => (loggedIn ? { status: 200, body: envelope(sampleUser) } : problem(401, 'AUTH_REQUIRED')),
    'POST /api/v1/auth/login': (call) => {
      const body = call.body as { username: string; password: string }
      if (call.headers['X-CSRF-TOKEN'] !== 'csrf-token-1') return problem(403, 'ACCESS_DENIED')
      if (body.password !== 'correct-password') return problem(401, 'AUTH_CREDENTIALS_INVALID')
      loggedIn = true
      return { status: 200, body: envelope(sampleUser) }
    },
    'POST /api/v1/auth/logout': (call) => {
      if (call.headers['X-CSRF-TOKEN'] !== 'csrf-token-1') return problem(403, 'ACCESS_DENIED')
      loggedIn = false
      return { status: 204 }
    }
  })
  return fake
}

beforeEach(() => {
  resetSessionForTests()
})

const Stub = defineComponent({ render: () => null })

/** 与生产路由同构的最小路由表，用于独立验证守卫逻辑。 */
function createGuardedRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'Login', component: Stub, meta: { guestOnly: true } },
      { path: '/trace/:publicTraceId', name: 'TraceDetail', component: Stub },
      {
        path: '/app',
        component: Stub,
        meta: { requiresAuth: true },
        children: [
          { path: '', component: Stub },
          { path: 'batches', component: Stub },
          { path: 'batches/:id', component: Stub }
        ]
      }
    ]
  })
  installAuthGuard(router)
  return router
}

afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrfToken()
})

describe('enterprise session and route guards', () => {
  it('redirects anonymous access to /app/** to /login with the original target', async () => {
    const { calls } = serverWithSession(false)
    const router = createGuardedRouter()

    await router.push('/app/batches?flowStatus=ACTIVE')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/app/batches?flowStatus=ACTIVE')
    expect(calls.filter((c) => c.path === '/api/v1/me')).toHaveLength(1)
  })

  it('keeps /trace/:publicTraceId anonymous without touching the enterprise session', async () => {
    const { calls } = serverWithSession(false)
    const router = createGuardedRouter()

    await router.push('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')

    expect(router.currentRoute.value.name).toBe('TraceDetail')
    expect(calls.some((c) => c.path === '/api/v1/me')).toBe(false)
  })

  it('restores an existing server session after a page refresh', async () => {
    serverWithSession(true)
    const router = createGuardedRouter()

    await router.push('/app/batches')

    expect(router.currentRoute.value.path).toBe('/app/batches')
    expect(useSession().user.value?.orgName).toBe('东海水产加工有限公司')
  })

  it('sends authenticated users away from /login to /app or a safe redirect target', async () => {
    serverWithSession(true)
    const router = createGuardedRouter()

    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/app')

    await router.push('/login?redirect=/app/batches/5')
    expect(router.currentRoute.value.path).toBe('/app/batches/5')

    await router.push('/login?redirect=https://evil.example/app')
    expect(router.currentRoute.value.path).toBe('/app')
  })

  it('logs in with CSRF, then logout clears state and protected routes return to /login', async () => {
    const { calls } = serverWithSession(false)
    const router = createGuardedRouter()
    await router.push('/login')

    await expect(login('processor_op', 'wrong-password')).rejects.toMatchObject({ status: 401 })
    expect(useSession().isAuthenticated.value).toBe(false)

    await login('processor_op', 'correct-password')
    expect(useSession().isAuthenticated.value).toBe(true)
    await router.push('/app/batches')
    expect(router.currentRoute.value.path).toBe('/app/batches')

    await logout()
    expect(useSession().isAuthenticated.value).toBe(false)
    expect(useSession().user.value).toBeNull()

    await router.replace('/trace')
    await router.push('/app/batches')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/app/batches')
    const writes = calls.filter((c) => c.method === 'POST')
    expect(writes.every((c) => c.headers['X-CSRF-TOKEN'] === 'csrf-token-1')).toBe(true)
  })

  it('protects the production /app route table and keeps /trace anonymous', async () => {
    const { calls } = serverWithSession(false)
    const router = createAppRouter(createMemoryHistory())

    await router.push('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    expect(router.currentRoute.value.name).toBe('TraceDetail')
    expect(calls).toHaveLength(0)

    await router.push('/app')
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/app')
  })

  it('treats an unreachable session check as logged out for protected routes', async () => {
    installFakeFetch({ 'GET /api/v1/me': () => problem(503, 'SERVICE_UNAVAILABLE') })
    const router = createGuardedRouter()

    await router.push('/app')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('only accepts in-app enterprise redirect targets', () => {
    expect(safeRedirectTarget('/app/batches?page=2')).toBe('/app/batches?page=2')
    expect(safeRedirectTarget('/trace/ABC')).toBe('/app')
    expect(safeRedirectTarget('//evil.example')).toBe('/app')
    expect(safeRedirectTarget(undefined)).toBe('/app')
  })
})
