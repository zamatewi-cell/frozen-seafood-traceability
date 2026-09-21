import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * 企业端外壳的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的全链路验收见 real-smoke.spec.ts（不使用任何拦截）。
 */

const user = {
  userId: 7,
  username: 'processor_op',
  displayName: '加工操作员',
  orgId: 30,
  orgNo: 'ORG_PROC_01',
  orgName: '东海水产加工有限公司',
  orgType: 'PROCESSOR',
  roles: ['OPERATOR'],
  scopes: ['ORG_ONLY']
}

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body)
  })
}

const meta = { requestId: 'req-e2e', timestamp: '2026-09-21T08:00:00Z' }

async function installFakeAuthBackend(page: Page) {
  const state = { loggedIn: false, meCalls: 0, csrfHeaders: [] as (string | undefined)[] }
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, 200, { data: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'e2e-csrf' }, meta }))
  await page.route('**/api/v1/me', (route) => {
    state.meCalls += 1
    return state.loggedIn
      ? json(route, 200, { data: user, meta })
      : json(route, 401, { status: 401, code: 'AUTH_REQUIRED', title: '需要登录' })
  })
  await page.route('**/api/v1/auth/login', (route) => {
    state.csrfHeaders.push(route.request().headers()['x-csrf-token'])
    state.loggedIn = true
    return json(route, 200, { data: user, meta })
  })
  await page.route('**/api/v1/auth/logout', (route) => {
    state.csrfHeaders.push(route.request().headers()['x-csrf-token'])
    state.loggedIn = false
    return route.fulfill({ status: 204 })
  })
  return state
}

test.describe('Enterprise shell', () => {
  test('anonymous /app is redirected to /login and login lands on the workbench', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)

    await page.goto('/app')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    await expect(page.getByRole('heading', { name: '企业用户登录' })).toBeVisible()

    await page.locator('#login-username').fill('processor_op')
    await page.locator('#login-password').fill('not-a-real-password')
    await page.getByRole('button', { name: '登录' }).click()

    await expect(page).toHaveURL(/\/app$/)
    await expect(page.getByTestId('workbench-session')).toContainText('东海水产加工有限公司')

    await page.reload()
    await expect(page.getByTestId('workbench-session')).toContainText('加工操作员')

    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login/)
    await page.goto('/app')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(backend.csrfHeaders).toEqual(['e2e-csrf', 'e2e-csrf'])
  })

  test('consumer trace route stays anonymous and never checks the enterprise session', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)
    await page.route('**/api/public/v1/public/traces/*', (route) =>
      json(route, 404, { status: 404, code: 'PUBLIC_TRACE_NOT_FOUND', title: '未找到' }))

    await page.goto('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    await expect(page).toHaveURL(/\/trace\/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC$/)
    await expect(page.getByText('未找到该追溯码').first()).toBeVisible()
    expect(backend.meCalls).toBe(0)
  })
})
