import { test, expect, type Browser, type Page, type Response } from '@playwright/test'

/**
 * Phase A / Slice 2 真实浏览器验收（由 scripts/smoke.mjs 编排）：Transfer + Shipment。
 *
 * 三个相互隔离的浏览器上下文分别代表来源企业、承运企业与加工企业（真实 Session + CSRF），
 * 禁止 page.route 或任何接口替身：所有请求经 Vite proxy 到真实 Spring Boot，数据来自隔离 schema 中的真实 MySQL 8.4。
 * 证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 *
 * 路径：来源登录 → B0 详情 → 发起交接 T0 → 创建运输任务 S0 并装载 → 提交 T0
 *       → 承运登录 → 查看 S0 → 确认装载发运 → 确认到达
 *       → 加工登录 → 待接收交接 → 接受 T0 → 打开 B0（责任组织为加工企业，TRANSPORT + ARRIVAL 可见）。
 */

interface Slice2Expected {
  b0Id: number
  b0TraceBatchNo: string
  sourceOrgName: string
  carrierOrgName: string
  processorOrgName: string
  sourceSiteName: string
  processorSiteName: string
}

const expected: Slice2Expected = JSON.parse(process.env.SLICE2_EXPECTED || '{}')
const accounts = {
  source: { username: process.env.SLICE2_SOURCE_USERNAME || '', password: process.env.SLICE2_SOURCE_PASSWORD || '' },
  carrier: { username: process.env.SLICE2_CARRIER_USERNAME || '', password: process.env.SLICE2_CARRIER_PASSWORD || '' },
  processor: { username: process.env.SLICE2_PROCESSOR_USERNAME || '', password: process.env.SLICE2_PROCESSOR_PASSWORD || '' }
}

interface WriteEvidence {
  actor: string
  method: string
  path: string
  status: number
  requestId: string
  csrf: boolean
  idempotencyKey: boolean
}

