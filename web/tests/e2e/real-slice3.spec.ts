import { test, expect, type Browser, type Page, type Response } from '@playwright/test'

/**
 * Phase A / Slice 3 真实浏览器验收（由 scripts/smoke.mjs 在 Slice 2 之后于同一隔离 schema 编排）：PROCESS / SPLIT。
 *
 * 加工企业账号在真实 Session + CSRF 下操作，禁止 page.route 或任何接口替身：所有请求经 Vite proxy 到真实 Spring Boot，
 * 数据来自隔离 schema 中的真实 MySQL 8.4。证据只输出方法、路径、状态码与请求编号，绝不输出密码、Cookie 或 CSRF 凭据。
 *
 * 路径：加工登录 → B0（Slice 2 已接受，1000kg）→ 加工：产出 960，损耗 30，留样 10 → 落到 B1（ACTIVE，PROCESS 事件）
 *       → 回看 B0（CLOSED，剩余 0，入口消失）→ B1 拆分：600 + 360 → 操作详情（B1 CLOSED，B2/B3 ACTIVE，无事件）。
 */

interface Slice3Expected {
  b0Id: number
  b0TraceBatchNo: string
}

const expected: Slice3Expected = JSON.parse(process.env.SLICE3_EXPECTED || '{}')
const processor = { username: process.env.SLICE3_PROCESSOR_USERNAME || '', password: process.env.SLICE3_PROCESSOR_PASSWORD || '' }

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

test('Slice 3: PROCESSOR processes B0 (1000 = 960 + 30 + 10) and splits B1 (960 = 600 + 360)', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b0Id).toBeGreaterThan(0)
  const evidence: WriteEvidence[] = []
  const { context, page } = await login(browser, evidence)

  // ---- PROCESS ----
  await page.goto(`/app/batches/${expected.b0Id}`)
  await expect(page.getByTestId('detail-trace-batch-no')).toHaveText(expected.b0TraceBatchNo)
  await expect(page.getByTestId('detail-remaining-quantity')).toContainText('1,000 kg')
  await page.getByTestId('start-process').click()
  await expect(page.getByTestId('wizard-title')).toHaveText('加工批次')
  await expect(page.getByTestId('wizard-input-quantity')).toContainText('1,000 kg')
  await page.getByTestId('output-quantity-0').fill('960')
  await page.getByTestId('loss-quantity').fill('30')
  await expect(page.getByTestId('wizard-submit')).toBeDisabled()
  await page.getByTestId('sample-quantity').fill('10')
  await expect(page.getByTestId('balance-indicator')).toHaveAttribute('data-balanced', 'true')
  const processSubmit = waitForApi(page, 'POST', /^\/api\/v1\/batch-operations\/\d+\/submit$/)
  await page.getByTestId('wizard-submit').click()
  expect((await processSubmit).status()).toBe(200)

  await expect(page).toHaveURL(/\/app\/batches\/\d+$/)
  const b1Id = Number(new URL(page.url()).pathname.split('/').pop())
  expect(b1Id).not.toBe(expected.b0Id)
  await expect(page.getByTestId('batch-flash')).toContainText('PROCESS')
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
  await expect(page.getByTestId('detail-quantity')).toContainText('960 kg')
  await expect(page.getByTestId('lineage-parent')).toHaveText(expected.b0TraceBatchNo)
  await expect(page.getByTestId('trace-event')).toHaveCount(1)
  await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'PROCESS')
  await expect(page.getByTestId('trace-event')).toContainText('加工产出 960 kg（投入 1000 kg，损耗 30 kg，留样 10 kg）')
  const b1TraceBatchNo = (await page.getByTestId('detail-trace-batch-no').textContent())?.trim() ?? ''

  // B0 已全量消耗：CLOSED、剩余 0、不再提供加工 / 拆分 / 交接入口
  await page.goto(`/app/batches/${expected.b0Id}`)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(page.getByTestId('detail-remaining-quantity')).toHaveText('0 kg')
  await expect(page.getByTestId('operation-consumed')).toBeVisible()
  await expect(page.getByTestId('lineage-child')).toHaveText(b1TraceBatchNo)
  await expect(page.getByTestId('start-process')).toHaveCount(0)
  await expect(page.getByTestId('initiate-transfer')).toHaveCount(0)

  // ---- SPLIT ----
  await page.goto(`/app/batches/${b1Id}`)
  await page.getByTestId('start-split').click()
  await expect(page.getByTestId('wizard-title')).toHaveText('拆分批次')
  await expect(page.getByTestId('wizard-input-quantity')).toContainText('960 kg')
  await expect(page.getByTestId('output-product-0')).toHaveCount(0)
  await page.getByTestId('output-quantity-0').fill('600')
  await page.getByTestId('output-quantity-1').fill('360')
  await expect(page.getByTestId('balance-indicator')).toHaveAttribute('data-balanced', 'true')
  const splitSubmit = waitForApi(page, 'POST', /^\/api\/v1\/batch-operations\/\d+\/submit$/)
  await page.getByTestId('wizard-submit').click()
  expect((await splitSubmit).status()).toBe(200)

  await expect(page).toHaveURL(/\/app\/batch-operations\/\d+$/)
  await expect(page.getByTestId('operation-status')).toHaveAttribute('data-status', 'SUBMITTED')
  await expect(page.getByTestId('operation-type')).toHaveText('拆分')
  await expect(page.getByTestId('operation-balance')).toHaveAttribute('data-balanced', 'true')
  const flows = await page.getByTestId('operation-item-flow').evaluateAll((els) => els.map((el) => el.getAttribute('data-flow')))
  expect(flows).toEqual(['CLOSED', 'ACTIVE', 'ACTIVE'])

  const childLinks = page.locator('[data-testid="operation-item"][data-role="OUTPUT"] [data-testid="operation-item-batch"]')
  await expect(childLinks).toHaveCount(2)
  await childLinks.first().click()
  await expect(page).toHaveURL(/\/app\/batches\/\d+$/)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
  await expect(page.getByTestId('detail-quantity')).toContainText('600 kg')
  await expect(page.getByTestId('lineage-parent')).toHaveText(b1TraceBatchNo)
  await expect(page.getByTestId('events-empty')).toBeVisible()

  const writes = evidence.filter((e) => e.path.startsWith('/api/v1/batch-operations'))
  expect(writes.map((e) => `${e.method} ${e.path.replace(/\/\d+(?=\/|$)/g, '/:id')} ${e.status}`)).toEqual([
    'POST /api/v1/batch-operations 201',
    'POST /api/v1/batch-operations/:id/submit 200',
    'POST /api/v1/batch-operations 201',
    'POST /api/v1/batch-operations/:id/submit 200'
  ])
  for (const w of writes) {
    expect(w.csrf).toBe(true)
    expect(w.idempotencyKey).toBe(true)
  }
  console.log('[real-slice3] writes:')
  for (const w of evidence) console.log(`  ${w.method} ${w.path} -> ${w.status} (requestId ${w.requestId})`)
  await context.close()
})
