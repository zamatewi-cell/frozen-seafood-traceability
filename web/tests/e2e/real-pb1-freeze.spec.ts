import { test, expect, type Browser, type Page, type Response } from '@playwright/test'
import { randomUUID } from 'node:crypto'

/**
 * Phase B / PB1-A 真实浏览器验收（由 scripts/smoke.mjs 编排，接在 Slice 4 之后、Slice 5 之前）：加工企业人工风险冻结 / 解除冻结。
 *
 * 前置：B2（600 kg）由加工企业负责，ACTIVE / NORMAL，已完成自有冷库入库 / 出库。
 * 路径：加工企业质量管理员在 B2 详情填写原因并二次确认风险冻结 → ACTIVE / FROZEN（数量、责任组织、流转状态不变）
 *       → 加工企业操作员看到冻结影响说明，交接 / 加工 / 拆分 / 仓储 / 首次激活公开码入口全部消失，
 *         同一会话真实 API 探测：交接创建 409 BATCH_NOT_ACTIVE、批次操作 422、冷库仓储 422、首次激活公开码 422、操作员冻结 403；
 *         查询（批次详情、追溯事件、批次操作、风险转换历史）不受影响
 *       → 质量管理员解除冻结 → ACTIVE / NORMAL，操作员入口恢复；随后 Slice 5 / 6 照常完成 Phase A 主链。
 * 冻结由人工质量管理员发起，不使用温度或 Alert。三个上下文相互隔离（真实 Session + CSRF），禁止 page.route 或任何接口替身。
 * 证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 */

interface Pb1Expected {
  b2Id: number
  b3Id: number
  b2TraceBatchNo: string
  processorOrgName: string
  sourceOrgId: number
  coldStoreSiteId: number
}

