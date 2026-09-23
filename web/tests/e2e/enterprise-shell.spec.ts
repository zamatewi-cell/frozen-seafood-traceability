import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * 企业端外壳的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的全链路验收见 real-smoke.spec.ts（不使用任何拦截）。
 */

const user = {
  userId: 7,
  username: 'processor_op',
  displayName: '加工操作员',
  orgId: 30,
  orgNo: 'ORG_PROC_01',
  orgName: '东海水产加工有限公司',
  orgType: 'PROCESSOR',
  roles: ['OPERATOR'],
  scopes: ['ORG_ONLY']
}

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body)
  })
}

const meta = { requestId: 'req-e2e', timestamp: '2026-09-21T08:00:00Z' }
const emptyPage = { ...meta, page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } }

async function installFakeAuthBackend(page: Page, currentUser: typeof user = user) {
  const state = { loggedIn: false, meCalls: 0, csrfHeaders: [] as (string | undefined)[] }
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, 200, { data: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'e2e-csrf' }, meta }))
  await page.route('**/api/v1/me', (route) => {
    state.meCalls += 1
    return state.loggedIn
      ? json(route, 200, { data: currentUser, meta })
      : json(route, 401, { status: 401, code: 'AUTH_REQUIRED', title: '需要登录' })
  })
  await page.route('**/api/v1/auth/login', (route) => {
    state.csrfHeaders.push(route.request().headers()['x-csrf-token'])
    state.loggedIn = true
    return json(route, 200, { data: currentUser, meta })
  })
  await page.route('**/api/v1/auth/logout', (route) => {
    state.csrfHeaders.push(route.request().headers()['x-csrf-token'])
    state.loggedIn = false
    return route.fulfill({ status: 204 })
  })
  return state
}

