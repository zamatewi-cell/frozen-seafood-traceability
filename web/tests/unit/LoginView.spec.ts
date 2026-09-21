import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken } from '@/api/client'
import { resetSessionForTests, useSession } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser } from './helpers/fakeFetch'

const originalFetch = globalThis.fetch

beforeEach(() => resetSessionForTests())
afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrfToken()
})

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  const wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { router, wrapper }
}

describe('LoginView and EnterpriseLayout', () => {
  it('shows a neutral error on bad credentials and never keeps the password', async () => {
    installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/me': () => problem(401, 'AUTH_REQUIRED'),
      'POST /api/v1/auth/login': () => problem(401, 'AUTH_CREDENTIALS_INVALID', '认证凭据无效或账户不可用')
    })
    const { wrapper } = await mountAt('/login')

    await wrapper.find('#login-username').setValue('processor_op')
    await wrapper.find('#login-password').setValue('wrong-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('用户名或密码错误')
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('logs in, lands on the redirect target, shows real session data and logs out', async () => {
    let loggedIn = false
    const { calls } = installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/me': () => (loggedIn ? { status: 200, body: envelope(sampleUser) } : problem(401, 'AUTH_REQUIRED')),
      'POST /api/v1/auth/login': () => {
        loggedIn = true
        return { status: 200, body: envelope(sampleUser) }
      },
      'POST /api/v1/auth/logout': () => {
        loggedIn = false
        return { status: 204 }
      }
    })
    const { router, wrapper } = await mountAt('/app')
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/app')

    await wrapper.find('#login-username').setValue('processor_op')
    await wrapper.find('#login-password').setValue('correct-password')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/app')
    const session = wrapper.find('[data-testid="workbench-session"]')
    expect(session.text()).toContain('加工操作员')
    expect(session.text()).toContain('东海水产加工有限公司')
    expect(session.text()).toContain('ORG_PROC_01')
    // 工作台只使用真实会话信息：除认证接口外不请求任何统计或业务数据
    expect(new Set(calls.map((c) => c.path))).toEqual(new Set(['/api/v1/me', '/api/v1/auth/csrf', '/api/v1/auth/login']))

    await wrapper.find('.logout-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(useSession().user.value).toBeNull()
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.headers['X-CSRF-TOKEN'])).toEqual(['csrf-token-1', 'csrf-token-1'])
    wrapper.unmount()
  })
})
