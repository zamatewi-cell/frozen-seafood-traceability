import { test, expect, type Browser, type Page, type Response } from '@playwright/test'
import { randomUUID } from 'node:crypto'

/**
 * Phase A / Slice 5 真实浏览器验收（由 scripts/smoke.mjs 编排）：加工 → 零售交接与终端 Sale。
 *
 * 三个相互隔离的浏览器上下文分别代表加工企业、承运企业与零售企业（真实 Session + CSRF），禁止 page.route 或任何接口替身。
 * 路径：加工企业为 B2 / B3 分别发起交接 T2 / T3 → 同一运输任务 S1 装载两张交接 → 提交 T2 / T3
 *       → 承运商发运、到达 S1 → 零售企业接受 T2 / T3
 *       → 零售企业在批次详情终端销售：B2 售 200（剩余 400，仍可流转，交接入口消失）→ B2 售 400（售罄关闭）→ B3 售 360（售罄关闭）。
 * B2 首次销售后、剩余 400 时，在零售企业同一会话内经真实 API 探测：交接 409 BATCH_SALE_STARTED、批次操作 403、
 * 非门店 / 停用门店 / 他组织门店、超卖与人工 SALE 事件均被拒绝；售罄后重放原销售得到同一 Sale，改载荷 409。
 * 证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 */

interface Slice5Expected {
  b2Id: number
  b3Id: number
  b2TraceBatchNo: string
  b3TraceBatchNo: string
  processorOrgId: number
  processorOrgName: string
  carrierOrgName: string
  retailerOrgName: string
  processorSiteName: string
  retailerStoreName: string
  retailerHubSiteId: number
  retailerInactiveStoreId: number
  processorStoreSiteId: number
}

const expected: Slice5Expected = JSON.parse(process.env.SLICE5_EXPECTED || '{}')
const accounts = {
  processor: { username: process.env.SLICE5_PROCESSOR_USERNAME || '', password: process.env.SLICE5_PROCESSOR_PASSWORD || '' },
  carrier: { username: process.env.SLICE5_CARRIER_USERNAME || '', password: process.env.SLICE5_CARRIER_PASSWORD || '' },
  retailer: { username: process.env.SLICE5_RETAILER_USERNAME || '', password: process.env.SLICE5_RETAILER_PASSWORD || '' }
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

/** 零售企业同一浏览器会话内的真实 API 探测（共享 Session Cookie，现取 CSRF）；不经过页面，也不计入页面写请求证据。 */
async function probe(page: Page, method: string, path: string, body: unknown, idempotencyKey: string = randomUUID()) {
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()).data
  const response = await page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': idempotencyKey, 'Content-Type': 'application/json' },
    data: body === undefined ? undefined : JSON.stringify(body)
  })
  const json = await response.json().catch(() => ({}))
  return { status: response.status(), code: json.code as string | undefined, data: json.data }
}

async function sellInBrowser(page: Page, quantity: string | 'ALL') {
  await page.getByTestId('sale-start').click()
  await selectByText(page, 'field-sale-site', expected.retailerStoreName)
  if (quantity === 'ALL') await page.getByTestId('sale-all').click()
  else await page.getByTestId('field-sale-quantity').fill(quantity)
  const post = waitForApi(page, 'POST', /^\/api\/v1\/batches\/\d+\/sales$/)
  await page.getByTestId('sale-submit').click()
  const response = await post
  expect(response.status()).toBe(201)
  return { sale: (await response.json()).data, request: response.request() }
}