test.describe('Enterprise shell', () => {
  test('anonymous /app is redirected to /login and login lands on the workbench', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)

    await page.goto('/app')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    await expect(page.getByRole('heading', { name: '企业用户登录' })).toBeVisible()

    await page.locator('#login-username').fill('processor_op')
    await page.locator('#login-password').fill('not-a-real-password')
    await page.getByRole('button', { name: '登录' }).click()

    await expect(page).toHaveURL(/\/app$/)
    await expect(page.getByTestId('workbench-session')).toContainText('东海水产加工有限公司')

    await page.reload()
    await expect(page.getByTestId('workbench-session')).toContainText('加工操作员')

    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login/)
    await page.goto('/app')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(backend.csrfHeaders).toEqual(['e2e-csrf', 'e2e-csrf'])
  })

  test('consumer trace route stays anonymous and never checks the enterprise session', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)
    await page.route('**/api/public/v1/public/traces/*', (route) =>
      json(route, 404, { status: 404, code: 'PUBLIC_TRACE_NOT_FOUND', title: '未找到' }))

    await page.goto('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    await expect(page).toHaveURL(/\/trace\/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC$/)
    await expect(page.getByText('未找到该追溯码').first()).toBeVisible()
    expect(backend.meCalls).toBe(0)
  })

  test('batch list filters through the URL and opens a read-only detail', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)
    backend.loggedIn = true
    const batch = {
      id: 12, orgId: 30, productId: 5, traceBatchNo: 'TB-AAAAAAAAAAAAAAAAAAAAAAAAAA', externalBatchNo: 'SUP-2026-001',
      batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
      flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 1
    }
    const listQueries: string[] = []
    await page.route('**/api/v1/batches?*', (route) => {
      listQueries.push(new URL(route.request().url()).search)
      return json(route, 200, { data: [batch], meta: { ...meta, page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } } })
    })
    await page.route('**/api/v1/batches/12', (route) => json(route, 200, { data: batch, meta }))
    await page.route('**/api/v1/batches/12/events', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/12/sales', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/12/public-trace-code', (route) => json(route, 404, { status: 404, code: 'PUBLIC_TRACE_CODE_NOT_FOUND', title: '公开追溯码未激活', detail: '该批次尚未激活公开追溯码' }))
    await page.route('**/api/v1/transfers?*', (route) => json(route, 200, { data: [], meta: { ...meta, page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } } }))
    await page.route('**/api/v1/batch-operations?*', (route) => json(route, 200, { data: [], meta: emptyPage }))
    await page.route('**/api/v1/products/5', (route) => json(route, 200, {
      data: { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 },
      meta
    }))
    await page.route('**/api/v1/organizations/30', (route) => json(route, 200, {
      data: { id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' },
      meta
    }))

    await page.goto('/app/batches')
    await expect(page.locator('.batch-row')).toHaveCount(1)
    await expect(page.locator('.batch-row')).toContainText('冷冻大黄鱼')

    await page.getByTestId('filter-flow-status').selectOption('ACTIVE')
    await expect(page).toHaveURL(/flowStatus=ACTIVE/)
    await page.getByTestId('filter-risk-status').selectOption('NORMAL')
    await expect(page).toHaveURL(/riskStatus=NORMAL/)
    await expect.poll(() => listQueries.at(-1)).toContain('riskStatus=NORMAL')

    await page.locator('.batch-row').first().click()
    await expect(page).toHaveURL(/\/app\/batches\/12$/)
    await expect(page.getByTestId('detail-trace-batch-no')).toHaveText('TB-AAAAAAAAAAAAAAAAAAAAAAAAAA')
    await expect(page.getByTestId('detail-org-name')).toHaveText('东海水产加工有限公司')

    await page.getByTestId('back-to-list').click()
    await expect(page).toHaveURL(/flowStatus=ACTIVE&riskStatus=NORMAL/)
    // 加工企业不是来源组织：列表不提供新建来源批次入口
    await expect(page.getByTestId('new-source-batch')).toHaveCount(0)
  })

  test('source operator creates a draft, activates it on the detail page and sees the SOURCE event', async ({ page }) => {
    const sourceUser = { ...user, userId: 9, username: 'source_op', displayName: '来源操作员', orgId: 40, orgNo: 'ORG_SRC_01', orgName: '东海远洋捕捞有限公司', orgType: 'SOURCE' }
    const backend = await installFakeAuthBackend(page, sourceUser)
    backend.loggedIn = true
    const product = { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
    let batch: Record<string, unknown> | null = null
    let events: unknown[] = []
    const writes: { path: string; body: unknown; csrf?: string; idempotencyKey?: string }[] = []

    await page.route('**/api/v1/products?*', (route) => json(route, 200, {
      data: [product, { ...product, id: 8, publicName: '停用产品', status: 'INACTIVE' }],
      meta: { ...meta, page: { number: 1, size: 100, totalElements: 2, totalPages: 1 } }
    }))
    await page.route('**/api/v1/products/5', (route) => json(route, 200, { data: product, meta }))
    await page.route('**/api/v1/organizations/40', (route) => json(route, 200, {
      data: { id: 40, orgNo: 'ORG_SRC_01', name: '东海远洋捕捞有限公司', orgType: 'SOURCE', status: 'ACTIVE' }, meta
    }))
    await page.route('**/api/v1/batches', (route) => {
      const request = route.request()
      writes.push({ path: '/api/v1/batches', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'], idempotencyKey: request.headers()['idempotency-key'] })
      batch = {
        id: 101, orgId: 40, productId: 5, traceBatchNo: 'TB-E2ESERVERGENERATED000001', externalBatchNo: 'SRC-2026-001',
        batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
        flowStatus: 'DRAFT', riskStatus: 'NORMAL', version: 0
      }
      return json(route, 201, { data: batch, meta })
    })
    await page.route('**/api/v1/batches/101', (route) => json(route, 200, { data: batch, meta }))
    await page.route('**/api/v1/batches/101/events', (route) => json(route, 200, { data: events, meta }))
    await page.route('**/api/v1/batches/101/sales', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/101/public-trace-code', (route) => json(route, 404, { status: 404, code: 'PUBLIC_TRACE_CODE_NOT_FOUND', title: '公开追溯码未激活', detail: '该批次尚未激活公开追溯码' }))
    await page.route('**/api/v1/transfers?*', (route) => json(route, 200, { data: [], meta: { ...meta, page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } } }))
    await page.route('**/api/v1/batch-operations?*', (route) => json(route, 200, { data: [], meta: emptyPage }))
    await page.route('**/api/v1/batches/101/submit', (route) => {
      const request = route.request()
      writes.push({ path: '/api/v1/batches/101/submit', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'] })
      batch = { ...batch, flowStatus: 'ACTIVE', version: 1 }
      events = [{
        id: 900, batchId: 101, orgId: 40, eventType: 'SOURCE', occurredAt: '2026-09-21T08:30:00.000Z', recordedAt: '2026-09-21T08:30:00.000Z',
        operatorId: 9, dataSource: 'MANUAL', status: 'SUBMITTED', summary: '来源批次激活：东海舟山渔场',
        detailsJson: { sourceObjectType: 'BATCH', sourceObjectId: 101, traceBatchNo: 'TB-E2ESERVERGENERATED000001', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', quantity: '1000.000', unitCode: 'kg' }
      }]
      return json(route, 200, { data: batch, meta })
    })

    await page.goto('/app')
    await page.getByTestId('entry-new-source-batch').click()
    await expect(page).toHaveURL(/\/app\/batches\/new$/)
    await expect(page.getByTestId('field-product').locator('option')).toHaveCount(2)
    await page.getByTestId('field-product').selectOption('5')
    await page.getByTestId('field-external-batch-no').fill('SRC-2026-001')
    await page.getByTestId('field-quantity').fill('1000')
    await expect(page.getByTestId('field-unit')).toHaveText('kg')
    await page.getByTestId('field-origin-type').selectOption('DOMESTIC_CAPTURE')
    await page.getByTestId('field-origin-text').fill('东海舟山渔场')
    await page.getByTestId('save-draft').dblclick()

    await expect(page).toHaveURL(/\/app\/batches\/101$/)
    await expect(page.getByTestId('detail-flow-status')).toHaveText('草稿')
    await expect(page.getByTestId('detail-trace-batch-no')).toHaveText('TB-E2ESERVERGENERATED000001')
    await expect(page.getByTestId('events-empty')).toBeVisible()

    await page.getByTestId('submit-activation').click()
    await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
    await expect(page.getByTestId('detail-risk-status')).toHaveText('正常')
    await expect(page.getByTestId('trace-event')).toHaveCount(1)
    await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'SOURCE')

    const creates = writes.filter((w) => w.path === '/api/v1/batches')
    expect(creates).toHaveLength(1)
    expect(creates[0].csrf).toBe('e2e-csrf')
    expect(creates[0].idempotencyKey?.length).toBeGreaterThanOrEqual(16)
    expect(Object.keys(creates[0].body as object).sort()).toEqual(['externalBatchNo', 'originText', 'originType', 'productId', 'quantity', 'unitCode'])
    const submits = writes.filter((w) => w.path === '/api/v1/batches/101/submit')
    expect(submits).toEqual([{ path: '/api/v1/batches/101/submit', body: { version: 0 }, csrf: 'e2e-csrf' }])
  })

  test('processor processes a batch through the wizard: full input, exact balance, lands on the activated output', async ({ page }) => {
    const backend = await installFakeAuthBackend(page)
    backend.loggedIn = true
    const product = { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
    const input: Record<string, unknown> = {
      id: 101, orgId: 30, productId: 5, traceBatchNo: 'TB-E2E-B0', batchType: 'SOURCE', quantity: 1000, remainingQuantity: 1000,
      unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 3
    }
    const output: Record<string, unknown> = {
      id: 201, orgId: 30, productId: 5, traceBatchNo: 'TB-E2E-B1', batchType: 'PROCESSING', quantity: 960, remainingQuantity: 960,
      unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', flowStatus: 'DRAFT', riskStatus: 'NORMAL', version: 0, producedByOperationId: 701
    }
    const writes: { method: string; path: string; body: unknown; csrf?: string; idempotencyKey?: string }[] = []
    let operation: Record<string, unknown> | null = null
    let outputEvents: unknown[] = []

    await page.route('**/api/v1/products?*', (route) => json(route, 200, { data: [product], meta: { ...meta, page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } } }))
    await page.route('**/api/v1/products/5', (route) => json(route, 200, { data: product, meta }))
    await page.route('**/api/v1/organizations/30', (route) => json(route, 200, {
      data: { id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }, meta
    }))
    await page.route('**/api/v1/transfers?*', (route) => json(route, 200, { data: [], meta: emptyPage }))
    await page.route('**/api/v1/batches/101', (route) => json(route, 200, { data: input, meta }))
    await page.route('**/api/v1/batches/101/events', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/101/sales', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/101/public-trace-code', (route) => json(route, 404, { status: 404, code: 'PUBLIC_TRACE_CODE_NOT_FOUND', title: '公开追溯码未激活', detail: '该批次尚未激活公开追溯码' }))
    await page.route('**/api/v1/batches/201', (route) => json(route, 200, { data: output, meta }))
    await page.route('**/api/v1/batches/201/events', (route) => json(route, 200, { data: outputEvents, meta }))
    await page.route('**/api/v1/batches/201/sales', (route) => json(route, 200, { data: [], meta }))
    await page.route('**/api/v1/batches/201/public-trace-code', (route) => json(route, 404, { status: 404, code: 'PUBLIC_TRACE_CODE_NOT_FOUND', title: '公开追溯码未激活', detail: '该批次尚未激活公开追溯码' }))
    await page.route('**/api/v1/batch-operations?*', (route) => {
      const batchId = Number(new URL(route.request().url()).searchParams.get('batchId'))
      const related = operation && (batchId === 101 || batchId === 201) ? [operation] : []
      return json(route, 200, { data: related, meta: { ...meta, page: { number: 1, size: 20, totalElements: related.length, totalPages: related.length ? 1 : 0 } } })
    })
    const items = () => [
      { id: 1, operationId: 701, role: 'INPUT', batchId: 101, quantity: 1000, unitCode: 'kg', normalizedQuantity: 1000, traceBatchNo: 'TB-E2E-B0', batchFlowStatus: input.flowStatus },
      { id: 2, operationId: 701, role: 'OUTPUT', batchId: 201, quantity: 960, unitCode: 'kg', normalizedQuantity: 960, traceBatchNo: 'TB-E2E-B1', batchFlowStatus: output.flowStatus },
      { id: 3, operationId: 701, role: 'LOSS', quantity: 30, unitCode: 'kg', normalizedQuantity: 30 },
      { id: 4, operationId: 701, role: 'SAMPLE', quantity: 10, unitCode: 'kg', normalizedQuantity: 10 }
    ]
    await page.route('**/api/v1/batch-operations', (route) => {
      const request = route.request()
      writes.push({ method: request.method(), path: '/api/v1/batch-operations', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'], idempotencyKey: request.headers()['idempotency-key'] })
      operation = { id: 701, orgId: 30, operationNo: 'OP-E2E-701', operationType: 'PROCESS', occurredAt: '2026-09-22T08:00:00.000Z', recordedAt: '2026-09-22T08:00:00.000Z',
        status: 'DRAFT', balanced: true, version: 0, createdAt: '2026-09-22T08:00:00.000Z', updatedAt: '2026-09-22T08:00:00.000Z', items: items(), relations: [] }
      return json(route, 201, { data: operation, meta })
    })
    await page.route('**/api/v1/batch-operations/701/submit', (route) => {
      const request = route.request()
      writes.push({ method: request.method(), path: '/api/v1/batch-operations/701/submit', body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'], idempotencyKey: request.headers()['idempotency-key'] })
      Object.assign(input, { flowStatus: 'CLOSED', remainingQuantity: 0, consumedByOperationId: 701 })
      Object.assign(output, { flowStatus: 'ACTIVE' })
      outputEvents = [{
        id: 950, batchId: 201, orgId: 30, eventType: 'PROCESS', occurredAt: '2026-09-22T08:00:00.000Z', recordedAt: '2026-09-22T08:00:01.000Z',
        dataSource: 'MANUAL', status: 'SUBMITTED', summary: '加工产出 960 kg（投入 1000 kg，损耗 30 kg，留样 10 kg）',
        detailsJson: { sourceObjectType: 'BATCH_OPERATION', sourceObjectId: 701, operationNo: 'OP-E2E-701', lossQuantity: '30.000', wasteQuantity: '0', sampleQuantity: '10.000' }
      }]
      operation = { ...operation, status: 'SUBMITTED', version: 1, items: items(), relations: [{ id: 1, operationId: 701, parentBatchId: 101, childBatchId: 201, relationType: 'TRANSFORM', createdAt: '2026-09-22T08:00:01Z' }] }
      return json(route, 200, { data: operation, meta })
    })

    await page.goto('/app/batches/101')
    await expect(page.getByTestId('detail-remaining-quantity')).toContainText('1,000 kg')
    await page.getByTestId('start-process').click()
    await expect(page).toHaveURL(/\/app\/batches\/101\/operations\/new\?type=PROCESS$/)
    await expect(page.getByTestId('wizard-input-quantity')).toContainText('1,000 kg')

    await page.getByTestId('output-quantity-0').fill('960')
    await page.getByTestId('loss-quantity').fill('30')
    await expect(page.getByTestId('balance-indicator')).toHaveAttribute('data-balanced', 'false')
    await expect(page.getByTestId('wizard-submit')).toBeDisabled()
    await page.getByTestId('sample-quantity').fill('10')
    await expect(page.getByTestId('balance-indicator')).toHaveAttribute('data-balanced', 'true')
    await page.getByTestId('wizard-submit').dblclick()

    await expect(page).toHaveURL(/\/app\/batches\/201$/)
    await expect(page.getByTestId('batch-flash')).toContainText('PROCESS')
    await expect(page.getByTestId('detail-flow-status')).toHaveText('可流转')
    await expect(page.getByTestId('lineage-produced-by')).toContainText('OP-E2E-701')
    await expect(page.getByTestId('trace-event')).toHaveAttribute('data-event-type', 'PROCESS')

    expect(writes.map((w) => `${w.method} ${w.path}`)).toEqual(['POST /api/v1/batch-operations', 'POST /api/v1/batch-operations/701/submit'])
    for (const w of writes) {
      expect(w.csrf).toBe('e2e-csrf')
      expect(w.idempotencyKey?.length).toBeGreaterThanOrEqual(16)
    }
    const created = writes[0].body as { operationType: string; items: Record<string, unknown>[] }
    expect(created.operationType).toBe('PROCESS')
    expect(created.items).toEqual([
      { role: 'INPUT', batchId: 101, quantity: 1000, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 960, unitCode: 'kg' },
      { role: 'LOSS', quantity: 30, unitCode: 'kg' },
      { role: 'SAMPLE', quantity: 10, unitCode: 'kg' }
    ])
    expect(writes[1].body).toEqual({ version: 0 })
  })
})
