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

async function installFakeAuthBackend(page: Page, currentUser: typeof user = user) {
  const state = { loggedIn: false, meCalls: 0, csrfHeaders: [] as (string | undefined)[] }
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, 200, { data: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'e2e-csrf' }, meta }))
  await page.route('**/api/v1/me', (route) => {
    state.meCalls += 1
    return state.loggedIn
      ? json(route, 200, { data: currentUser, meta })
      : json(route, 401, { status: 401, code: 'AUTH_REQUIRED', title: '需要登录' })
  })
  await page.route('**/api/v1/auth/login', (route) => {
    state.csrfHeaders.push(route.request().headers()['x-csrf-token'])
    state.loggedIn = true
    return json(route, 200, { data: currentUser, meta })
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

  test('batch list filters through the URL and opens a read-only detail', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)
    backend.loggedIn = true
    const batch = {
      id: 12, orgId: 30, productId: 5, traceBatchNo: 'TB-AAAAAAAAAAAAAAAAAAAAAAAAAA', externalBatchNo: 'SUP-2026-001',
      batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
      flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 1
    }
    const listQueries: string[] = []
    await page.route('**/api/v1/batches?*', (route) => {
      listQueries.push(new URL(route.request().url()).search)
      return json(route, 200, { data: [batch], meta: { ...meta, page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } } })
    })
    await page.route('**/api/v1/batches/12', (route) => json(route, 200, { data: batch, meta }))
    await page.route('**/api/v1/batches/12/events', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/transfers?*', (route) => json(route, 200, { data: [], meta: { ...meta, page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } } }))
    await page.route('**/api/v1/products/5', (route) => json(route, 200, {
      data: { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 },
      meta
    }))
    await page.route('**/api/v1/organizations/30', (route) => json(route, 200, {
      data: { id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' },
      meta
    }))

    await page.goto('/app/batches')
    await expect(page.locator('.batch-row')).toHaveCount(1)
    await expect(page.locator('.batch-row')).toContainText('冷冻大黄鱼')

    await page.getByTestId('filter-flow-status').selectOption('ACTIVE')
    await expect(page).toHaveURL(/flowStatus=ACTIVE/)
    await page.getByTestId('filter-risk-status').selectOption('NORMAL')
    await expect(page).toHaveURL(/riskStatus=NORMAL/)
    await expect.poll(() => listQueries.at(-1)).toContain('riskStatus=NORMAL')

    await page.locator('.batch-row').first().click()
    await expect(page).toHaveURL(/\/app\/batches\/12$/)
    await expect(page.getByTestId('detail-trace-batch-no')).toHaveText('TB-AAAAAAAAAAAAAAAAAAAAAAAAAA')
    await expect(page.getByTestId('detail-org-name')).toHaveText('东海水产加工有限公司')

    await page.getByTestId('back-to-list').click()
    await expect(page).toHaveURL(/flowStatus=ACTIVE&riskStatus=NORMAL/)
    // 加工企业不是来源组织：列表不提供新建来源批次入口
    await expect(page.getByTestId('new-source-batch')).toHaveCount(0)
  })

  test('source operator creates a draft, activates it on the detail page and sees the SOURCE event', async ({ page }) => {
    const sourceUser = { ...user, userId: 9, username: 'source_op', displayName: '来源操作员', orgId: 40, orgNo: 'ORG_SRC_01', orgName: '东海远洋捕捞有限公司', orgType: 'SOURCE' }
    const backend = await installFakeAuthBackend(page, sourceUser)
    backend.loggedIn = true
    const product = { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
    let batch: Record<string, unknown> | null = null
    let events: unknown[] = []
    const writes: { path: string; body: unknown; csrf?: string; idempotencyKey?: string }[] = []

    await page.route('**/api/v1/products?*', (route) => json(route, 200, {
      data: [product, { ...product, id: 8, publicName: '停用产品', status: 'INACTIVE' }],
      meta: { ...meta, page: { number: 1, size: 100, totalElements: 2, totalPages: 1 } }
    }))
    await page.route('**/api/v1/products/5', (route) => json(route, 200, { data: product, meta }))
    await page.route('**/api/v1/organizations/40', (route) => json(route, 200, {
      data: { id: 40, orgNo: 'ORG_SRC_01', name: '东海远洋捕捞有限公司', orgType: 'SOURCE', status: 'ACTIVE' }, meta
    }))
    await page.route('**/api/v1/batches', (route) => {
      const request = route.request()
      writes.push({ path: '/api/v1/batches', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'], idempotencyKey: request.headers()['idempotency-key'] })
      batch = {
        id: 101, orgId: 40, productId: 5, traceBatchNo: 'TB-E2ESERVERGENERATED000001', externalBatchNo: 'SRC-2026-001',
        batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
        flowStatus: 'DRAFT', riskStatus: 'NORMAL', version: 0
      }
      return json(route, 201, { data: batch, meta })
    })
    await page.route('**/api/v1/batches/101', (route) => json(route, 200, { data: batch, meta }))
    await page.route('**/api/v1/batches/101/events', (route) => json(route, 200, { data: events, meta }))
    await page.route('**/api/v1/transfers?*', (route) => json(route, 200, { data: [], meta: { ...meta, page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } } }))
    await page.route('**/api/v1/batches/101/submit', (route) => {
      const request = route.request()
      writes.push({ path: '/api/v1/batches/101/submit', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'] })
      batch = { ...batch, flowStatus: 'ACTIVE', version: 1 }
      events = [{
        id: 900, batchId: 101, orgId: 40, eventType: 'SOURCE', occurredAt: '2026-09-21T08:30:00.000Z', recordedAt: '2026-09-21T08:30:00.000Z',
        operatorId: 9, dataSource: 'MANUAL', status: 'SUBMITTED', summary: '来源批次激活：东海舟山渔场',
        detailsJson: { sourceObjectType: 'BATCH', sourceObjectId: 101, traceBatchNo: 'TB-E2ESERVERGENERATED000001', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', quantity: '1000.000', unitCode: 'kg' }
      }]
      return json(route, 200, { data: batch, meta })
    })

    await page.goto('/app')
    await page.getByTestId('entry-new-source-batch').click()
    await expect(page).toHaveURL(/\/app\/batches\/new$/)
    await expect(page.getByTestId('field-product').locator('option')).toHaveCount(2)
    await page.getByTestId('field-product').selectOption('5')
    await page.getByTestId('field-external-batch-no').fill('SRC-2026-001')
    await page.getByTestId('field-quantity').fill('1000')
    await expect(page.getByTestId('field-unit')).toHaveText('kg')
    await page.getByTestId('field-origin-type').selectOption('DOMESTIC_CAPTURE')
    await page.getByTestId('field-origin-text').fill('东海舟山渔场')
    await page.getByTestId('save-draft').dblclick()

    await expect(page).toHaveURL(/\/app\/batches\/101$/)
    await expect(page.getByTestId('detail-flow-status')).toHaveText('草稿')
    await expect(page.getByTestId('detail-trace-batch-no')).toHaveText('TB-E2ESERVERGENERATED000001')
    await expect(page.getByTestId('events-empty')).toBeVisible()

    await page.getByTestId('submit-activation').click()
    await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
    await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
    await expect(page.getByTestId('trace-event')).toHaveCount(1)
    await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'SOURCE')

    const creates = writes.filter((w) => w.path === '/api/v1/batches')
    expect(creates).toHaveLength(1)
    expect(creates[0].csrf).toBe('e2e-csrf')
    expect(creates[0].idempotencyKey?.length).toBeGreaterThanOrEqual(16)
    expect(Object.keys(creates[0].body as object).sort()).toEqual(['externalBatchNo', 'originText', 'originType', 'productId', 'quantity', 'unitCode'])
    const submits = writes.filter((w) => w.path === '/api/v1/batches/101/submit')
    expect(submits).toEqual([{ path: '/api/v1/batches/101/submit', body: { version: 0 }, csrf: 'e2e-csrf' }])
  })
})
