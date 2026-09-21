import { test, expect, type Page, type Response } from '@playwright/test'

/**
 * Phase 0 + Phase A / Slice 1 真实浏览器冒烟（由 scripts/smoke.mjs 编排）。
 *
 * 本文件禁止使用 page.route 或任何接口替身：所有请求经 Vite proxy 到真实 Spring Boot，
 * 数据来自隔离 schema 中的真实 MySQL 8.4。每个关键请求都校验来自后端的 X-Request-Id。
 * 证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 */

interface Expected {
  orgName: string
  fishName: string
  active: string
  activeId: number
  draft: string
  frozen: string
  recalled: string
  closed: string
  foreign: string
  publicTraceId: string
}

const expected: Expected = JSON.parse(process.env.SMOKE_EXPECTED || '{}')
const username = process.env.SMOKE_USERNAME || ''
const password = process.env.SMOKE_PASSWORD || ''

interface EvidenceLine {
  method: string
  path: string
  status: number
  requestId: string
  csrfHeaderSent: boolean
}

function recordApiTraffic(page: Page, evidence: EvidenceLine[]) {
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    evidence.push({
      method: response.request().method(),
      path: `${url.pathname}${url.search}`,
      status: response.status(),
      requestId: response.headers()['x-request-id'] || '',
      csrfHeaderSent: Boolean(response.request().headers()['x-csrf-token'])
    })
  })
}

function waitForApi(page: Page, method: string, predicate: (url: URL) => boolean) {
  return page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === method && predicate(url)
  })
}

async function traceNumbersOnPage(page: Page): Promise<string[]> {
  return (await page.locator('.batch-row .trace-link').allTextContents()).map((text) => text.trim()).sort()
}

test.describe.configure({ mode: 'serial' })