function recordWrites(page: Page, actor: string, evidence: WriteEvidence[]) {
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    const method = response.request().method()
    if (!url.pathname.startsWith('/api/v1/') || method === 'GET') return
    const headers = response.request().headers()
    evidence.push({
      actor,
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

async function selectByText(page: Page, testId: string, text: string) {
  const option = page.getByTestId(testId).locator('option', { hasText: text }).first()
  await expect(option).toHaveCount(1)
  const value = await option.getAttribute('value')
  await page.getByTestId(testId).selectOption(value ?? '')
}

async function loginAs(browser: Browser, account: { username: string; password: string }, actor: string, evidence: WriteEvidence[]) {
  expect(account.username).not.toBe('')
  expect(account.password).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  recordWrites(page, actor, evidence)
  await page.goto('/login')
  await page.locator('#login-username').fill(account.username)
  await page.locator('#login-password').fill(account.password)
  const login = waitForApi(page, 'POST', /^\/api\/v1\/auth\/login$/)
  await page.getByRole('button', { name: '登录' }).click()
  expect((await login).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

test('Slice 2: B0 is handed over from SOURCE to PROCESSOR through a real shipment carried by CARRIER', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b0Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []

  // ---------------------------------------------------------------- 来源企业
  const source = await loginAs(browser, accounts.source, 'SOURCE', evidence)
  await source.page.goto(`/app/batches/${expected.b0Id}`)
  await expect(source.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b0TraceBatchNo)
  await expect(source.page.getByTestId('detail-org-name')).toHaveText(expected.sourceOrgName)
  await source.page.getByTestId('initiate-transfer').click()
  await expect(source.page).toHaveURL(new RegExp(`/app/batches/${expected.b0Id}/transfers/new$`))

  await selectByText(source.page, 'field-receiver', expected.processorOrgName)
  const createTransfer = waitForApi(source.page, 'POST', /^\/api\/v1\/transfers$/)
  await source.page.getByTestId('create-transfer').click()
  const transfer = await (await createTransfer).json()
  expect(transfer.data.status).toBe('DRAFT')
  const transferId: number = transfer.data.id
  await expect(source.page).toHaveURL(/\/app\/shipments\/new\?transferId=/)
  await expect(source.page.getByTestId('shipment-create-flash')).toContainText('下一步')
  await expect(source.page.getByTestId(`select-transfer-${transferId}`)).toBeChecked()

  await selectByText(source.page, 'field-carrier', expected.carrierOrgName)
  await source.page.getByTestId('field-vehicle').fill('浙L·冷S2001')
  await selectByText(source.page, 'field-origin-site', expected.sourceSiteName)
  await expect(source.page.getByTestId('field-destination-site')).toBeEnabled()
  await selectByText(source.page, 'field-destination-site', expected.processorSiteName)
  const createShipment = waitForApi(source.page, 'POST', /^\/api\/v1\/shipments$/)
  const bind = waitForApi(source.page, 'POST', /^\/api\/v1\/shipments\/\d+\/transfers$/)
  await source.page.getByTestId('create-shipment').click()
  const shipment = await (await createShipment).json()
  expect(shipment.data.status).toBe('PLANNED')
  expect((await bind).status()).toBe(200)
  const shipmentId: number = shipment.data.id
  await expect(source.page).toHaveURL(new RegExp(`/app/shipments/${shipmentId}$`))
  await expect(source.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'PLANNED')
  await expect(source.page.getByTestId('shipment-next-step')).toContainText('提交交接')

  const submit = waitForApi(source.page, 'POST', new RegExp(`^/api/v1/transfers/${transferId}/submit$`))
  await source.page.getByTestId(`submit-transfer-${transferId}`).click()
  expect((await submit).status()).toBe(200)
  await expect(source.page.getByTestId('manifest-transfer-status')).toHaveAttribute('data-status', 'PENDING')
  await expect(source.page.getByTestId('shipment-next-step')).toContainText('等待承运商确认装载发运')
  await expect(source.page.getByTestId('dispatch-shipment')).toHaveCount(0)

  // ---------------------------------------------------------------- 承运企业
  const carrier = await loginAs(browser, accounts.carrier, 'CARRIER', evidence)
  await expect(carrier.page.getByTestId('nav-inbound')).toHaveCount(0)
  await carrier.page.getByTestId('entry-carrier-shipments').click()
  await carrier.page.getByTestId(`open-shipment-${shipmentId}`).click()
  await expect(carrier.page.getByTestId('shipment-carrier')).toContainText(expected.carrierOrgName)
  await expect(carrier.page.getByTestId('shipment-next-step')).toContainText('确认装载发运')
  const dispatch = waitForApi(carrier.page, 'POST', new RegExp(`^/api/v1/shipments/${shipmentId}/dispatch$`))
  await carrier.page.getByTestId('dispatch-shipment').click()
  expect((await dispatch).status()).toBe(200)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'IN_TRANSIT')
  await expect(carrier.page.getByTestId('shipment-flash')).toContainText('TRANSPORT')

  const arrive = waitForApi(carrier.page, 'POST', new RegExp(`^/api/v1/shipments/${shipmentId}/arrive$`))
  await carrier.page.getByTestId('arrive-shipment').click()
  expect((await arrive).status()).toBe(200)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'DELIVERED')
  await expect(carrier.page.getByTestId('shipment-flash')).toContainText('ARRIVAL')
  await expect(carrier.page.getByTestId('carrier-actions')).toHaveCount(0)

  // 到达后来源企业仍是责任组织（交接尚未接受）
  await source.page.goto(`/app/batches/${expected.b0Id}`)
  await expect(source.page.getByTestId('detail-org-name')).toHaveText(expected.sourceOrgName)
  await expect(source.page.getByTestId('batch-transfer-status')).toHaveAttribute('data-status', 'PENDING')

  // ---------------------------------------------------------------- 加工企业
  const processor = await loginAs(browser, accounts.processor, 'PROCESSOR', evidence)
  await processor.page.getByTestId('entry-inbound').click()
  await expect(processor.page).toHaveURL(/\/app\/transfers\/inbound$/)
  const card = processor.page.locator(`[data-testid="inbound-transfer"][data-transfer-id="${transferId}"]`)
  await expect(card.getByTestId('inbound-trace-batch-no')).toHaveText(expected.b0TraceBatchNo)
  await expect(card.getByTestId('inbound-shipment-status')).toHaveAttribute('data-status', 'DELIVERED')
  const accept = waitForApi(processor.page, 'POST', new RegExp(`^/api/v1/transfers/${transferId}/accept$`))
  await processor.page.getByTestId(`accept-transfer-${transferId}`).click()
  const accepted = await (await accept).json()
  expect(accepted.data.status).toBe('ACCEPTED')

  await expect(processor.page).toHaveURL(new RegExp(`/app/batches/${expected.b0Id}$`))
  await expect(processor.page.getByTestId('batch-flash')).toContainText('当前责任组织')
  await expect(processor.page.getByTestId('detail-org-name')).toHaveText(expected.processorOrgName)
  await expect(processor.page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(processor.page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(processor.page.getByTestId('detail-quantity')).toHaveText('1,000 kg')
  await expect(processor.page.locator('[data-testid="trace-event"]')).toHaveCount(3)
  const eventTypes = await processor.page.locator('[data-testid="trace-event"]').evaluateAll((items) => items.map((i) => i.getAttribute('data-event-type')))
  expect(eventTypes).toEqual(['SOURCE', 'TRANSPORT', 'ARRIVAL'])
  await expect(processor.page.getByTestId('trace-events')).toContainText(shipment.data.shipmentNo)
  await expect(processor.page.getByTestId('batch-transfer-status')).toHaveAttribute('data-status', 'ACCEPTED')

  // 原来源企业：批次已转出，只能只读查看本组织参与的历史记录
  await source.page.reload()
  await expect(source.page.getByTestId('batch-detail-forbidden')).toBeVisible()
  await expect(source.page.getByTestId('history-transfer')).toHaveCount(1)
  await expect(source.page.getByTestId('initiate-transfer')).toHaveCount(0)

  // ---------------------------------------------------------------- 写请求证据
  const businessWrites = evidence.filter((e) => !e.path.startsWith('/api/v1/auth/'))
  expect(businessWrites.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')}`)).toEqual([
    'SOURCE POST /api/v1/transfers',
    'SOURCE POST /api/v1/shipments',
    'SOURCE POST /api/v1/shipments/:id/transfers',
    'SOURCE POST /api/v1/transfers/:id/submit',
    'CARRIER POST /api/v1/shipments/:id/dispatch',
    'CARRIER POST /api/v1/shipments/:id/arrive',
    'PROCESSOR POST /api/v1/transfers/:id/accept'
  ])
  for (const e of businessWrites) {
    expect(e.status, `${e.method} ${e.path}`).toBeLessThan(300)
    expect(e.csrf, `${e.method} ${e.path} CSRF`).toBe(true)
    expect(e.idempotencyKey, `${e.method} ${e.path} Idempotency-Key`).toBe(true)
    expect(e.requestId, `${e.method} ${e.path} X-Request-Id`).not.toBe('')
  }
  console.log('[slice2-smoke] 写请求证据（actor method path status requestId）:')
  for (const e of businessWrites) console.log(`  ${e.actor} ${e.method} ${e.path} ${e.status} ${e.requestId}`)
  console.log(`[slice2-smoke] SLICE2_RESULT ${JSON.stringify({ transferId, shipmentId, shipmentNo: shipment.data.shipmentNo })}`)

  await Promise.all([source.context.close(), carrier.context.close(), processor.context.close()])
})
