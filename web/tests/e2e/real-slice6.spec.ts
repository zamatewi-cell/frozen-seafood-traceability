import { test, expect, type Browser, type Page, type Request, type Response } from '@playwright/test'

/**
 * Phase A / Slice 6 真实浏览器验收（由 scripts/smoke.mjs 编排，接在 Slice 5 之后）：PublicTraceCode 与消费者全链。
 *
 * 前置（Slice 5 已完成）：零售企业在接受 T2 / T3 之后、终端销售之前激活了 B2 / B3 的公开追溯码，随后 B2 / B3 售罄关闭。
 * 本用例：
 *   1. 零售企业（当前责任组织）在已关闭的 B2 / B3 详情读取公开追溯码与消费者入口（只读，不产生任何写请求）；
 *   2. 全新、未登录的浏览器上下文扫码打开 B2：B0 → B1 → B2 谱系、CLOSED / NORMAL、来源 / 加工 / 仓储 / 运输 / 到达 / 销售事实、
 *      无伪造的 SPLIT → PACK / PROCESS、温度数据不足、声明可见；
 *   3. 通过查询框输入 B3 公开码：B0 → B1 → B3，B2 不出现；
 *   4. 匿名探测：batchId、traceBatchNo 及其 26 位后缀、随机码一律 404；企业端当前码接口匿名 401；
 *   5. 消费者上下文的全部 API 请求都是匿名 GET 公开查询，不携带 Cookie。
 * 禁止 page.route 或任何接口替身。证据只输出方法、路径、状态码与请求编号。
 */

interface Slice6Expected {
  b2Id: number
  b3Id: number
  b2TraceBatchNo: string
  b3TraceBatchNo: string
  forbidden: string[]
}

const expected: Slice6Expected = JSON.parse(process.env.SLICE6_EXPECTED || '{}')
const retailerAccount = { username: process.env.SLICE6_RETAILER_USERNAME || '', password: process.env.SLICE6_RETAILER_PASSWORD || '' }

interface Evidence {
  actor: string
  method: string
  path: string
  status: number
  requestId: string
  cookie: boolean
}

const ALLOWED_KEYS: Record<string, string[]> = {
  $: ['data', 'meta'],
  '$.meta': ['requestId', 'timestamp'],
  '$.data': ['publicTraceId', 'product', 'batch', 'lineage', 'timeline', 'temperatureSummary', 'flowStatus', 'riskStatus', 'recallNotice', 'queriedAt', 'disclosure'],
  '$.data.product': ['name', 'category', 'specification'],
  '$.data.batch': ['publicBatchNo', 'originType', 'maskedOrigin', 'productionDate'],
  '$.data.lineage': ['nodes', 'edges'],
  '$.data.lineage.nodes[]': ['nodeKey', 'generation', 'role', 'productName'],
  '$.data.lineage.edges[]': ['fromNodeKey', 'toNodeKey', 'operationType', 'occurredAt'],
  '$.data.timeline[]': ['eventType', 'event', 'occurredAt', 'dataSourceLabel', 'nodeKey'],
  '$.data.temperatureSummary': ['result', 'ruleNote']
}

function nonWhitelistedKeys(node: unknown, path = '$', out: string[] = []): string[] {
  if (Array.isArray(node)) {
    node.forEach((item) => nonWhitelistedKeys(item, `${path}[]`, out))
    return out
  }
  if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      if (!(ALLOWED_KEYS[path] ?? []).includes(key)) out.push(`${path}.${key}`)
      else nonWhitelistedKeys(value, `${path}.${key}`, out)
    }
  }
  return out
}

function recordApi(page: Page, actor: string, evidence: Evidence[]) {
  page.on('response', (response: Response) => {
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    const request: Request = response.request()
    evidence.push({
      actor,
      method: request.method(),
      path: url.pathname,
      status: response.status(),
      requestId: response.headers()['x-request-id'] || '',
      cookie: Boolean(request.headers()['cookie'])
    })
  })
}