test('enterprise login, real batch list/detail, session restore and logout', async ({ page }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(username).not.toBe('')
  expect(password).not.toBe('')
  const evidence: EvidenceLine[] = []
  recordApiTraffic(page, evidence)

  // 1. /login → 真实账号登录（CSRF + Session）
  await page.goto('/login')
  await page.locator('#login-username').fill(username)
  await page.locator('#login-password').fill(password)
  const loginResponse = waitForApi(page, 'POST', (url) => url.pathname === '/api/v1/auth/login')
  await page.getByRole('button', { name: '登录' }).click()
  const login = await loginResponse
  expect(login.status()).toBe(200)
  expect(login.request().headers()['x-csrf-token']).toBeTruthy()

  // 2. /app 工作台展示真实会话信息
  await expect(page).toHaveURL(/\/app$/)
  await expect(page.getByTestId('workbench-session')).toContainText(expected.orgName)

  // 3. /app/batches 从真实 MySQL 读取本组织批次，其他组织批次不可见
  const listResponse = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/batches')
  await page.getByRole('link', { name: '批次', exact: true }).click()
  expect((await listResponse).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(5)
  expect(await traceNumbersOnPage(page)).toEqual(
    [expected.active, expected.draft, expected.frozen, expected.recalled, expected.closed].sort()
  )
  expect(await traceNumbersOnPage(page)).not.toContain(expected.foreign)
  const activeRow = page.locator('.batch-row', { hasText: expected.active })
  await expect(activeRow).toContainText('P0-EXT-001')
  await expect(activeRow).toContainText(expected.fishName)
  await expect(activeRow).toContainText('1,000 kg')
  await expect(activeRow).toContainText(expected.orgName)
  await expect(page.locator('.batch-row', { hasText: expected.draft })).toContainText('P0-EXT-001')
  await expect(page.locator('.batch-row', { hasText: expected.frozen })).toContainText('冻结')
  await expect(page.locator('.batch-row', { hasText: expected.recalled })).toContainText('模拟召回')

  // 4. flowStatus 筛选
  let filtered = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/batches' && url.searchParams.get('flowStatus') === 'ACTIVE')
  await page.getByTestId('filter-flow-status').selectOption('ACTIVE')
  expect((await filtered).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(2)
  expect(await traceNumbersOnPage(page)).toEqual([expected.active, expected.frozen].sort())

  // 5. riskStatus 筛选（与流转状态叠加）
  filtered = waitForApi(page, 'GET', (url) => url.searchParams.get('riskStatus') === 'FROZEN' && url.searchParams.get('flowStatus') === 'ACTIVE')
  await page.getByTestId('filter-risk-status').selectOption('FROZEN')
  expect((await filtered).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(1)
  expect(await traceNumbersOnPage(page)).toEqual([expected.frozen])

  // 清除筛选后，单独按 RECALLED 风险状态筛选（CLOSED + RECALLED 可以同时存在）
  filtered = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/batches' && !url.searchParams.has('flowStatus') && !url.searchParams.has('riskStatus'))
  await page.getByTestId('clear-filters').click()
  expect((await filtered).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(5)
  filtered = waitForApi(page, 'GET', (url) => url.searchParams.get('riskStatus') === 'RECALLED')
  await page.getByTestId('filter-risk-status').selectOption('RECALLED')
  expect((await filtered).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(1)
  await expect(page.locator('.batch-row')).toContainText('已关闭')
  filtered = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/batches' && !url.searchParams.has('riskStatus'))
  await page.getByTestId('clear-filters').click()
  expect((await filtered).status()).toBe(200)
  await expect(page.locator('.batch-row')).toHaveCount(5)

  // 6. 进入 /app/batches/:id 真实详情
  const detailResponse = waitForApi(page, 'GET', (url) => url.pathname === `/api/v1/batches/${expected.activeId}`)
  await page.locator('.batch-row', { hasText: expected.active }).click()
  expect((await detailResponse).status()).toBe(200)
  await expect(page).toHaveURL(new RegExp(`/app/batches/${expected.activeId}$`))
  await expect(page.getByTestId('detail-trace-batch-no')).toHaveText(expected.active)
  await expect(page.getByTestId('detail-external-batch-no')).toHaveText('P0-EXT-001')
  await expect(page.getByTestId('detail-product-name')).toHaveText(expected.fishName)
  await expect(page.getByTestId('detail-quantity')).toHaveText('1,000 kg')
  await expect(page.getByTestId('detail-org-name')).toHaveText(expected.orgName)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')

  // 7. 刷新浏览器 → Session 恢复（GET /api/v1/me 200）
  const meAfterReload = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/me')
  await page.reload()
  expect((await meAfterReload).status()).toBe(200)
  await expect(page).toHaveURL(new RegExp(`/app/batches/${expected.activeId}$`))
  await expect(page.getByTestId('detail-trace-batch-no')).toHaveText(expected.active)

  // 8. 返回列表
  await page.getByTestId('back-to-list').click()
  await expect(page).toHaveURL(/\/app\/batches$/)
  await expect(page.locator('.batch-row')).toHaveCount(5)

  // 9. 登出（CSRF 写请求）
  const logoutResponse = waitForApi(page, 'POST', (url) => url.pathname === '/api/v1/auth/logout')
  await page.getByRole('button', { name: '退出登录' }).click()
  const logout = await logoutResponse
  expect(logout.status()).toBe(204)
  expect(logout.request().headers()['x-csrf-token']).toBeTruthy()
  await expect(page).toHaveURL(/\/login$/)

  // 10. 再次访问 /app/batches → 服务端确认未登录 → 回到 /login
  const meAfterLogout = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/me')
  await page.goto('/app/batches')
  expect((await meAfterLogout).status()).toBe(401)
  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)app(%2F|\/)batches$/)

  // 所有企业 API 响应都来自真实后端（带服务端生成的请求编号）
  for (const line of evidence) expect(line.requestId, `${line.method} ${line.path}`).not.toBe('')
  expect(evidence.filter((line) => line.method !== 'GET').every((line) => line.csrfHeaderSent)).toBe(true)
  console.log('[real-smoke] enterprise API evidence (method path status requestId csrfHeaderSent):')
  for (const line of evidence) console.log(`  ${line.method} ${line.path} ${line.status} ${line.requestId} ${line.csrfHeaderSent}`)
})

test('consumer trace stays anonymous against the real backend', async ({ browser }) => {
  const context = await browser.newContext()
  const page = await context.newPage()
  const evidence: EvidenceLine[] = []
  recordApiTraffic(page, evidence)

  const traceResponse = waitForApi(page, 'GET', (url) => url.pathname === `/api/public/v1/public/traces/${expected.publicTraceId}`)
  await page.goto(`/trace/${expected.publicTraceId}`)
  expect((await traceResponse).status()).toBe(200)
  await expect(page).toHaveURL(new RegExp(`/trace/${expected.publicTraceId}$`))
  await expect(page.locator('h1.product-title')).toHaveText(expected.fishName)
  await expect(page.getByTestId('public-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('public-risk-status')).toHaveText('正常')
  await expect(page.locator('.consumer-hero-card .status-badge')).toContainText('当前记录正常')
  await expect(page.locator('.temp-summary-card')).toContainText('暂无实时时序采集')
  // 公开页只显示掩码后的外部批号，绝不出现 traceBatchNo
  await expect(page.locator('body')).not.toContainText(expected.active)

  expect(evidence.some((line) => line.path.startsWith('/api/v1/'))).toBe(false)
  for (const line of evidence) expect(line.requestId).not.toBe('')
  console.log('[real-smoke] consumer API evidence:')
  for (const line of evidence) console.log(`  ${line.method} ${line.path} ${line.status} ${line.requestId}`)
  await context.close()
})

test('source operator creates SRC-2026-001 in the browser, activates it and sees exactly one SOURCE event', async ({ page }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  const evidence: EvidenceLine[] = []
  recordApiTraffic(page, evidence)
  const idempotencyKeys: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && url.pathname === '/api/v1/batches') {
      idempotencyKeys.push(request.headers()['idempotency-key'] || '')
    }
  })

  // 1. /login → 来源组织 OPERATOR 登录
  await page.goto('/login')
  await page.locator('#login-username').fill(username)
  await page.locator('#login-password').fill(password)
  const loginResponse = waitForApi(page, 'POST', (url) => url.pathname === '/api/v1/auth/login')
  await page.getByRole('button', { name: '登录' }).click()
  expect((await loginResponse).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)

  // 2. /app → 新建来源批次（产品下拉来自真实 GET /api/v1/products?status=ACTIVE）
  const productsResponse = waitForApi(page, 'GET', (url) => url.pathname === '/api/v1/products' && url.searchParams.get('status') === 'ACTIVE')
  await page.getByTestId('entry-new-source-batch').click()
  expect((await productsResponse).status()).toBe(200)
  await expect(page).toHaveURL(/\/app\/batches\/new$/)
  const productOption = page.getByTestId('field-product').locator('option', { hasText: expected.fishName })
  await expect(productOption).toHaveCount(1)
  await page.getByTestId('field-product').selectOption({ value: await productOption.getAttribute('value') ?? '' })
  await page.getByTestId('field-external-batch-no').fill('SRC-2026-001')
  await page.getByTestId('field-quantity').fill('1000')
  await expect(page.getByTestId('field-unit')).toHaveText('kg')
  await page.getByTestId('field-origin-type').selectOption('DOMESTIC_CAPTURE')
  await page.getByTestId('field-origin-text').fill('东海舟山渔场（浏览器真实建批）')
  await page.getByTestId('field-capture-date').fill('2026-09-20')

  // 3. 保存草稿（CSRF + Idempotency-Key）→ 详情 DRAFT/NORMAL 与服务端 traceBatchNo
  const createResponse = waitForApi(page, 'POST', (url) => url.pathname === '/api/v1/batches')
  await page.getByTestId('save-draft').click()
  const create = await createResponse
  expect(create.status()).toBe(201)
  expect(create.request().headers()['x-csrf-token']).toBeTruthy()
  const createBody = create.request().postDataJSON() as Record<string, unknown>
  for (const serverField of ['batchType', 'traceBatchNo', 'orgId', 'creationOrgId', 'flowStatus', 'riskStatus', 'status', 'version']) {
    expect(createBody).not.toHaveProperty(serverField)
  }
  const created = (await create.json()).data as { id: number; traceBatchNo: string }
  expect(created.traceBatchNo).toMatch(/^TB-[0-9A-Z]{26}$/)
  await expect(page).toHaveURL(new RegExp(`/app/batches/${created.id}$`))
  await expect(page.getByTestId('detail-trace-batch-no')).toHaveText(created.traceBatchNo)
  await expect(page.getByTestId('detail-external-batch-no')).toHaveText('SRC-2026-001')
  await expect(page.getByTestId('detail-quantity')).toHaveText('1,000 kg')
  await expect(page.getByTestId('detail-flow-status')).toHaveText('草稿')
  await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(page.getByTestId('events-empty')).toBeVisible()

  // 4. 提交激活 → ACTIVE/NORMAL，事件区恰好一条 SOURCE
  const submitResponse = waitForApi(page, 'POST', (url) => url.pathname === `/api/v1/batches/${created.id}/submit`)
  await page.getByTestId('submit-activation').click()
  const submit = await submitResponse
  expect(submit.status()).toBe(200)
  expect(submit.request().headers()['x-csrf-token']).toBeTruthy()
  expect(submit.request().postDataJSON()).toEqual({ version: 0 })
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(page.getByTestId('trace-event')).toHaveCount(1)
  await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'SOURCE')
  await expect(page.getByTestId('trace-event')).toContainText('东海舟山渔场（浏览器真实建批）')
  await expect(page.getByTestId('trace-event')).toContainText(created.traceBatchNo)

  // 5. 刷新页面 → 数据仍来自服务端
  const eventsAfterReload = waitForApi(page, 'GET', (url) => url.pathname === `/api/v1/batches/${created.id}/events`)
  await page.reload()
  expect((await eventsAfterReload).status()).toBe(200)
  await expect(page.getByTestId('detail-trace-batch-no')).toHaveText(created.traceBatchNo)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('trace-event')).toHaveCount(1)
  await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'SOURCE')

  // 6. 返回批次列表 → 看到该批次（原 5 条夹具 + 浏览器新建 1 条）
  await page.getByTestId('back-to-list').click()
  await expect(page).toHaveURL(/\/app\/batches$/)
  await expect(page.locator('.batch-row')).toHaveCount(6)
  const newRow = page.locator('.batch-row', { hasText: created.traceBatchNo })
  await expect(newRow).toContainText('SRC-2026-001')
  await expect(newRow).toContainText('可流转')

  expect(idempotencyKeys).toHaveLength(1)
  expect(idempotencyKeys[0].length).toBeGreaterThanOrEqual(16)
  for (const line of evidence) expect(line.requestId, `${line.method} ${line.path}`).not.toBe('')
  expect(evidence.filter((line) => line.method !== 'GET').every((line) => line.csrfHeaderSent)).toBe(true)
  console.log('[real-smoke] source batch API evidence (method path status requestId csrfHeaderSent):')
  for (const line of evidence) console.log(`  ${line.method} ${line.path} ${line.status} ${line.requestId} ${line.csrfHeaderSent}`)
})
