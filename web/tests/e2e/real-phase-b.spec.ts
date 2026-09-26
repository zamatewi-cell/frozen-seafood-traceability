import { test, expect, type Browser, type Page, type Response } from '@playwright/test'
import { randomUUID } from 'node:crypto'

/**
 * Phase B 异常闭环真实浏览器验收（由 scripts/smoke.mjs 编排；PB3 → PB4 → PB5 → PB6）。
 *
 * 夹具（smoke.mjs 经真实 API）：来源企业把 X1 300kg / X2 200kg 两个来源批次（已激活公开追溯码）装入同一运输任务 SX 发往加工企业，
 * 承运商以 100 分钟前的装载时间发运（界面发运按钮固定使用当前时间；两个持续超温片段各需要已测区间达到规则允许的 1800 秒）。
 * 以下全部在相互隔离的浏览器上下文中完成（真实 Session + CSRF），禁止 page.route 或任何接口替身：
 *   承运商在途登记 7 条温度：NORMAL / HIGH / HIGH / HIGH（片段 1 达到 1800 秒 → 告警 A1，经风险核心自动冻结 X1 / X2）
 *   / NORMAL（片段结束）/ HIGH / HIGH（片段 2 达到 1800 秒 → 告警 A2，X1 / X2 已冻结只快照）
 *   → 来源企业质量管理员确认 A1、A2 → 承运商确认到达 → 加工企业不能直接接受冻结批次，隔离收货 X1 / X2
 *   → 加工企业质量管理员（隔离接收方）提交检验证据：A1 中 X1 合格、X2 不合格；A2 中 X1 合格
 *   → 独立评审修复：来源质量管理员依据 A1 放行 X1 只记录 A1 的结论，X1 仍被 A2 冻结；依据 A2 放行后才恢复正常
 *   → 对不合格的 X2 发起模拟召回（范围快照含隔离中的交接；召回同时解除 X2 在两个告警中的风险事项）
 *   → 加工企业按隔离实收数量接受 X1；拒收已召回的 X2 → 来源质量管理员对 A1、A2 形成处置结论、以“退回”关闭模拟召回
 *   → 匿名消费者扫码 X2 看到模拟召回提示与处置进展（不含任何内部事实），扫码 X1 保持正常。
 * 证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 */

interface PhaseBExpected {
  shipmentId: number
  loadedAt: string
  x1Id: number
  x2Id: number
  x1TraceBatchNo: string
  x2TraceBatchNo: string
  x1PublicId: string
  x2PublicId: string
  t1Id: number
  t2Id: number
  sourceOrgName: string
  processorOrgId: number
  processorOrgName: string
  quarantineSiteName: string
  forbidden: string[]
}

