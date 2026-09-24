import { test, expect, type Browser, type Page, type Request, type Response } from '@playwright/test'
import { randomUUID } from 'node:crypto'

/**
 * Phase B / PB1-B 真实浏览器验收（由 scripts/smoke.mjs 编排，接在 Slice 6 零写入快照之后）：已售罄 CLOSED 批次的风险冻结 / 解除冻结。
 *
 * 前置：B2 已由零售企业售罄关闭（CLOSED / NORMAL），公开追溯码 ACTIVE。
 * 路径：零售质量管理员在 B2 详情风险冻结（CLOSED / FROZEN，流转状态保持已关闭、数量与责任组织不变）
 *       → 全新未登录的消费者上下文扫码：已关闭 + 模拟冻结，综合结论为教学实训专用的“模拟风险冻结”，不出现召回提示，谱系不变
 *       → 零售操作员真实 API 探测终端销售仍 422（冻结从不重新打开已关闭批次）
 *       → 质量管理员解除冻结（CLOSED / NORMAL）→ 消费者页恢复“流转已关闭 / 正常”。
 * 禁止 page.route 或任何接口替身。证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 */

interface Pb1bExpected {
  b2Id: number
  b2TraceBatchNo: string
  retailerOrgName: string
  storeSiteId: number
}

const expected: Pb1bExpected = JSON.parse(process.env.PB1B_EXPECTED || '{}')
const accounts = {
  operator: { username: process.env.PB1B_RETAILER_USERNAME || '', password: process.env.PB1B_RETAILER_PASSWORD || '' },
  qm: { username: process.env.PB1B_RETAILER_QM_USERNAME || '', password: process.env.PB1B_RETAILER_QM_PASSWORD || '' }
}

interface Evidence {
  actor: string
  method: string
  path: string
  status: number
  requestId: string
  csrf: boolean
  idempotencyKey: boolean
  cookie: boolean
}

function recordApi(page: Page, actor: string, evidence: Evidence[]) {
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    const request: Request = response.request()
    const headers = request.headers()
    evidence.push({
      actor,
      method: request.method(),
      path: url.pathname,
      status: response.status(),
      requestId: response.headers()['x-request-id'] || '',
      csrf: Boolean(headers['x-csrf-token']),
      idempotencyKey: (headers['idempotency-key'] || '').length >= 16,
      cookie: Boolean(headers['cookie'])
    })
  })
}

function waitForApi(page: Page, method: string, pathPattern: RegExp) {
  return page.waitForResponse((response) => response.request().method() === method && pathPattern.test(new URL(response.url()).pathname))
}

async function loginAs(browser: Browser, account: { username: string; password: string }, actor: string, evidence: Evidence[]) {
  expect(account.username).not.toBe('')
  expect(account.password).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  recordApi(page, actor, evidence)
  await page.goto('/login')
  await page.locator('#login-username').fill(account.username)
  await page.locator('#login-password').fill(account.password)
  const login = waitForApi(page, 'POST', /^\/api\/v1\/auth\/login$/)
  await page.getByRole('button', { name: '登录' }).click()
  expect((await login).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

async function probe(page: Page, method: string, path: string, body: unknown) {
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()).data
  const response = await page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID(), 'Content-Type': 'application/json' },
    data: body === undefined ? undefined : JSON.stringify(body)
  })
  const json = await response.json().catch(() => ({}))
  return { status: response.status(), code: json.code as string | undefined }
}

async function riskTransitionInBrowser(page: Page, action: 'freeze' | 'release', reason: string) {
  await page.getByTestId(action === 'freeze' ? 'risk-freeze-open' : 'risk-release-open').click()
  await page.getByTestId('field-risk-reason').fill(reason)
  await page.getByTestId('risk-next').click()
  await expect(page.getByTestId('risk-confirm-panel')).toBeVisible()
  const post = waitForApi(page, 'POST', new RegExp(`^/api/v1/batches/\\d+/risk/${action}$`))
  await page.getByTestId('risk-confirm').click()
  const response = await post
  expect(response.status()).toBe(201)
  expect(JSON.parse(response.request().postData() || '{}')).toEqual({ reason })
  await expect(page.getByTestId('risk-current')).toHaveAttribute('data-status', action === 'freeze' ? 'FROZEN' : 'NORMAL')
  return (await response.json()).data
}