function waitForPublicTrace(page: Page, code: string) {
  return page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/public/v1/public/traces/${code}`)
}

async function loginRetailer(browser: Browser, evidence: Evidence[]) {
  expect(retailerAccount.username).not.toBe('')
  const context = await browser.newContext()
  const page = await context.newPage()
  recordApi(page, 'RETAILER', evidence)
  await page.goto('/login')
  await page.locator('#login-username').fill(retailerAccount.username)
  await page.locator('#login-password').fill(retailerAccount.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/app$/)
  return { context, page }
}

async function readCode(page: Page, batchId: number) {
  await page.goto(`/app/batches/${batchId}`)
  await expect(page.getByTestId('detail-flow-status')).toHaveText('已关闭')
  await expect(page.getByTestId('public-code-status')).toHaveAttribute('data-status', 'ACTIVE')
  const code = (await page.getByTestId('public-code-value').textContent())?.trim() ?? ''
  expect(code).toMatch(/^[A-Z2-7]{26}$/)
  await expect(page.getByTestId('public-code-link')).toHaveAttribute('href', `/trace/${code}`)
  // 关闭后已激活码继续可查询；关闭后不再提供首次激活
  await expect(page.getByTestId('public-code-activate')).toHaveCount(0)
  return code
}

async function timelineOf(page: Page) {
  return page.getByTestId('public-timeline-item').evaluateAll((els) =>
    els.map((el) => `${el.getAttribute('data-node-key')}:${el.getAttribute('data-event-type')}`))
}

/** 按谱系节点归组的事件多重集（事实集合）；时间先后另由投影返回的 occurredAt 数值显式比较。 */
function byNode(timeline: string[]) {
  const groups: Record<string, string[]> = {}
  for (const item of timeline) {
    const [node, type] = item.split(':')
    ;(groups[node] ??= []).push(type)
  }
  for (const node of Object.keys(groups)) groups[node].sort()
  return groups
}

interface ProjectedItem { eventType: string; nodeKey: string; occurredAt: string }

/** 从投影 JSON 取出扫码批次（TARGET 节点）某类事件的业务时间（毫秒），保持投影顺序。 */
function targetTimes(json: { data: { timeline: ProjectedItem[]; lineage: { nodes: Array<{ nodeKey: string; role: string }> } } }, eventType: string) {
  const target = json.data.lineage.nodes.find((n) => n.role === 'TARGET')!.nodeKey
  return json.data.timeline.filter((i) => i.nodeKey === target && i.eventType === eventType).map((i) => Date.parse(i.occurredAt))
}

test('Slice 6: anonymous consumer scans the B2 / B3 public trace codes after sell-out and sees only their own upstream chain', async ({ browser }) => {
  expect(process.env.REAL_SMOKE).toBe('true')
  expect(expected.b2Id).toBeGreaterThan(0)
  const evidence: Evidence[] = []
  const chronology: string[] = []

  // ---------------------------------------------------------------- 零售企业：已关闭批次仍可读取公开码（只读）
  const retailer = await loginRetailer(browser, evidence)
  const b2Code = await readCode(retailer.page, expected.b2Id)
  const b3Code = await readCode(retailer.page, expected.b3Id)
  expect(b2Code).not.toBe(b3Code)
  await retailer.context.close()

  // ---------------------------------------------------------------- 匿名消费者：全新上下文，无登录、无 Cookie
  const consumerContext = await browser.newContext()
  expect(await consumerContext.cookies()).toHaveLength(0)
  const consumer = await consumerContext.newPage()
  recordApi(consumer, 'CONSUMER', evidence)

  // B2：扫码直达
  const b2Response = waitForPublicTrace(consumer, b2Code)
  await consumer.goto(`/trace/${b2Code}`)
  const b2Http = await b2Response
  expect(b2Http.status()).toBe(200)
  const b2Json = await b2Http.json()
  expect(nonWhitelistedKeys(b2Json)).toEqual([])

  await expect(consumer.getByTestId('public-trace-id')).toHaveText(b2Code)
  await expect(consumer.getByTestId('public-flow-status')).toHaveText('已关闭')
  await expect(consumer.getByTestId('public-risk-status')).toHaveText('正常')
  await expect(consumer.locator('.recall-alert-card')).toHaveCount(0)
  await expect(consumer.getByTestId('lineage-node')).toHaveCount(3)
  expect(await consumer.getByTestId('lineage-node').evaluateAll((els) => els.map((el) => el.getAttribute('data-role'))))
    .toEqual(['ORIGIN', 'INTERMEDIATE', 'TARGET'])
  expect(await consumer.locator('[data-testid="lineage-node"] .lineage-node-label').allTextContents())
    .toEqual(['来源批次', '加工批次', '本批次'])
  expect(await consumer.getByTestId('lineage-edge').evaluateAll((els) => els.map((el) => el.getAttribute('data-operation-type'))))
    .toEqual(['PROCESS', 'SPLIT'])

  const b2Timeline = await timelineOf(consumer)
  expect(byNode(b2Timeline)).toEqual({
    N1: ['ARRIVAL', 'SOURCE', 'TRANSPORT'],
    N2: ['PROCESS'],
    N3: ['ARRIVAL', 'SALE', 'SALE', 'TRANSPORT', 'WAREHOUSE_IN', 'WAREHOUSE_OUT']
  })
  expect(b2Timeline.filter((t) => t.endsWith(':PACK'))).toEqual([])
  expect(b2Timeline.filter((t) => t.endsWith(':PROCESS'))).toHaveLength(1)
  const occurred = (b2Json.data.timeline as Array<{ occurredAt: string }>).map((i) => Date.parse(i.occurredAt))
  expect([...occurred].sort((a, b) => a - b)).toEqual(occurred)
  // 业务时间先后（比较投影返回的 occurredAt 数值，而不是渲染位置）：S1 发运 ≤ S1 到达 ≤ B2 第一笔销售 ≤ 第二笔销售
  const [b2Transport] = targetTimes(b2Json, 'TRANSPORT')
  const [b2Arrival] = targetTimes(b2Json, 'ARRIVAL')
  const b2Sales = targetTimes(b2Json, 'SALE')
  expect(b2Sales).toHaveLength(2)
  expect(b2Transport).toBeLessThanOrEqual(b2Arrival)
  expect(b2Arrival).toBeLessThanOrEqual(b2Sales[0])
  expect(b2Sales[0]).toBeLessThanOrEqual(b2Sales[1])
  expect(b2Timeline.slice(-4)).toEqual(['N3:TRANSPORT', 'N3:ARRIVAL', 'N3:SALE', 'N3:SALE'])

  await expect(consumer.locator('.temp-summary-card')).toContainText('暂无实时时序采集')
  // PB2：S1 在途已登记温度，公开页面仍不展示任何测量明细，说明文字不再宣称“暂无记录”
  await expect(consumer.locator('.temp-summary-card')).toContainText('公开页面不展示冷链温度测量明细')
  await expect(consumer.locator('.temp-summary-card')).not.toContainText('暂无有效温控监测记录')
  await expect(consumer.locator('.trace-disclosure-card')).toContainText('不作为货物物理真实性或防伪验证凭证')
  await expect(consumer.locator('.footer-disclosure')).toContainText('教学实训推演')
  const b2Text = await consumer.locator('body').innerText()
  for (const value of ['全程温控正常', '证书', expected.b2TraceBatchNo, expected.b3TraceBatchNo, b3Code, ...expected.forbidden]) {
    expect(b2Text, `B2 页面出现 ${value}`).not.toContain(value)
    expect(JSON.stringify(b2Json), `B2 响应出现 ${value}`).not.toContain(value)
  }

  // B3：通过查询框输入（小写输入自动规范化为大写）
  await consumer.goto('/trace')
  const b3Response = waitForPublicTrace(consumer, b3Code)
  await consumer.locator('#trace-code-input').fill(b3Code.toLowerCase())
  await consumer.locator('#trace-code-input').press('Enter')
  const b3Http = await b3Response
  expect(b3Http.status()).toBe(200)
  const b3Json = await b3Http.json()
  expect(nonWhitelistedKeys(b3Json)).toEqual([])
  await expect(consumer).toHaveURL(new RegExp(`/trace/${b3Code}$`))
  await expect(consumer.getByTestId('public-flow-status')).toHaveText('已关闭')
  await expect(consumer.getByTestId('lineage-node')).toHaveCount(3)
  const b3Timeline = await timelineOf(consumer)
  expect(byNode(b3Timeline)).toEqual({
    N1: ['ARRIVAL', 'SOURCE', 'TRANSPORT'],
    N2: ['PROCESS'],
    N3: ['ARRIVAL', 'SALE', 'TRANSPORT', 'WAREHOUSE_IN', 'WAREHOUSE_OUT']
  })
  const b3Occurred = (b3Json.data.timeline as Array<{ occurredAt: string }>).map((i) => Date.parse(i.occurredAt))
  expect([...b3Occurred].sort((a, b) => a - b)).toEqual(b3Occurred)
  // S1 到达 ≤ B3 销售（同一运输任务 S1 的 B3 事实）
  const [b3Transport] = targetTimes(b3Json, 'TRANSPORT')
  const [b3Arrival] = targetTimes(b3Json, 'ARRIVAL')
  const [b3Sale] = targetTimes(b3Json, 'SALE')
  expect(b3Transport).toBeLessThanOrEqual(b3Arrival)
  expect(b3Arrival).toBeLessThanOrEqual(b3Sale)
  expect(b3Timeline.slice(-3)).toEqual(['N3:TRANSPORT', 'N3:ARRIVAL', 'N3:SALE'])
  chronology.push(
    `B2: TRANSPORT ${new Date(b2Transport).toISOString()} <= ARRIVAL ${new Date(b2Arrival).toISOString()} <= SALE ${new Date(b2Sales[0]).toISOString()} <= SALE ${new Date(b2Sales[1]).toISOString()}`,
    `B3: TRANSPORT ${new Date(b3Transport).toISOString()} <= ARRIVAL ${new Date(b3Arrival).toISOString()} <= SALE ${new Date(b3Sale).toISOString()}`
  )
  const b3Text = await consumer.locator('body').innerText()
  for (const value of [b2Code, expected.b2TraceBatchNo, expected.b3TraceBatchNo, ...expected.forbidden]) {
    expect(b3Text, `B3 页面出现 ${value}`).not.toContain(value)
    expect(JSON.stringify(b3Json), `B3 响应出现 ${value}`).not.toContain(value)
  }

  // ---------------------------------------------------------------- 匿名探测：内部标识不能作为公开入口
  const probes: Array<{ name: string; status: number; code?: string }> = []
  const probe = async (name: string, path: string) => {
    const response = await consumer.request.get(path)
    const json = await response.json().catch(() => ({}))
    probes.push({ name, status: response.status(), code: json.code })
  }
  const suffix26 = expected.b2TraceBatchNo.replace(/^TB-/, '')
  await probe('batchId', `/api/public/v1/public/traces/${expected.b2Id}`)
  await probe('traceBatchNo', `/api/public/v1/public/traces/${encodeURIComponent(expected.b2TraceBatchNo)}`)
  await probe('traceBatchNo 26-char suffix', `/api/public/v1/public/traces/${suffix26}`)
  await probe('random valid-format code', '/api/public/v1/public/traces/AAAAAAAAAAAAAAAAAAAAAAAAAA')
  await probe('lowercase public code', `/api/public/v1/public/traces/${b2Code.toLowerCase()}`)
  await probe('enterprise current-code API', `/api/v1/batches/${expected.b2Id}/public-trace-code`)
  expect(probes.map((p) => `${p.name} ${p.status} ${p.code ?? ''}`)).toEqual([
    'batchId 404 PUBLIC_TRACE_NOT_FOUND',
    'traceBatchNo 404 PUBLIC_TRACE_NOT_FOUND',
    'traceBatchNo 26-char suffix 404 PUBLIC_TRACE_NOT_FOUND',
    'random valid-format code 404 PUBLIC_TRACE_NOT_FOUND',
    'lowercase public code 404 PUBLIC_TRACE_NOT_FOUND',
    'enterprise current-code API 401 AUTH_REQUIRED'
  ])
  await consumer.goto(`/trace/${suffix26}`)
  await expect(consumer.locator('.neutral-not-found-card')).toBeVisible()

  // ---------------------------------------------------------------- 证据：零售只读、消费者只做匿名公开 GET
  const retailerWrites = evidence.filter((e) => e.actor === 'RETAILER' && e.method !== 'GET' && !e.path.startsWith('/api/v1/auth/'))
  expect(retailerWrites).toEqual([])
  const consumerCalls = evidence.filter((e) => e.actor === 'CONSUMER')
  expect(consumerCalls.length).toBeGreaterThanOrEqual(3)
  for (const e of consumerCalls) {
    expect(`${e.method} ${e.path}`).toMatch(/^GET \/api\/public\/v1\/public\/traces\/[^/]+$/)
    expect(e.cookie, `${e.path} 携带了 Cookie`).toBe(false)
    expect(e.requestId).not.toBe('')
  }
  expect((await consumerContext.cookies()).filter((c) => c.name === 'TRACESESSION')).toEqual([])

  console.log('[slice6-smoke] API 证据（actor method path status requestId cookie）:')
  for (const e of evidence) console.log(`  ${e.actor} ${e.method} ${e.path} ${e.status} ${e.requestId} ${e.cookie}`)
  console.log('[slice6-smoke] 匿名探测（name status code）:')
  for (const p of probes) console.log(`  ${p.name} ${p.status} ${p.code ?? ''}`)
  console.log(`[slice6-smoke] B2 公开时间线（节点:事件，按业务时间）: ${b2Timeline.join(' → ')}`)
  for (const line of chronology) console.log(`[slice6-smoke] 业务时间先后 ${line}`)
  console.log(`[slice6-smoke] B3 公开时间线（节点:事件，按业务时间）: ${b3Timeline.join(' → ')}`)
  console.log(`[slice6-smoke] SLICE6_RESULT ${JSON.stringify({ b2Timeline: b2Timeline.length, b3Timeline: b3Timeline.length, lineageNodes: 3 })}`)

  await consumerContext.close()
})