const expected: PhaseBExpected = JSON.parse(process.env.PHASEB_EXPECTED || '{}')
const account = (prefix: string) => ({ username: process.env[`${prefix}_USERNAME`] || '', password: process.env[`${prefix}_PASSWORD`] || '' })
const accounts = {
  source: account('PHASEB_SOURCE'),
  sourceQm: account('PHASEB_SOURCE_QM'),
  carrier: account('PHASEB_CARRIER'),
  processor: account('PHASEB_PROCESSOR'),
  processorQm: account('PHASEB_PROCESSOR_QM')
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

async function loginAs(browser: Browser, credentials: { username: string; password: string }, actor: string, evidence: WriteEvidence[]) {
  expect(credentials.username).not.toBe('')
  expect(credentials.password).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  recordWrites(page, actor, evidence)
  await page.goto('/login')
  await page.locator('#login-username').fill(credentials.username)
  await page.locator('#login-password').fill(credentials.password)
  const login = waitForApi(page, 'POST', /^\/api\/v1\/auth\/login$/)
  await page.getByRole('button', { name: '登录' }).click()
  expect((await login).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

/** 同一浏览器会话内的真实 API 探测（共享 Session Cookie，现取 CSRF）；不经过页面，也不计入页面写请求证据。 */
async function probe(page: Page, method: string, path: string, body: unknown) {
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()).data
  const response = await page.request.fetch(path, {
    method,
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID(), 'Content-Type': 'application/json' },
    data: body === undefined ? undefined : JSON.stringify(body)
  })
  const json = await response.json().catch(() => ({}))
  return { status: response.status(), code: json.code as string | undefined, data: json.data }
}

const pad = (n: number) => String(n).padStart(2, '0')
/** 与界面 datetime-local（秒精度）一致的本地时间字符串；浏览器与测试进程在同一台机器、同一时区。 */
function localInput(ms: number) {
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** PB2 / PB3：承运商在运输任务详情“在途温度记录”面板登记一条指定测量时间的温度。 */
async function recordTemperature(page: Page, measuredMs: number, temperature: string) {
  await page.getByTestId('temperature-open').click()
  await page.getByTestId('field-temperature-measured-at').fill(localInput(measuredMs))
  await page.getByTestId('field-temperature-value').fill(temperature)
  await page.getByTestId('field-temperature-source').selectOption('MANUAL')
  const post = waitForApi(page, 'POST', new RegExp(`^/api/v1/shipments/${expected.shipmentId}/temperature-records$`))
  await page.getByTestId('temperature-submit').click()
  const response = await post
  expect(response.status()).toBe(201)
  const record = (await response.json()).data
  expect(Date.parse(record.measuredAt)).toBe(Math.floor(measuredMs / 1000) * 1000)
  return record
}

/** PB4：隔离接收方质量管理员在告警详情为某批次提交检验报告（关联告警）。 */
async function inspect(page: Page, batchId: number, reportNo: string, conclusion: 'PASS' | 'FAIL') {
  const block = page.locator(`[data-testid="alert-quality-batch"][data-batch-id="${batchId}"]`)
  await block.getByTestId('inspection-open').click()
  await block.getByTestId('field-inspection-no').fill(reportNo)
  await block.getByTestId('field-inspection-institution').fill('教学演示检测中心（模拟）')
  await block.getByTestId('field-inspection-items').fill('中心温度、感官与挥发性盐基氮（模拟数据）')
  await block.getByTestId('field-inspection-conclusion').selectOption(conclusion)
  await block.getByTestId('field-inspection-source').selectOption('SIMULATED')
  await block.getByTestId('inspection-next').click()
  await expect(block.getByTestId('inspection-confirm-panel')).toBeVisible()
  const post = waitForApi(page, 'POST', new RegExp(`^/api/v1/batches/${batchId}/inspection-reports$`))
  await block.getByTestId('inspection-confirm').click()
  const response = await post
  expect(response.status()).toBe(201)
  const report = (await response.json()).data
  expect(report).toMatchObject({ batchId, submitterRole: 'QUARANTINE_RECEIVER', conclusion, dataSource: 'SIMULATED' })
  await expect(block.locator(`[data-testid="inspection-row"][data-conclusion="${conclusion}"]`)).toHaveCount(1)
  return report
}

test('Phase B: sustained excursion → alert + auto-freeze → quarantine → inspection → release / simulated recall → consumer disposition', async ({ browser }) => {
  // 一个测试串联六个浏览器上下文的完整异常闭环：整体预算高于默认 30 秒，单步断言仍各自快速超时
  test.setTimeout(180_000)
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.x1Id).toBeGreaterThan(0)
  expect(expected.x2Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []
  const loadedAt = Date.parse(expected.loadedAt)
  const minute = 60_000

  // ---------------------------------------------------------------- PB3：承运商在途登记温度，第 4 条使连续越界达到允许时长
  const carrier = await loginAs(browser, accounts.carrier, 'CARRIER', evidence)
  await carrier.page.goto(`/app/shipments/${expected.shipmentId}`)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'IN_TRANSIT')
  await expect(carrier.page.getByTestId('temperature-empty')).toBeVisible()
  const readings = [
    await recordTemperature(carrier.page, loadedAt + 5 * minute, '-18.00'),
    await recordTemperature(carrier.page, loadedAt + 10 * minute, '-12.00'),
    await recordTemperature(carrier.page, loadedAt + 25 * minute, '-11.50')
  ]
  expect(readings.map((r) => r.evaluation)).toEqual(['NORMAL', 'HIGH', 'HIGH'])
  await expect(carrier.page.getByTestId('temperature-rule-band')).toContainText('-25.00 ℃ ~ -15.00 ℃')
  // 两条越界只持续 15 分钟（< 1800 秒）：单点越界本身不产生告警
  await expect(carrier.page.getByTestId('shipment-alerts')).toHaveCount(0)
  readings.push(await recordTemperature(carrier.page, loadedAt + 41 * minute, '-10.80'))
  expect(readings[3].evaluation).toBe('HIGH')
  await expect(carrier.page.getByTestId('shipment-flash')).toContainText('系统已创建持续超温告警并冻结受影响批次')
  await expect(carrier.page.getByTestId('shipment-alert-row')).toHaveCount(1)
  // 回到范围内结束片段 1；片段 2 的首条越界还不构成持续超温，第二条越界使片段 2 也达到 1800 秒
  readings.push(await recordTemperature(carrier.page, loadedAt + 45 * minute, '-18.50'))
  readings.push(await recordTemperature(carrier.page, loadedAt + 50 * minute, '-11.00'))
  await expect(carrier.page.getByTestId('shipment-alert-row')).toHaveCount(1)
  readings.push(await recordTemperature(carrier.page, loadedAt + 81 * minute, '-10.50'))
  expect(readings.map((r) => r.evaluation)).toEqual(['NORMAL', 'HIGH', 'HIGH', 'HIGH', 'NORMAL', 'HIGH', 'HIGH'])
  const alertRows = carrier.page.getByTestId('shipment-alert-row')
  await expect(alertRows).toHaveCount(2)
  const alertIds: number[] = []
  for (const row of await alertRows.all()) {
    await expect(row).toHaveAttribute('data-status', 'OPEN')
    alertIds.push(Number(/\/app\/alerts\/(\d+)$/.exec((await row.locator('a').getAttribute('href')) ?? '')?.[1]))
  }
  const [alertId, alert2Id] = alertIds.sort((a, b) => a - b)
  expect(alertId).toBeGreaterThan(0)
  expect(alert2Id).toBeGreaterThan(alertId)

  // ---------------------------------------------------------------- 来源企业质量管理员：告警详情、自动冻结、确认异常
  const sourceQm = await loginAs(browser, accounts.sourceQm, 'SOURCE_QM', evidence)
  await sourceQm.page.getByTestId('nav-alerts').click()
  const alertNo = (await sourceQm.page.getByTestId(`open-alert-${alertId}`).innerText()).trim()
  expect(alertNo).not.toBe('')
  await sourceQm.page.getByTestId(`open-alert-${alertId}`).click()
  await expect(sourceQm.page).toHaveURL(new RegExp(`/app/alerts/${alertId}$`))
  await expect(sourceQm.page.getByTestId('alert-no')).toHaveText(alertNo)
  await expect(sourceQm.page.getByTestId('alert-status')).toHaveAttribute('data-status', 'OPEN')
  await expect(sourceQm.page.getByTestId('alert-owner')).toHaveText(expected.sourceOrgName)
  await expect(sourceQm.page.getByTestId('alert-duration')).toContainText('31 分钟')
  await expect(sourceQm.page.getByTestId('alert-disclaimer')).toContainText('不代表真实的产品扣留')
  await expect(sourceQm.page.getByTestId('alert-batch-row')).toHaveCount(2)
  await expect(sourceQm.page.locator('[data-testid="alert-batch-row"][data-risk-status="FROZEN"]')).toHaveCount(2)
  for (const cell of await sourceQm.page.getByTestId('alert-batch-auto-frozen').all()) await expect(cell).toHaveText('是（系统自动冻结）')
  await sourceQm.page.getByTestId('alert-ack-open').click()
  await sourceQm.page.getByTestId('field-alert-ack-note').fill('到货前确认在途持续超温，等待隔离检验')
  await sourceQm.page.getByTestId('alert-ack-next').click()
  const ack = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alertId}/acknowledge$`))
  await sourceQm.page.getByTestId('alert-ack-confirm').click()
  expect((await ack).status()).toBe(200)
  await expect(sourceQm.page.getByTestId('alert-status')).toHaveAttribute('data-status', 'ACKNOWLEDGED')
  // A2：两个批次在 A2 创建时已被 A1 冻结，只快照（不是 A2 自动冻结的）
  await sourceQm.page.goto(`/app/alerts/${alert2Id}`)
  const alert2No = (await sourceQm.page.getByTestId('alert-no').innerText()).trim()
  await expect(sourceQm.page.getByTestId('alert-duration')).toContainText('31 分钟')
  for (const cell of await sourceQm.page.getByTestId('alert-batch-auto-frozen').all()) await expect(cell).toHaveText('否（告警时已非正常）')
  await sourceQm.page.getByTestId('alert-ack-open').click()
  await sourceQm.page.getByTestId('alert-ack-next').click()
  const ack2 = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alert2Id}/acknowledge$`))
  await sourceQm.page.getByTestId('alert-ack-confirm').click()
  expect((await ack2).status()).toBe(200)
  await expect(sourceQm.page.getByTestId('alert-status')).toHaveAttribute('data-status', 'ACKNOWLEDGED')
  // 风险历史：系统自动冻结（无操作人），数量 / 责任组织 / 流转状态不变
  await sourceQm.page.goto(`/app/batches/${expected.x1Id}`)
  await expect(sourceQm.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'FROZEN')
  await expect(sourceQm.page.getByTestId('risk-transition-alert-link')).toHaveAttribute('href', `/app/alerts/${alertId}`)

  // ---------------------------------------------------------------- 承运商确认到达
  await carrier.page.reload()
  const arrive = waitForApi(carrier.page, 'POST', new RegExp(`^/api/v1/shipments/${expected.shipmentId}/arrive$`))
  await carrier.page.getByTestId('arrive-shipment').click()
  expect((await arrive).status()).toBe(200)
  await expect(carrier.page.getByTestId('shipment-status')).toHaveAttribute('data-status', 'DELIVERED')

  // ---------------------------------------------------------------- PB4：加工企业不能直接接受冻结批次，隔离收货 X1 / X2
  const processor = await loginAs(browser, accounts.processor, 'PROCESSOR', evidence)
  for (const transferId of [expected.t1Id, expected.t2Id]) {
    await processor.page.goto('/app/transfers/inbound')
    const card = processor.page.locator(`[data-testid="inbound-transfer"][data-transfer-id="${transferId}"]`)
    await expect(card.getByTestId('inbound-frozen-warning')).toContainText('不能直接接受')
    await expect(card.getByTestId(`accept-transfer-${transferId}`)).toHaveCount(0)
    await card.getByTestId(`start-quarantine-${transferId}`).click()
    const siteOption = card.getByTestId(`quarantine-site-${transferId}`).locator('option', { hasText: expected.quarantineSiteName })
    await card.getByTestId(`quarantine-site-${transferId}`).selectOption((await siteOption.getAttribute('value')) ?? '')
    await card.getByTestId(`quarantine-reason-${transferId}`).fill('随车持续超温告警，隔离待检')
    await card.getByTestId(`quarantine-next-${transferId}`).click()
    await expect(card.getByTestId(`quarantine-confirm-panel-${transferId}`)).toContainText('批次责任组织仍为发送方')
    const quarantine = waitForApi(processor.page, 'POST', new RegExp(`^/api/v1/transfers/${transferId}/quarantine$`))
    await card.getByTestId(`quarantine-confirm-${transferId}`).click()
    expect((await quarantine).status()).toBe(200)
    await expect(processor.page.getByTestId('inbound-flash')).toContainText('批次仍由发送方负责')
    await expect(card).toHaveAttribute('data-status', 'QUARANTINED')
    await expect(card.getByTestId('inbound-quarantine-waiting')).toBeVisible()
  }

  // ---------------------------------------------------------------- PB4：加工企业质量管理员（隔离接收方）提交检验证据
  const processorQm = await loginAs(browser, accounts.processorQm, 'PROCESSOR_QM', evidence)
  // 隔离不转移责任：接收方质量管理员不能对仍由发送方负责的批次发起模拟召回（真实 API 403，零写入）
  const quarantinedRecall = await probe(processorQm.page, 'POST', '/api/v1/recalls', { batchIds: [expected.x2Id], reason: 'probe' })
  expect(`${quarantinedRecall.status} ${quarantinedRecall.code}`).toBe('403 ORG_SCOPE_DENIED')
  await processorQm.page.goto(`/app/alerts/${alertId}`)
  await expect(processorQm.page.getByTestId('alert-quality')).toBeVisible()
  await expect(processorQm.page.getByTestId('alert-ack-open')).toHaveCount(0)
  await inspect(processorQm.page, expected.x1Id, 'PB-RCV-X1-PASS', 'PASS')
  await inspect(processorQm.page, expected.x2Id, 'PB-RCV-X2-FAIL', 'FAIL')
  // 接收方只能提交证据，不能放行 / 召回 / 形成处置结论
  await expect(processorQm.page.getByTestId(`alert-release-open-${expected.x1Id}`)).toHaveCount(0)
  await expect(processorQm.page.getByTestId('recall-start-open')).toHaveCount(0)
  await expect(processorQm.page.getByTestId('alert-resolve-open')).toHaveCount(0)
  // A2 是独立的质量调查：X1 需要关联 A2 的检验证据
  await processorQm.page.goto(`/app/alerts/${alert2Id}`)
  await inspect(processorQm.page, expected.x1Id, 'PB-RCV-X1-A2-PASS', 'PASS')
  // 隔离接收方看不到发货方的其他风险事项明细
  await expect(processorQm.page.getByTestId('alert-batch-pending-holds')).toHaveCount(0)

  // ---------------------------------------------------------------- 来源质量管理员：依据合格结论放行 X1
  await sourceQm.page.goto(`/app/alerts/${alertId}`)
  const x1Block = sourceQm.page.locator(`[data-testid="alert-quality-batch"][data-batch-id="${expected.x1Id}"]`)
  const x2Block = sourceQm.page.locator(`[data-testid="alert-quality-batch"][data-batch-id="${expected.x2Id}"]`)
  await expect(x1Block.getByTestId('recall-start-open')).toBeVisible()
  await sourceQm.page.getByTestId(`alert-release-open-${expected.x1Id}`).click()
  await sourceQm.page.getByTestId('field-alert-release-note').fill('隔离检验合格')
  await sourceQm.page.getByTestId('alert-release-next').click()
  const release = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alertId}/batches/${expected.x1Id}/release$`))
  await sourceQm.page.getByTestId('alert-release-confirm').click()
  expect((await release).status()).toBe(200)
  // 独立评审修复：A1 的合格结论只解除 A1 的风险事项，X1 仍被 A2 冻结
  await expect(sourceQm.page.locator(`[data-testid="alert-batch-row"][data-batch-id="${expected.x1Id}"]`)).toHaveAttribute('data-risk-status', 'FROZEN')
  await expect(sourceQm.page.getByTestId('alert-flash')).toContainText(`批次仍被告警 ${alert2No}冻结`)
  await expect(x1Block.getByTestId('alert-batch-pending-alert')).toHaveAttribute('href', `/app/alerts/${alert2Id}`)
  await expect(sourceQm.page.getByTestId(`alert-release-open-${expected.x1Id}`)).toHaveCount(0)
  // 不合格的 X2 不能放行，只能召回（或等待新的证据）
  await expect(sourceQm.page.getByTestId(`alert-release-open-${expected.x2Id}`)).toHaveCount(0)
  // 批次风险面板：仍未解除的告警风险事项，人工解除冻结入口隐藏；接收方此时仍不能接受
  await sourceQm.page.goto(`/app/batches/${expected.x1Id}`)
  await expect(sourceQm.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'FROZEN')
  await expect(sourceQm.page.getByTestId('risk-hold-alert')).toContainText(alert2No)
  await expect(sourceQm.page.getByTestId('risk-release-open')).toHaveCount(0)
  const acceptTooEarly = await probe(processor.page, 'POST', `/api/v1/transfers/${expected.t1Id}/accept`, {
    receivedQuantity: 300, unitCode: 'kg', occurredAt: new Date(Date.now() - 1_000).toISOString(),
    expectedVersion: (await (await processor.page.request.get(`/api/v1/transfers/${expected.t1Id}`)).json()).data.version
  })
  expect(`${acceptTooEarly.status} ${acceptTooEarly.code}`).toMatch(/^(409|422) BATCH_FLOW_BLOCKED$/)
  // 依据 A2 的合格结论形成 A2 的放行结论：解除最后一个风险事项，X1 恢复正常
  await sourceQm.page.goto(`/app/alerts/${alert2Id}`)
  await sourceQm.page.getByTestId(`alert-release-open-${expected.x1Id}`).click()
  await sourceQm.page.getByTestId('alert-release-next').click()
  const release2 = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alert2Id}/batches/${expected.x1Id}/release$`))
  await sourceQm.page.getByTestId('alert-release-confirm').click()
  expect((await release2).status()).toBe(200)
  await expect(sourceQm.page.locator(`[data-testid="alert-batch-row"][data-batch-id="${expected.x1Id}"]`)).toHaveAttribute('data-risk-status', 'NORMAL')
  await expect(sourceQm.page.getByTestId('alert-flash')).toContainText('风险状态恢复正常')
  await sourceQm.page.goto(`/app/alerts/${alertId}`)

  // ---------------------------------------------------------------- PB5：来源质量管理员对不合格的 X2 发起模拟召回
  await x2Block.getByTestId('recall-start-open').click()
  await x2Block.getByTestId('field-recall-reason').fill('隔离检验不合格，启动模拟召回')
  await x2Block.getByTestId('recall-start-next').click()
  await expect(x2Block.getByTestId('recall-start-confirm-panel')).toContainText('不代表真实法定召回')
  const startRecall = waitForApi(sourceQm.page, 'POST', /^\/api\/v1\/recalls$/)
  await x2Block.getByTestId('recall-start-confirm').click()
  const started = await startRecall
  expect(started.status()).toBe(201)
  expect(JSON.parse(started.request().postData() || '{}')).toEqual({ batchIds: [expected.x2Id], reason: '隔离检验不合格，启动模拟召回', alertId })
  const recall = (await started.json()).data
  await expect(sourceQm.page).toHaveURL(new RegExp(`/app/recalls/${recall.id}$`))
  await expect(sourceQm.page.getByTestId('recall-status')).toHaveAttribute('data-status', 'IN_PROGRESS')
  await expect(sourceQm.page.getByTestId('recall-disclaimer')).toContainText('不代表真实法定召回')
  const seedRow = sourceQm.page.locator('[data-testid="recall-scope-SEED"] [data-testid="recall-scope-row"]')
  await expect(seedRow).toHaveCount(1)
  await expect(seedRow).toHaveAttribute('data-action', 'RECALLED')
  await expect(seedRow).toHaveAttribute('data-risk-status', 'RECALLED')
  await expect(seedRow).toContainText(expected.x2TraceBatchNo)
  expect(recall.scope).toEqual([expect.objectContaining({
    batchId: expected.x2Id, scopeRole: 'SEED', action: 'RECALLED', riskStatusBefore: 'FROZEN', openTransferStatus: 'QUARANTINED',
    shipmentStatus: 'DELIVERED', remainingQuantity: 200, soldQuantity: 0, publicCodeActive: true
  })])

  // ---------------------------------------------------------------- 加工企业：按隔离实收数量接受 X1；拒收已召回的 X2
  await processor.page.goto('/app/transfers/inbound')
  const t1Card = processor.page.locator(`[data-testid="inbound-transfer"][data-transfer-id="${expected.t1Id}"]`)
  const t2Card = processor.page.locator(`[data-testid="inbound-transfer"][data-transfer-id="${expected.t2Id}"]`)
  await expect(t1Card.getByTestId('inbound-quarantine-ready')).toBeVisible()
  await expect(t2Card.getByTestId('inbound-batch-risk')).toHaveAttribute('data-status', 'RECALLED')
  await expect(t2Card.getByTestId(`accept-transfer-${expected.t2Id}`)).toHaveCount(0)
  await t2Card.getByTestId(`start-reject-${expected.t2Id}`).click()
  await t2Card.getByTestId(`reject-reason-${expected.t2Id}`).fill('发货方已启动模拟召回，退回发货方')
  const reject = waitForApi(processor.page, 'POST', new RegExp(`^/api/v1/transfers/${expected.t2Id}/reject$`))
  await t2Card.getByTestId(`reject-transfer-${expected.t2Id}`).click()
  expect((await (await reject).json()).data.status).toBe('REJECTED')
  await expect(processor.page.getByTestId('inbound-flash')).toContainText('批次仍由发送方负责')
  const accept = waitForApi(processor.page, 'POST', new RegExp(`^/api/v1/transfers/${expected.t1Id}/accept$`))
  await processor.page.getByTestId(`accept-transfer-${expected.t1Id}`).click()
  const accepted = await accept
  expect((await accepted.json()).data.status).toBe('ACCEPTED')
  expect(JSON.parse(accepted.request().postData() || '{}').receivedQuantity).toBe(300)
  await expect(processor.page).toHaveURL(new RegExp(`/app/batches/${expected.x1Id}$`))
  await expect(processor.page.getByTestId('detail-org-name')).toHaveText(expected.processorOrgName)
  await expect(processor.page.getByTestId('detail-risk-status')).toHaveText('正常')

  // ---------------------------------------------------------------- 来源质量管理员：形成告警处置结论、以“退回”关闭模拟召回
  await sourceQm.page.goto(`/app/alerts/${alertId}`)
  await expect(sourceQm.page.locator(`[data-testid="alert-batch-row"][data-batch-id="${expected.x2Id}"]`)).toHaveAttribute('data-risk-status', 'RECALLED')
  await sourceQm.page.getByTestId('alert-resolve-open').click()
  await sourceQm.page.getByTestId('field-alert-resolution').fill('X1 复检合格放行并由加工企业接受；X2 不合格已模拟召回并被拒收退回')
  await sourceQm.page.getByTestId('alert-resolve-next').click()
  const resolve = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alertId}/resolve$`))
  await sourceQm.page.getByTestId('alert-resolve-confirm').click()
  expect((await resolve).status()).toBe(200)
  await expect(sourceQm.page.getByTestId('alert-status')).toHaveAttribute('data-status', 'RESOLVED')
  await expect(sourceQm.page.getByTestId('alert-history-row')).toHaveCount(4)
  // A2：X1 已有 A2 的放行结论，X2 已进入模拟召回（召回同时解除了 A2 的风险事项）
  await sourceQm.page.goto(`/app/alerts/${alert2Id}`)
  await expect(sourceQm.page.locator(`[data-testid="alert-batch-row"][data-batch-id="${expected.x2Id}"]`)).toHaveAttribute('data-risk-status', 'RECALLED')
  await sourceQm.page.getByTestId('alert-resolve-open').click()
  await sourceQm.page.getByTestId('field-alert-resolution').fill('片段 2：X1 复检合格放行；X2 已随片段 1 的不合格结论进入模拟召回')
  await sourceQm.page.getByTestId('alert-resolve-next').click()
  const resolve2 = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/alerts/${alert2Id}/resolve$`))
  await sourceQm.page.getByTestId('alert-resolve-confirm').click()
  expect((await resolve2).status()).toBe(200)
  await expect(sourceQm.page.getByTestId('alert-status')).toHaveAttribute('data-status', 'RESOLVED')

  await sourceQm.page.goto(`/app/recalls/${recall.id}`)
  await sourceQm.page.getByTestId('recall-close-open').click()
  await sourceQm.page.getByTestId('field-recall-disposition').selectOption('RETURNED')
  await sourceQm.page.getByTestId('field-recall-summary').fill('X2 200kg 已被加工企业拒收并按演练流程退回来源企业冷库')
  await sourceQm.page.getByTestId('recall-close-next').click()
  const close = waitForApi(sourceQm.page, 'POST', new RegExp(`^/api/v1/recalls/${recall.id}/close$`))
  await sourceQm.page.getByTestId('recall-close-confirm').click()
  expect((await close).status()).toBe(200)
  await expect(sourceQm.page.getByTestId('recall-status')).toHaveAttribute('data-status', 'CLOSED')
  await expect(sourceQm.page.getByTestId('recall-disposition')).toContainText('退回')
  // X2 风险终态：风险历史链接模拟召回，冻结 / 解除入口消失
  await sourceQm.page.goto(`/app/batches/${expected.x2Id}`)
  await expect(sourceQm.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'RECALLED')
  await expect(sourceQm.page.getByTestId('risk-recalled-note')).toBeVisible()
  await expect(sourceQm.page.getByTestId('risk-transition-recall-link')).toHaveAttribute('href', `/app/recalls/${recall.id}`)
  await expect(sourceQm.page.getByTestId('risk-freeze-open')).toHaveCount(0)
  await expect(sourceQm.page.getByTestId('risk-release-open')).toHaveCount(0)
  await expect(sourceQm.page.getByTestId('recall-start-open')).toHaveCount(0)

  // ---------------------------------------------------------------- RECALLED 终态探测（真实 API，零写入）
  const source = await loginAs(browser, accounts.source, 'SOURCE', evidence)
  const probes = [
    ['manual release of RECALLED X2', await probe(sourceQm.page, 'POST', `/api/v1/batches/${expected.x2Id}/risk/release`, { reason: 'probe' })],
    ['freeze RECALLED X2', await probe(sourceQm.page, 'POST', `/api/v1/batches/${expected.x2Id}/risk/freeze`, { reason: 'probe' })],
    ['recall X2 again', await probe(sourceQm.page, 'POST', '/api/v1/recalls', { batchIds: [expected.x2Id], reason: 'probe' })],
    ['transfer RECALLED X2', await probe(source.page, 'POST', '/api/v1/transfers', { batchId: expected.x2Id, receiverOrgId: expected.processorOrgId })],
    ['disable X2 public code', await probe(source.page, 'POST', `/api/v1/batches/${expected.x2Id}/public-trace-code/disable`, undefined)],
    ['close recall again', await probe(sourceQm.page, 'POST', `/api/v1/recalls/${recall.id}/close`, { publicDisposition: 'DESTROYED', resultSummary: 'probe' })]
  ] as const
  expect(probes.map(([name, r]) => `${name} ${r.status} ${r.code ?? ''}`)).toEqual([
    'manual release of RECALLED X2 409 INVALID_STATE_TRANSITION',
    'freeze RECALLED X2 409 INVALID_STATE_TRANSITION',
    'recall X2 again 409 INVALID_STATE_TRANSITION',
    'transfer RECALLED X2 409 BATCH_NOT_ACTIVE',
    'disable X2 public code 409 PUBLIC_TRACE_CODE_RECALL_LOCKED',
    'close recall again 409 INVALID_STATE_TRANSITION'
  ])

  // ---------------------------------------------------------------- PB6：匿名消费者扫码（全新上下文，无登录）
  const consumerContext = await browser.newContext()
  const consumer = await consumerContext.newPage()
  const publicCalls: Array<{ method: string; path: string; cookie: boolean }> = []
  consumer.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) publicCalls.push({ method: request.method(), path: url.pathname, cookie: Boolean(request.headers()['cookie']) })
  })
  const x2Response = consumer.waitForResponse((r) => new URL(r.url()).pathname === `/api/public/v1/public/traces/${expected.x2PublicId}`)
  await consumer.goto(`/trace/${expected.x2PublicId}`)
  const x2Body = await (await x2Response).text()
  const x2 = JSON.parse(x2Body).data
  expect(x2).toMatchObject({ flowStatus: 'ACTIVE', riskStatus: 'RECALLED', recallDisposition: { status: 'CLOSED' } })
  expect(x2.recallDisposition.label).toContain('退回')
  expect(x2.temperatureSummary.result).toBe('INSUFFICIENT_DATA')
  await expect(consumer.locator('.recall-alert-card')).toBeVisible()
  await expect(consumer.locator('.recall-alert-card')).toContainText('教学演练')
  await expect(consumer.getByTestId('public-risk-status')).toHaveText('模拟召回')
  await expect(consumer.getByTestId('public-flow-status')).toHaveText('可流转')
  const disposition = consumer.getByTestId('public-recall-disposition')
  await expect(disposition).toHaveAttribute('data-status', 'CLOSED')
  await expect(disposition).toContainText('处置已完成')
  await expect(disposition).toContainText('退回')
  await expect(disposition).toContainText('非真实召回结论')
  await expect(consumer.locator('.temp-summary-card')).toContainText('暂无实时时序采集')
  // 告警 / 检验 / 召回的内部事实（编号、原因、处置总结、温度读数）与企业信息都不得进入匿名页面或响应
  const forbidden = [...expected.forbidden, alertNo, alert2No, recall.recallNo, 'PB-RCV-X1-PASS', 'PB-RCV-X1-A2-PASS', 'PB-RCV-X2-FAIL',
    '教学演示检测中心（模拟）', '隔离检验不合格', '随车持续超温', '拒收并按演练流程退回', '发货方已启动模拟召回', '片段 2',
    '-10.80', '-11.50', '-12.00', '-18.50', '-11.00', '-10.50', 'QUARANTINE', 'ALERT']
  const x2Text = await consumer.locator('body').innerText()
  for (const secret of forbidden) {
    expect(x2Body, `public X2 response leaks ${secret}`).not.toContain(secret)
    expect(x2Text, `public X2 page leaks ${secret}`).not.toContain(secret)
  }

  const x1Response = consumer.waitForResponse((r) => new URL(r.url()).pathname === `/api/public/v1/public/traces/${expected.x1PublicId}`)
  await consumer.goto(`/trace/${expected.x1PublicId}`)
  const x1Body = await (await x1Response).text()
  expect(JSON.parse(x1Body).data).toMatchObject({ flowStatus: 'ACTIVE', riskStatus: 'NORMAL' })
  expect(JSON.parse(x1Body).data.recallDisposition).toBeUndefined()
  await expect(consumer.getByTestId('public-risk-status')).toHaveText('正常')
  await expect(consumer.locator('.recall-alert-card')).toHaveCount(0)
  for (const secret of forbidden) expect(x1Body, `public X1 response leaks ${secret}`).not.toContain(secret)
  expect(publicCalls.length).toBeGreaterThan(0)
  for (const call of publicCalls) {
    expect(`${call.method} ${call.path}`).toMatch(/^GET \/api\/public\/v1\/public\/traces\/[A-Z2-7]{26}$/)
    expect(call.cookie, `${call.path} must be anonymous`).toBe(false)
  }

  // ---------------------------------------------------------------- 页面写请求证据
  const businessWrites = evidence.filter((e) => !e.path.startsWith('/api/v1/auth/'))
  expect(businessWrites.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')}`)).toEqual([
    ...Array<string>(7).fill('CARRIER POST /api/v1/shipments/:id/temperature-records'),
    'SOURCE_QM POST /api/v1/alerts/:id/acknowledge',
    'SOURCE_QM POST /api/v1/alerts/:id/acknowledge',
    'CARRIER POST /api/v1/shipments/:id/arrive',
    'PROCESSOR POST /api/v1/transfers/:id/quarantine',
    'PROCESSOR POST /api/v1/transfers/:id/quarantine',
    'PROCESSOR_QM POST /api/v1/batches/:id/inspection-reports',
    'PROCESSOR_QM POST /api/v1/batches/:id/inspection-reports',
    'PROCESSOR_QM POST /api/v1/batches/:id/inspection-reports',
    'SOURCE_QM POST /api/v1/alerts/:id/batches/:id/release',
    'SOURCE_QM POST /api/v1/alerts/:id/batches/:id/release',
    'SOURCE_QM POST /api/v1/recalls',
    'PROCESSOR POST /api/v1/transfers/:id/reject',
    'PROCESSOR POST /api/v1/transfers/:id/accept',
    'SOURCE_QM POST /api/v1/alerts/:id/resolve',
    'SOURCE_QM POST /api/v1/alerts/:id/resolve',
    'SOURCE_QM POST /api/v1/recalls/:id/close'
  ])
  for (const e of businessWrites) {
    expect(e.status, `${e.method} ${e.path}`).toBeLessThan(300)
    expect(e.csrf, `${e.method} ${e.path} CSRF`).toBe(true)
    expect(e.idempotencyKey, `${e.method} ${e.path} Idempotency-Key`).toBe(true)
    expect(e.requestId, `${e.method} ${e.path} X-Request-Id`).not.toBe('')
  }
  console.log('[phase-b-smoke] 页面写请求证据（actor method path status requestId）:')
  for (const e of businessWrites) console.log(`  ${e.actor} ${e.method} ${e.path} ${e.status} ${e.requestId}`)
  console.log('[phase-b-smoke] RECALLED 终态与越权探测（name status code）:')
  console.log(`  quarantine receiver recall ${quarantinedRecall.status} ${quarantinedRecall.code ?? ''}`)
  for (const [name, r] of probes) console.log(`  ${name} ${r.status} ${r.code ?? ''}`)
  console.log(`[phase-b-smoke] 在途温度: ${readings.map((r) => `${r.temperature}℃=${r.evaluation}`).join(', ')}`)
  console.log(`[phase-b-smoke] 同一批次两个告警：依据 A1 放行后 X1 仍被 ${alert2No} 冻结，接受探测 ${acceptTooEarly.status} ${acceptTooEarly.code}；依据 A2 放行后恢复正常`)
  console.log(`[phase-b-smoke] PHASEB_RESULT ${JSON.stringify({ alertId, alert2Id, recallId: recall.id, recallNo: recall.recallNo, readings: readings.map((r) => r.id) })}`)

  await Promise.all([carrier.context.close(), sourceQm.context.close(), processor.context.close(), processorQm.context.close(),
    source.context.close(), consumerContext.close()])
})