test('Slice 5: B2 / B3 are handed over to RETAILER on one shipment and sold to zero at the retailer store', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b2Id).toBeGreaterThan(0)
  expect(expected.b3Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []

  // ---------------------------------------------------------------- 加工企业：T2 / T3 + 共同运输任务 S1
  const processor = await loginAs(browser, accounts.processor, 'PROCESSOR', evidence)
  const transferIds: number[] = []
  for (const batchId of [expected.b2Id, expected.b3Id]) {
    await processor.page.goto(`/app/batches/${batchId}`)
    await processor.page.getByTestId('initiate-transfer').click()
    await selectByText(processor.page, 'field-receiver', expected.retailerOrgName)
    const createTransfer = waitForApi(processor.page, 'POST', /^\/api\/v1\/transfers$/)
    await processor.page.getByTestId('create-transfer').click()
    const transfer = await (await createTransfer).json()
    expect(transfer.data.status).toBe('DRAFT')
    transferIds.push(transfer.data.id)
    await expect(processor.page).toHaveURL(/\/app\/shipments\/new\?transferId=/)
  }
  const [t2, t3] = transferIds
  await expect(processor.page.getByTestId(`select-transfer-${t3}`)).toBeChecked()
  await processor.page.getByTestId(`select-transfer-${t2}`).check()
  await selectByText(processor.page, 'field-carrier', expected.carrierOrgName)
  await processor.page.getByTestId('field-vehicle').fill('浙L·冷S5001')
  await selectByText(processor.page, 'field-origin-site', expected.processorSiteName)
  await expect(processor.page.getByTestId('field-destination-site')).toBeEnabled()
  await selectByText(processor.page, 'field-destination-site', expected.retailerStoreName)
  const createShipment = waitForApi(processor.page, 'POST', /^\/api\/v1\/shipments$/)
  await processor.page.getByTestId('create-shipment').click()
  const shipment = await (await createShipment).json()
  const shipmentId: number = shipment.data.id
  await expect(processor.page).toHaveURL(new RegExp(`/app/shipments/${shipmentId}$`))
  for (const transferId of transferIds) {
    const submit = waitForApi(processor.page, 'POST', new RegExp(`^/api/v1/transfers/${transferId}/submit$`))
    await processor.page.getByTestId(`submit-transfer-${transferId}`).click()
    expect((await submit).status()).toBe(200)
  }
  await expect(processor.page.getByTestId('manifest-transfer-status')).toHaveCount(2)
  await expect(processor.page.locator('[data-testid="manifest-transfer-status"][data-status="PENDING"]')).toHaveCount(2)

  // ---------------------------------------------------------------- 承运企业：发运、到达
  const carrier = await loginAs(browser, accounts.carrier, 'CARRIER', evidence)
  await carrier.page.goto(`/app/shipments/${shipmentId}`)
  const dispatch = waitForApi(carrier.page, 'POST', new RegExp(`^/api/v1/shipments/${shipmentId}/dispatch$`))
  await carrier.page.getByTestId('dispatch-shipment').click()
  expect((await dispatch).status()).toBe(200)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'IN_TRANSIT')
  const arrive = waitForApi(carrier.page, 'POST', new RegExp(`^/api/v1/shipments/${shipmentId}/arrive$`))
  await carrier.page.getByTestId('arrive-shipment').click()
  expect((await arrive).status()).toBe(200)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'DELIVERED')

  // ---------------------------------------------------------------- 零售企业：接受 T2 / T3
  const retailer = await loginAs(browser, accounts.retailer, 'RETAILER', evidence)
  for (const [transferId, batchId] of [[t2, expected.b2Id], [t3, expected.b3Id]]) {
    await retailer.page.goto('/app/transfers/inbound')
    const accept = waitForApi(retailer.page, 'POST', new RegExp(`^/api/v1/transfers/${transferId}/accept$`))
    await retailer.page.getByTestId(`accept-transfer-${transferId}`).click()
    expect((await (await accept).json()).data.status).toBe('ACCEPTED')
    await expect(retailer.page).toHaveURL(new RegExp(`/app/batches/${batchId}$`))
    await expect(retailer.page.getByTestId('detail-org-name')).toHaveText(expected.retailerOrgName)
  }

  // ---------------------------------------------------------------- B2：部分销售 200
  await retailer.page.goto(`/app/batches/${expected.b2Id}`)
  await expect(retailer.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b2TraceBatchNo)
  await expect(retailer.page.getByTestId('sale-remaining')).toHaveText('600 kg')
  await expect(retailer.page.getByTestId('initiate-transfer')).toBeVisible()
  const first = await sellInBrowser(retailer.page, '200')
  await expect(retailer.page.getByTestId('batch-flash')).toContainText('已登记终端销售 200 kg；剩余 400 kg')
  await expect(retailer.page.getByTestId('detail-remaining-quantity')).toHaveText('400 kg')
  await expect(retailer.page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(retailer.page.getByTestId('detail-org-name')).toHaveText(expected.retailerOrgName)
  await expect(retailer.page.getByTestId('sale-started')).toBeVisible()
  await expect(retailer.page.getByTestId('initiate-transfer')).toHaveCount(0)
  await expect(retailer.page.getByTestId('sale-row')).toHaveCount(1)
  await expect(retailer.page.locator('[data-testid="trace-event"][data-event-type="SALE"]')).toHaveCount(1)

  // ---------------------------------------------------------------- 首次销售后、剩余 400 时的真实 API 探测
  const probes: Array<{ name: string; status: number; code?: string }> = []
  const record = (name: string, r: { status: number; code?: string }) => probes.push({ name, status: r.status, code: r.code })
  record('transfer after first sale', await probe(retailer.page, 'POST', '/api/v1/transfers', { batchId: expected.b2Id, receiverOrgId: expected.processorOrgId }))
  record('batch operation by retailer', await probe(retailer.page, 'POST', '/api/v1/batch-operations', {
    operationType: 'SPLIT', occurredAt: new Date(Date.now() - 60_000).toISOString(), note: 'probe',
    items: [
      { role: 'INPUT', batchId: expected.b2Id, quantity: 400, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 200, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 200, unitCode: 'kg' }
    ]
  }))
  const at = new Date(Date.now() - 60_000).toISOString()
  record('sale at logistics hub', await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, { siteId: expected.retailerHubSiteId, quantity: 1, occurredAt: at }))
  record('sale at inactive store', await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, { siteId: expected.retailerInactiveStoreId, quantity: 1, occurredAt: at }))
  record('sale at processor store', await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, { siteId: expected.processorStoreSiteId, quantity: 1, occurredAt: at }))
  record('oversell 400.001', await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, { siteId: first.sale.siteId, quantity: 400.001, occurredAt: at }))
  record('manual SALE event', await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/events`, { eventType: 'SALE', occurredAt: at, dataSource: 'MANUAL', summary: 'probe' }))
  expect(probes.map((p) => `${p.name} ${p.status} ${p.code ?? ''}`)).toEqual([
    'transfer after first sale 409 BATCH_SALE_STARTED',
    'batch operation by retailer 403 ORG_TYPE_NOT_ALLOWED',
    'sale at logistics hub 422 SALE_SITE_TYPE_INVALID',
    'sale at inactive store 422 SITE_NOT_ACTIVE',
    'sale at processor store 403 ORG_SCOPE_DENIED',
    'oversell 400.001 422 SALE_QUANTITY_EXCEEDS_REMAINING',
    'manual SALE event 422 EVENT_TYPE_NOT_MANUAL'
  ])

  // ---------------------------------------------------------------- B2：售出剩余 400 → 售罄关闭
  await retailer.page.reload()
  const second = await sellInBrowser(retailer.page, '400')
  await expect(retailer.page.getByTestId('batch-flash')).toContainText('批次已售罄')
  await expect(retailer.page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(retailer.page.getByTestId('detail-remaining-quantity')).toHaveText('0 kg')
  await expect(retailer.page.getByTestId('sale-sold-out')).toBeVisible()
  await expect(retailer.page.getByTestId('sale-panel')).toHaveCount(0)
  await expect(retailer.page.getByTestId('sale-row')).toHaveCount(2)
  await expect(retailer.page.locator('[data-testid="trace-event"][data-event-type="SALE"]')).toHaveCount(2)

  // ---------------------------------------------------------------- B3：一次售罄 360
  await retailer.page.goto(`/app/batches/${expected.b3Id}`)
  await expect(retailer.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b3TraceBatchNo)
  await expect(retailer.page.getByTestId('sale-remaining')).toHaveText('360 kg')
  const third = await sellInBrowser(retailer.page, 'ALL')
  expect(Number(third.sale.quantity)).toBe(360)
  await expect(retailer.page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(retailer.page.getByTestId('detail-remaining-quantity')).toHaveText('0 kg')
  await expect(retailer.page.getByTestId('sale-row')).toHaveCount(1)

  // ---------------------------------------------------------------- 售罄后幂等重放：同键同载荷得到原 Sale，改载荷 409
  const replayKey = second.request.headers()['idempotency-key']
  const replayBody = JSON.parse(second.request.postData() || '{}')
  const replay = await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, replayBody, replayKey)
  expect(replay.status).toBe(201)
  expect(replay.data.id).toBe(second.sale.id)
  const changed = await probe(retailer.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, { ...replayBody, quantity: 399 }, replayKey)
  expect(`${changed.status} ${changed.code}`).toBe('409 IDEMPOTENCY_CONFLICT')

  // ---------------------------------------------------------------- 页面写请求证据
  const businessWrites = evidence.filter((e) => !e.path.startsWith('/api/v1/auth/'))
  expect(businessWrites.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')}`)).toEqual([
    'PROCESSOR POST /api/v1/transfers',
    'PROCESSOR POST /api/v1/transfers',
    'PROCESSOR POST /api/v1/shipments',
    'PROCESSOR POST /api/v1/shipments/:id/transfers',
    'PROCESSOR POST /api/v1/shipments/:id/transfers',
    'PROCESSOR POST /api/v1/transfers/:id/submit',
    'PROCESSOR POST /api/v1/transfers/:id/submit',
    'CARRIER POST /api/v1/shipments/:id/dispatch',
    'CARRIER POST /api/v1/shipments/:id/arrive',
    'RETAILER POST /api/v1/transfers/:id/accept',
    'RETAILER POST /api/v1/transfers/:id/accept',
    'RETAILER POST /api/v1/batches/:id/sales',
    'RETAILER POST /api/v1/batches/:id/sales',
    'RETAILER POST /api/v1/batches/:id/sales'
  ])
  for (const e of businessWrites) {
    expect(e.status, `${e.method} ${e.path}`).toBeLessThan(300)
    expect(e.csrf, `${e.method} ${e.path} CSRF`).toBe(true)
    expect(e.idempotencyKey, `${e.method} ${e.path} Idempotency-Key`).toBe(true)
    expect(e.requestId, `${e.method} ${e.path} X-Request-Id`).not.toBe('')
  }
  console.log('[slice5-smoke] 页面写请求证据（actor method path status requestId）:')
  for (const e of businessWrites) console.log(`  ${e.actor} ${e.method} ${e.path} ${e.status} ${e.requestId}`)
  console.log('[slice5-smoke] 首次销售后 API 探测（name status code）:')
  for (const p of probes) console.log(`  ${p.name} ${p.status} ${p.code ?? ''}`)
  console.log(`[slice5-smoke] SLICE5_RESULT ${JSON.stringify({ t2, t3, shipmentId, sales: [first.sale.id, second.sale.id, third.sale.id] })}`)

  await Promise.all([processor.context.close(), carrier.context.close(), retailer.context.close()])
})
