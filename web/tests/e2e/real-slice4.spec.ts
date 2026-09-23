import { test, expect, type Browser, type Page, type Response } from '@playwright/test'

/**
 * Phase A / Slice 4 真实浏览器验收（由 scripts/smoke.mjs 在 Slice 3 之后于同一隔离 schema 编排）：自有冷库入库 / 出库。
 *
 * 加工企业账号在真实 Session + CSRF 下操作，禁止 page.route 或任何接口替身：所有请求经 Vite proxy 到真实 Spring Boot，
 * 数据来自隔离 schema 中的真实 MySQL 8.4。证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 *
 * 路径：加工登录 → B2（600kg）冷库入库 → 冷库出库 → B3（360kg）冷库入库 → 冷库出库；
 *       每一步后批次数量、责任组织与双状态保持不变，时间线展示本组织冷库名称。
 */

interface Slice4Expected {
  b2Id: number
  b3Id: number
  coldStoreName: string
  processorOrgName: string
}

const expected: Slice4Expected = JSON.parse(process.env.SLICE4_EXPECTED || '{}')
const processor = { username: process.env.SLICE4_PROCESSOR_USERNAME || '', password: process.env.SLICE4_PROCESSOR_PASSWORD || '' }

interface WriteEvidence {
  method: string
  path: string
  status: number
  requestId: string
  csrf: boolean
  idempotencyKey: boolean
}

function recordWrites(page: Page, evidence: WriteEvidence[]) {
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    const method = response.request().method()
    if (!url.pathname.startsWith('/api/v1/') || method === 'GET') return
    const headers = response.request().headers()
    evidence.push({
      method,
      path: url.pathname,
      status: response.status(),
      requestId: response.headers()['x-request-id'] || '',
      csrf: Boolean(headers['x-csrf-token']),
      idempotencyKey: (headers['idempotency-key'] || '').length >= 16
    })
  })
}

function waitForApi(page: Page, method: string, pathPattern: RegExp) {
  return page.waitForResponse((response) => response.request().method() === method && pathPattern.test(new URL(response.url()).pathname))
}

async function login(browser: Browser, evidence: WriteEvidence[]) {
  expect(processor.username).not.toBe('')
  expect(processor.password).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  recordWrites(page, evidence)
  await page.goto('/login')
  await page.locator('#login-username').fill(processor.username)
  await page.locator('#login-password').fill(processor.password)
  const loginResponse = waitForApi(page, 'POST', /^\/api\/v1\/auth\/login$/)
  await page.getByRole('button', { name: '登录' }).click()
  expect((await loginResponse).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

async function expectBatchUnchanged(page: Page, quantity: string) {
  await expect(page.getByTestId('detail-quantity')).toContainText(quantity)
  await expect(page.getByTestId('detail-remaining-quantity')).toContainText(quantity)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(page.getByTestId('detail-org-name')).toHaveText(expected.processorOrgName)
}

async function recordWarehouse(page: Page, batchId: number, direction: 'in' | 'out') {
  await page.getByTestId(direction === 'in' ? 'warehouse-in' : 'warehouse-out').click()
  await expect(page.getByTestId('warehouse-form')).toHaveAttribute('data-mode', direction === 'in' ? 'WAREHOUSE_IN' : 'WAREHOUSE_OUT')
  const site = page.getByTestId('field-warehouse-site')
  const optionValue = await site.locator('option', { hasText: expected.coldStoreName }).getAttribute('value')
  expect(optionValue).toBeTruthy()
  await site.selectOption(optionValue as string)
  await expect(page.getByTestId('field-warehouse-summary')).toHaveValue(`${direction === 'in' ? '冷库入库' : '冷库出库'}：${expected.coldStoreName}`)
  const post = waitForApi(page, 'POST', new RegExp(`^/api/v1/batches/${batchId}/events$`))
  await page.getByTestId('warehouse-submit').click()
  expect((await post).status()).toBe(201)
  await expect(page.getByTestId('batch-flash')).toContainText(direction === 'in' ? '冷库入库已记录' : '冷库出库已记录')
}

test('Slice 4: PROCESSOR records own cold-store WAREHOUSE_IN / WAREHOUSE_OUT for B2 and B3 without changing the batches', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b2Id).toBeGreaterThan(0)
  expect(expected.b3Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []
  const { context, page } = await login(browser, evidence)

  for (const [batchId, quantity] of [[expected.b2Id, '600 kg'], [expected.b3Id, '360 kg']] as const) {
    await page.goto(`/app/batches/${batchId}`)
    await expectBatchUnchanged(page, quantity)
    await expect(page.getByTestId('warehouse-panel')).toBeVisible()

    await recordWarehouse(page, batchId, 'in')
    await expectBatchUnchanged(page, quantity)
    await recordWarehouse(page, batchId, 'out')
    await expectBatchUnchanged(page, quantity)

    const warehouseEvents = page.locator('[data-testid="trace-event"][data-event-type^="WAREHOUSE_"]')
    await expect(warehouseEvents).toHaveCount(2)
    expect(await warehouseEvents.evaluateAll((els) => els.map((el) => el.getAttribute('data-event-type')))).toEqual(['WAREHOUSE_IN', 'WAREHOUSE_OUT'])
    await expect(page.getByTestId('event-warehouse-site')).toHaveText([expected.coldStoreName, expected.coldStoreName])
  }

  const writes = evidence.filter((e) => e.path.startsWith('/api/v1/batches'))
  expect(writes.map((e) => `${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')} ${e.status}`)).toEqual([
    'POST /api/v1/batches/:id/events 201',
    'POST /api/v1/batches/:id/events 201',
    'POST /api/v1/batches/:id/events 201',
    'POST /api/v1/batches/:id/events 201'
  ])
  for (const w of writes) {
    expect(w.csrf).toBe(true)
    expect(w.idempotencyKey).toBe(true)
  }
  expect(evidence.filter((e) => !e.path.startsWith('/api/v1/batches') && e.path !== '/api/v1/auth/login')).toEqual([])
  console.log('[real-slice4] writes:')
  for (const w of evidence) console.log(`  ${w.method} ${w.path} -> ${w.status} (requestId ${w.requestId})`)
  await context.close()
})