async function scan(page: Page, code: string) {
  const response = page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/public/v1/public/traces/${code}`)
  await page.goto(`/trace/${code}`)
  expect((await response).status()).toBe(200)
  return (await (await response).json()).data
}

test('PB1-B: retailer QUALITY_MANAGER freezes and releases a sold-out CLOSED batch; the anonymous consumer sees the simulation-only wording', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b2Id).toBeGreaterThan(0)
  const evidence: Evidence[] = []

  // ---------------------------------------------------------------- 零售质量管理员：CLOSED 批次风险冻结
  const qm = await loginAs(browser, accounts.qm, 'RETAILER_QM', evidence)
  await qm.page.goto(`/app/batches/${expected.b2Id}`)
  await expect(qm.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b2TraceBatchNo)
  await expect(qm.page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(qm.page.getByTestId('public-code-status')).toHaveAttribute('data-status', 'ACTIVE')
  const code = (await qm.page.getByTestId('public-code-value').textContent())?.trim() ?? ''
  expect(code).toMatch(/^[A-Z2-7]{26}$/)
  const frozen = await riskTransitionInBrowser(qm.page, 'freeze', 'PB1 演示：售罄后历史风险模拟调查')
  expect(frozen).toMatchObject({ batchId: expected.b2Id, fromStatus: 'NORMAL', toStatus: 'FROZEN', flowStatus: 'CLOSED', sourceType: 'MANUAL' })
  await expect(qm.page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(qm.page.getByTestId('detail-risk-status')).toHaveText('冻结')
  await expect(qm.page.getByTestId('detail-remaining-quantity')).toHaveText('0 kg')
  await expect(qm.page.getByTestId('detail-org-name')).toHaveText(expected.retailerOrgName)
  await expect(qm.page.getByTestId('public-code-status')).toHaveAttribute('data-status', 'ACTIVE')

  // ---------------------------------------------------------------- 匿名消费者：已关闭 + 模拟冻结（教学实训专用文案）
  const consumerContext = await browser.newContext()
  const consumer = await consumerContext.newPage()
  recordApi(consumer, 'CONSUMER', evidence)
  const frozenTrace = await scan(consumer, code)
  expect(frozenTrace).toMatchObject({ flowStatus: 'CLOSED', riskStatus: 'FROZEN' })
  expect(frozenTrace.recallNotice ?? null).toBeNull()
  await expect(consumer.getByTestId('public-status-badge')).toContainText('模拟风险冻结')
  await expect(consumer.getByTestId('public-status-note')).toContainText('本教学实训系统中')
  await expect(consumer.getByTestId('public-status-note')).toContainText('已结束正常流转')
  await expect(consumer.getByTestId('public-status-note')).toContainText('不代表真实的产品安全判定、监管措施或产品扣留')
  await expect(consumer.getByTestId('public-flow-status')).toHaveText('已关闭')
  await expect(consumer.getByTestId('public-risk-status')).toHaveText('模拟冻结')
  await expect(consumer.locator('.recall-alert-card')).toHaveCount(0)
  await expect(consumer.getByTestId('lineage-node')).toHaveCount(3)
  await expect(consumer.locator('.temp-summary-card')).toContainText('暂无实时时序采集')
  await expect(consumer.locator('body')).not.toContainText('业务冻结状态')
  await expect(consumer.locator('body')).not.toContainText('PB1 演示')

  // ---------------------------------------------------------------- 零售操作员：冻结从不重新打开已关闭批次
  const operator = await loginAs(browser, accounts.operator, 'RETAILER', evidence)
  await operator.page.goto(`/app/batches/${expected.b2Id}`)
  await expect(operator.page.getByTestId('risk-frozen-effects')).toBeVisible()
  await expect(operator.page.getByTestId('risk-freeze-open')).toHaveCount(0)
  await expect(operator.page.getByTestId('risk-release-open')).toHaveCount(0)
  await expect(operator.page.getByTestId('sale-panel')).toHaveCount(0)
  const blockedSale = await probe(operator.page, 'POST', `/api/v1/batches/${expected.b2Id}/sales`, {
    siteId: expected.storeSiteId, quantity: 1, occurredAt: new Date(Date.now() - 60_000).toISOString()
  })
  expect(`${blockedSale.status} ${blockedSale.code}`).toBe('422 BATCH_FLOW_BLOCKED')

  // ---------------------------------------------------------------- 解除冻结：CLOSED / NORMAL，消费者页恢复
  const released = await riskTransitionInBrowser(qm.page, 'release', 'PB1 演示：历史风险调查结束')
  expect(released).toMatchObject({ fromStatus: 'FROZEN', toStatus: 'NORMAL', flowStatus: 'CLOSED' })
  await expect(qm.page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(qm.page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(qm.page.getByTestId('risk-transition-row')).toHaveCount(4)
  const releasedTrace = await scan(consumer, code)
  expect(releasedTrace).toMatchObject({ flowStatus: 'CLOSED', riskStatus: 'NORMAL' })
  await expect(consumer.getByTestId('public-status-badge')).toContainText('流转已关闭')
  await expect(consumer.getByTestId('public-risk-status')).toHaveText('正常')

  // ---------------------------------------------------------------- 证据：消费者只有匿名 GET；企业写入只有质量管理员的冻结与解除
  const consumerCalls = evidence.filter((e) => e.actor === 'CONSUMER')
  expect(consumerCalls.length).toBeGreaterThanOrEqual(2)
  for (const e of consumerCalls) {
    expect(`${e.method} ${e.path.replace(/[A-Z2-7]{26}$/, ':code')}`).toBe('GET /api/public/v1/public/traces/:code')
    expect(e.cookie, 'consumer request must not carry a cookie').toBe(false)
  }
  const writes = evidence.filter((e) => e.method !== 'GET' && e.path.startsWith('/api/v1/') && !e.path.startsWith('/api/v1/auth/'))
  expect(writes.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')}`)).toEqual([
    'RETAILER_QM POST /api/v1/batches/:id/risk/freeze',
    'RETAILER_QM POST /api/v1/batches/:id/risk/release'
  ])
  for (const e of writes) {
    expect(e.status).toBe(201)
    expect(e.csrf).toBe(true)
    expect(e.idempotencyKey).toBe(true)
    expect(e.requestId).not.toBe('')
  }
  console.log('[pb1b-smoke] 证据（actor method path status requestId）:')
  for (const e of [...writes, ...consumerCalls]) console.log(`  ${e.actor} ${e.method} ${e.path.replace(/[A-Z2-7]{26}$/, ':code')} ${e.status} ${e.requestId}`)
  console.log(`[pb1b-smoke] PB1B_RESULT ${JSON.stringify({ freezeId: frozen.id, releaseId: released.id })}`)

  await Promise.all([qm.context.close(), operator.context.close(), consumerContext.close()])
})
