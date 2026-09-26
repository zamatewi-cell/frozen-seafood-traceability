import { test, expect, type Browser, type Page, type Response } from '@playwright/test'

/**
 * Phase B 独立评审修复真实浏览器验收（由 scripts/smoke.mjs 编排）：召回通知随批次交接转给新的当前责任组织。
 *
 * 夹具（smoke.mjs 经真实 API）：加工企业把 Phase B 中接受的 X1 加工为 X1P，交给零售企业 RA 并被接受；加工企业质量管理员提交
 * X1 的不合格检验报告后对 X1 发起模拟召回——X1P 由 RA 持有，只通知持有方（跨组织不代为冻结 / 召回）；通知期间 X1P 仍为正常，
 * RA 再把 X1P 整批交给零售企业 RB 并被接受。以下全部在相互隔离的浏览器上下文中完成，禁止 page.route 或任何接口替身：
 *   RB 质量管理员在 X1P 批次详情看到上游模拟召回通知 → 打开召回，只看到本组织当前负责的 X1P 行（发起时快照 + 当前状态）
 *   → 在风险面板风险冻结 X1P（RB 的内部当前事实）→ RA 质量管理员（发起时的持有方）只看到发起时快照，看不到 RB 的冻结
 *   → 召回发起组织仍看到范围批次的当前状态（召回跟踪）。
 */

interface NoticeExpected {
  recallId: number
  recallNo: string
  x1pId: number
  x1pTraceBatchNo: string
  processorOrgName: string
}

const expected: NoticeExpected = JSON.parse(process.env.PHASEB_NOTICE_EXPECTED || '{}')
const account = (prefix: string) => ({ username: process.env[`${prefix}_USERNAME`] || '', password: process.env[`${prefix}_PASSWORD`] || '' })
const accounts = {
  newHolderQm: account('PHASEB_NOTICE_RB_QM'),
  formerHolderQm: account('PHASEB_NOTICE_RA_QM'),
  ownerQm: account('PHASEB_NOTICE_OWNER_QM')
}

interface WriteEvidence {
  actor: string
  method: string
  path: string
  status: number
  csrf: boolean
  idempotencyKey: boolean
}

function waitForApi(page: Page, method: string, pathPattern: RegExp) {
  return page.waitForResponse((response) => response.request().method() === method && pathPattern.test(new URL(response.url()).pathname))
}

async function loginAs(browser: Browser, credentials: { username: string; password: string }, actor: string, evidence: WriteEvidence[]) {
  expect(credentials.username).not.toBe('')
  expect(credentials.password).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    const method = response.request().method()
    if (!url.pathname.startsWith('/api/v1/') || method === 'GET' || url.pathname.startsWith('/api/v1/auth/')) return
    const headers = response.request().headers()
    evidence.push({ actor, method, path: url.pathname, status: response.status(), csrf: Boolean(headers['x-csrf-token']),
      idempotencyKey: (headers['idempotency-key'] || '').length >= 16 })
  })
  await page.goto('/login')
  await page.locator('#login-username').fill(credentials.username)
  await page.locator('#login-password').fill(credentials.password)
  const login = waitForApi(page, 'POST', /^\/api\/v1\/auth\/login$/)
  await page.getByRole('button', { name: '登录' }).click()
  expect((await login).status()).toBe(200)
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