const expected: Pb1Expected = JSON.parse(process.env.PB1_EXPECTED || '{}')
const accounts = {
  operator: { username: process.env.PB1_PROCESSOR_USERNAME || '', password: process.env.PB1_PROCESSOR_PASSWORD || '' },
  qm: { username: process.env.PB1_PROCESSOR_QM_USERNAME || '', password: process.env.PB1_PROCESSOR_QM_PASSWORD || '' }
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

/** 同一浏览器会话内的真实 API 探测（共享 Session Cookie，现取 CSRF）；不经过页面，也不计入页面写请求证据。 */
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

/** 质量管理员在批次详情“风险状态”面板填写原因、二次确认后提交；请求体只含原因。 */
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

test('PB1-A: processor QUALITY_MANAGER freezes B2 (ACTIVE / FROZEN), Phase A guards block the operator, reads keep working, then release', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b2Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []

  // ---------------------------------------------------------------- 加工质量管理员：风险冻结 B2
  const qm = await loginAs(browser, accounts.qm, 'PROCESSOR_QM', evidence)
  await qm.page.goto(`/app/batches/${expected.b2Id}`)
  await expect(qm.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b2TraceBatchNo)
  await expect(qm.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'NORMAL')
  await expect(qm.page.getByTestId('risk-history-empty')).toBeVisible()
  // 质量管理员不是操作员：只有风险冻结入口，没有交接 / 加工 / 仓储 / 公开码入口
  for (const id of ['initiate-transfer', 'start-process', 'warehouse-in', 'public-code-activate']) {
    await expect(qm.page.getByTestId(id), id).toHaveCount(0)
  }
  const frozen = await riskTransitionInBrowser(qm.page, 'freeze', 'PB1 演示：加工企业模拟质量抽检待定')
  expect(frozen).toMatchObject({ batchId: expected.b2Id, fromStatus: 'NORMAL', toStatus: 'FROZEN', flowStatus: 'ACTIVE', sourceType: 'MANUAL' })
  await expect(qm.page.getByTestId('batch-flash')).toContainText('已风险冻结（模拟质量处置）')
  await expect(qm.page.getByTestId('detail-risk-status')).toHaveText('冻结')
  await expect(qm.page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(qm.page.getByTestId('detail-quantity')).toContainText('600 kg')
  await expect(qm.page.getByTestId('detail-org-name')).toHaveText(expected.processorOrgName)
  await expect(qm.page.getByTestId('risk-transition-row')).toHaveCount(1)

  // ---------------------------------------------------------------- 加工操作员：冻结期间入口消失，Phase A 守卫阻断写入，查询可用
  const operator = await loginAs(browser, accounts.operator, 'PROCESSOR', evidence)
  await operator.page.goto(`/app/batches/${expected.b2Id}`)
  await expect(operator.page.getByTestId('risk-frozen-effects')).toBeVisible()
  await expect(operator.page.getByTestId('risk-transition-row')).toHaveCount(1)
  for (const id of ['initiate-transfer', 'start-process', 'start-split', 'warehouse-in', 'public-code-activate', 'risk-freeze-open', 'risk-release-open']) {
    await expect(operator.page.getByTestId(id), id).toHaveCount(0)
  }
  const at = new Date(Date.now() - 60_000).toISOString()
  const probes: Array<{ name: string; status: number; code?: string }> = []
  const record = (name: string, r: { status: number; code?: string }) => probes.push({ name, status: r.status, code: r.code })
  record('transfer create while frozen', await probe(operator.page, 'POST', '/api/v1/transfers', { batchId: expected.b2Id, receiverOrgId: expected.sourceOrgId }))
  record('split while frozen', await probe(operator.page, 'POST', '/api/v1/batch-operations', {
    operationType: 'SPLIT', occurredAt: at, note: 'PB1 probe',
    items: [
      { role: 'INPUT', batchId: expected.b2Id, quantity: 600, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 300, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 300, unitCode: 'kg' }
    ]
  }))
  record('warehouse in while frozen', await probe(operator.page, 'POST', `/api/v1/batches/${expected.b2Id}/events`, {
    eventType: 'WAREHOUSE_IN', siteId: expected.coldStoreSiteId, occurredAt: at, dataSource: 'MANUAL', summary: 'PB1 probe'
  }))
  record('first public code activation while frozen', await probe(operator.page, 'POST', `/api/v1/batches/${expected.b2Id}/public-trace-code/activate`, undefined))
  record('operator freeze attempt', await probe(operator.page, 'POST', `/api/v1/batches/${expected.b2Id}/risk/freeze`, { reason: 'PB1 probe' }))
  expect(probes.map((p) => `${p.name} ${p.status} ${p.code ?? ''}`)).toEqual([
    'transfer create while frozen 409 BATCH_NOT_ACTIVE',
    'split while frozen 422 BATCH_FLOW_BLOCKED',
    'warehouse in while frozen 422 BATCH_FLOW_BLOCKED',
    'first public code activation while frozen 422 BATCH_FLOW_BLOCKED',
    'operator freeze attempt 403 ACCESS_DENIED'
  ])
  const reads = [
    `/api/v1/batches/${expected.b2Id}`,
    `/api/v1/batches/${expected.b2Id}/events`,
    `/api/v1/batches/${expected.b2Id}/risk-transitions`,
    `/api/v1/batch-operations?batchId=${expected.b2Id}`
  ]
  for (const path of reads) {
    const response = await operator.page.request.get(path)
    expect(response.status(), path).toBe(200)
  }
  expect((await (await operator.page.request.get(`/api/v1/batches/${expected.b2Id}`)).json()).data).toMatchObject({ flowStatus: 'ACTIVE', riskStatus: 'FROZEN', orgId: frozen.orgId })

  // ---------------------------------------------------------------- 加工质量管理员：解除冻结，操作员入口恢复
  const released = await riskTransitionInBrowser(qm.page, 'release', 'PB1 演示：复检合格，解除冻结')
  expect(released).toMatchObject({ fromStatus: 'FROZEN', toStatus: 'NORMAL', flowStatus: 'ACTIVE' })
  await expect(qm.page.getByTestId('batch-flash')).toContainText('已解除冻结')
  await expect(qm.page.getByTestId('risk-transition-row')).toHaveCount(2)
  await operator.page.reload()
  await expect(operator.page.getByTestId('risk-frozen-effects')).toHaveCount(0)
  await expect(operator.page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(operator.page.getByTestId('initiate-transfer')).toBeVisible()
  await expect(operator.page.getByTestId('warehouse-in')).toBeVisible()
  await expect(operator.page.getByTestId('start-process')).toBeVisible()

  // ---------------------------------------------------------------- 页面写请求证据：只有质量管理员的冻结与解除
  const businessWrites = evidence.filter((e) => !e.path.startsWith('/api/v1/auth/'))
  expect(businessWrites.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')}`)).toEqual([
    'PROCESSOR_QM POST /api/v1/batches/:id/risk/freeze',
    'PROCESSOR_QM POST /api/v1/batches/:id/risk/release'
  ])
  for (const e of businessWrites) {
    expect(e.status, `${e.method} ${e.path}`).toBe(201)
    expect(e.csrf, `${e.method} ${e.path} CSRF`).toBe(true)
    expect(e.idempotencyKey, `${e.method} ${e.path} Idempotency-Key`).toBe(true)
    expect(e.requestId, `${e.method} ${e.path} X-Request-Id`).not.toBe('')
  }
  console.log('[pb1a-smoke] 页面写请求证据（actor method path status requestId）:')
  for (const e of businessWrites) console.log(`  ${e.actor} ${e.method} ${e.path} ${e.status} ${e.requestId}`)
  console.log('[pb1a-smoke] 冻结期间 API 探测（name status code）:')
  for (const p of probes) console.log(`  ${p.name} ${p.status} ${p.code ?? ''}`)
  console.log(`[pb1a-smoke] PB1A_RESULT ${JSON.stringify({ freezeId: frozen.id, releaseId: released.id })}`)

  await Promise.all([qm.context.close(), operator.context.close()])
})