test('recall notice follows the handed-over batch: the new holder sees and acts on it, the former holder keeps only its snapshot', async ({ browser }) => {
  test.setTimeout(120_000)
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.recallId).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []

  // ---------------------------------------------------------------- RB（当前责任组织）：批次详情中的上游召回通知
  const rb = await loginAs(browser, accounts.newHolderQm, 'RB_QM', evidence)
  await rb.page.goto(`/app/batches/${expected.x1pId}`)
  await expect(rb.page.getByTestId('detail-trace-batch-no')).toHaveText(expected.x1pTraceBatchNo)
  await expect(rb.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'NORMAL')
  const notice = rb.page.getByTestId('risk-recall-notice')
  await expect(notice).toHaveCount(1)
  await expect(notice).toContainText(expected.recallNo)
  await expect(notice).toContainText(expected.processorOrgName)
  await expect(rb.page.getByTestId('risk-recall-notices')).toContainText('以上游召回为证据发起本组织的模拟召回')

  // RB 打开召回：只看到本组织当前负责的 X1P 行（发起时快照 + 当前状态）
  await notice.locator('a').click()
  await expect(rb.page).toHaveURL(new RegExp(`/app/recalls/${expected.recallId}$`))
  await expect(rb.page.getByTestId('recall-scope-row')).toHaveCount(1)
  const rbRow = rb.page.getByTestId('recall-scope-row')
  await expect(rbRow).toHaveAttribute('data-batch-id', String(expected.x1pId))
  await expect(rbRow).toHaveAttribute('data-held-by-viewer', 'true')
  await expect(rbRow).toHaveAttribute('data-risk-status', 'NORMAL')
  await expect(rbRow).toHaveAttribute('data-action', 'NOTIFY_HOLDER')
  await expect(rb.page.getByTestId('recall-next-step')).toContainText('本组织当前负责的后续批次已被上游模拟召回圈定')
  await expect(rb.page.getByTestId('recall-close-open')).toHaveCount(0)
  await expect(rb.page.getByTestId('recall-result-summary')).toHaveCount(0)

  // RB 依据通知在批次风险面板风险冻结 X1P（这是 RB 的内部当前事实）
  await rbRow.locator('a').click()
  await expect(rb.page).toHaveURL(new RegExp(`/app/batches/${expected.x1pId}$`))
  await rb.page.getByTestId('risk-freeze-open').click()
  await rb.page.getByTestId('field-risk-reason').fill('收到上游模拟召回通知，冻结调查')
  await rb.page.getByTestId('risk-next').click()
  const freeze = waitForApi(rb.page, 'POST', new RegExp(`^/api/v1/batches/${expected.x1pId}/risk/freeze$`))
  await rb.page.getByTestId('risk-confirm').click()
  expect((await freeze).status()).toBe(201)
  await expect(rb.page.getByTestId('risk-current')).toHaveAttribute('data-status', 'FROZEN')
  await expect(rb.page.getByTestId('risk-hold-manual')).toBeVisible()
  await expect(rb.page.getByTestId('risk-recall-notice')).toHaveCount(1)

  // ---------------------------------------------------------------- RA（发起时的持有方，已转出）：只看发起时快照
  const ra = await loginAs(browser, accounts.formerHolderQm, 'RA_QM', evidence)
  await ra.page.getByTestId('nav-recalls').click()
  const raListRow = ra.page.locator(`[data-testid="recall-row"][data-recall-id="${expected.recallId}"]`)
  await expect(raListRow.getByTestId('recall-relation')).toHaveAttribute('data-relation', 'HISTORICAL_HOLDER')
  await expect(raListRow.getByTestId('recall-relation')).toHaveText('本组织曾持有范围批次（历史快照，只读）')
  await ra.page.goto(`/app/recalls/${expected.recallId}`)
  const raRow = ra.page.getByTestId('recall-scope-row')
  await expect(raRow).toHaveCount(1)
  await expect(raRow).toHaveAttribute('data-held-by-viewer', 'false')
  await expect(raRow).not.toHaveAttribute('data-risk-status', /.+/)
  await expect(raRow.getByTestId('recall-scope-current-hidden')).toContainText('只显示发起时快照')
  await expect(raRow.locator('a')).toHaveCount(0)
  await expect(raRow).not.toContainText('冻结')
  await expect(ra.page.getByTestId('recall-next-step')).toContainText('召回通知由批次的当前责任组织处置')
  const raResponse = await ra.page.request.get(`/api/v1/recalls/${expected.recallId}`)
  const raBody = await raResponse.text()
  expect(raResponse.status()).toBe(200)
  expect(raBody).not.toContain('FROZEN')
  expect(raBody).not.toContain('currentRiskStatus')
  const raHolds = await ra.page.request.get(`/api/v1/batches/${expected.x1pId}/risk-holds`)
  expect(raHolds.status(), 'the former holder cannot read the new holder risk holds').toBe(403)

  // ---------------------------------------------------------------- 召回发起组织：仍跟踪范围批次的当前状态
  const owner = await loginAs(browser, accounts.ownerQm, 'OWNER_QM', evidence)
  await owner.page.goto(`/app/recalls/${expected.recallId}`)
  const ownerRow = owner.page.locator(`[data-testid="recall-scope-row"][data-batch-id="${expected.x1pId}"]`)
  await expect(ownerRow).toHaveAttribute('data-action', 'NOTIFY_HOLDER')
  await expect(ownerRow).toHaveAttribute('data-risk-status', 'FROZEN')
  await expect(ownerRow).toHaveAttribute('data-held-by-viewer', 'false')

  // ---------------------------------------------------------------- 页面写请求证据：只有 RB 的风险冻结
  expect(evidence.map((e) => `${e.actor} ${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')} ${e.status}`)).toEqual([
    'RB_QM POST /api/v1/batches/:id/risk/freeze 201'
  ])
  expect(evidence.every((e) => e.csrf && e.idempotencyKey)).toBe(true)
  console.log(`[phase-b-notice-smoke] RB 看到召回通知 ${expected.recallNo} 并风险冻结 X1P；RA 只见发起时快照（风险事项查询 ${raHolds.status()}）；发起组织跟踪到 FROZEN`)

  await Promise.all([rb.context.close(), ra.context.close(), owner.context.close()])
})
