import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, type Router } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 1 来源建批：入口权限、真实产品 API、表单校验、保存草稿 / 保存并激活、
 * CSRF 与 Idempotency-Key、防重复提交、403/409/422/网络错误、详情提交激活与 SOURCE 事件展示。
 * 仅在单元测试中使用 fetch 替身；真实冒烟不拦截任何请求。
 */

const originalFetch = globalThis.fetch

const sourceOperator = {
  userId: 9, username: 'source_op', displayName: '来源操作员', orgId: 40, orgNo: 'ORG_SRC_01',
  orgName: '东海远洋捕捞有限公司', orgType: 'SOURCE', roles: ['OPERATOR'], scopes: ['ORG_ONLY']
}
const processorOperator = { ...sourceOperator, userId: 7, orgId: 30, orgType: 'PROCESSOR', orgName: '东海水产加工有限公司' }
const sourceAuditor = { ...sourceOperator, userId: 10, roles: ['AUDITOR'] }

const activeFish = { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
const activeShrimp = { id: 6, productCode: 'P-SHRIMP', publicName: '冷冻南美白虾', category: 'CRUSTACEAN', specification: '1kg/袋', sourceType: 'IMPORT', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
const inactiveProduct = { ...activeFish, id: 7, productCode: 'P-OLD', publicName: '停用产品', status: 'INACTIVE' }
const organization = { id: 40, orgNo: 'ORG_SRC_01', name: '东海远洋捕捞有限公司', orgType: 'SOURCE', status: 'ACTIVE' }

const draftBatch = {
  id: 101, orgId: 40, productId: 5, traceBatchNo: 'TB-SERVERGENERATED0000000001', externalBatchNo: 'SRC-2026-001',
  batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
  flowStatus: 'DRAFT', riskStatus: 'NORMAL', version: 0
}
const activeBatch = { ...draftBatch, flowStatus: 'ACTIVE', version: 1 }
const sourceEvent = {
  id: 900, batchId: 101, orgId: 40, eventType: 'SOURCE', occurredAt: '2026-09-21T08:30:00.000Z', recordedAt: '2026-09-21T08:30:00.000Z',
  operatorId: 9, dataSource: 'MANUAL', status: 'SUBMITTED', summary: '来源批次激活：东海舟山渔场',
  detailsJson: {
    sourceObjectType: 'BATCH', sourceObjectId: 101, traceBatchNo: 'TB-SERVERGENERATED0000000001', productId: 5,
    originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', quantity: '1000.000', unitCode: 'kg', occurredAtBasis: 'BATCH_ACTIVATION'
  }
}

type Routes = Record<string, (call: RecordedCall) => FakeResponse | Promise<FakeResponse>>

/** 可变的服务端状态：批次与事件随写请求变化，便于验证刷新后展示的是服务端数据。 */
function backend(user: unknown, overrides: Routes = {}) {
  const state = { batch: { ...draftBatch } as Record<string, unknown>, events: [] as unknown[] }
  const fake = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
    'GET /api/v1/batches': () => ({ status: 200, body: envelope([state.batch], { number: 1, size: 20, totalElements: 1, totalPages: 1 }) }),
    'GET /api/v1/products': () => ({ status: 200, body: envelope([activeFish, activeShrimp, inactiveProduct], { number: 1, size: 100, totalElements: 3, totalPages: 1 }) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope(activeFish) }),
    'GET /api/v1/organizations/40': () => ({ status: 200, body: envelope(organization) }),
    'POST /api/v1/batches': () => ({ status: 201, body: envelope(state.batch) }),
    'GET /api/v1/batches/101': () => ({ status: 200, body: envelope(state.batch) }),
    'GET /api/v1/batches/101/events': () => ({ status: 200, body: envelope(state.events) }),
    'POST /api/v1/batches/101/submit': () => {
      state.batch = { ...activeBatch }
      state.events = [sourceEvent]
      return { status: 200, body: envelope(state.batch) }
    },
    ...overrides
  })
  return { ...fake, state }
}

let wrapper: VueWrapper | null = null

async function mountAt(path: string): Promise<{ router: Router; view: VueWrapper }> {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { router, view: wrapper }
}

async function fillValidForm(view: VueWrapper) {
  await view.get('[data-testid="field-product"]').setValue('5')
  await view.get('[data-testid="field-external-batch-no"]').setValue('SRC-2026-001')
  await view.get('[data-testid="field-quantity"]').setValue('1000')
  await view.get('[data-testid="field-origin-type"]').setValue('DOMESTIC_CAPTURE')
  await view.get('[data-testid="field-origin-text"]').setValue('东海舟山渔场')
}

function writes(calls: RecordedCall[], path: string) {
  return calls.filter((c) => c.method === 'POST' && c.path === path)
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

describe('source batch entry permissions', () => {
  it('shows the create entry on the workbench and batch list for a SOURCE operator', async () => {
    backend(sourceOperator)
    const { view, router } = await mountAt('/app')
    expect(view.find('[data-testid="entry-new-source-batch"]').exists()).toBe(true)

    await router.push('/app/batches')
    await flushPromises()
    expect(view.find('[data-testid="new-source-batch"]').exists()).toBe(true)
  })

  it.each([
    ['non-SOURCE organization operator', processorOperator],
    ['SOURCE organization non-operator', sourceAuditor]
  ])('hides every executable entry for a %s and blocks the form route', async (_label, user) => {
    const { calls } = backend(user)
    const { view, router } = await mountAt('/app')
    expect(view.find('[data-testid="entry-new-source-batch"]').exists()).toBe(false)

    await router.push('/app/batches')
    await flushPromises()
    expect(view.find('[data-testid="new-source-batch"]').exists()).toBe(false)

    await router.push('/app/batches/new')
    await flushPromises()
    expect(view.find('[data-testid="source-batch-forbidden"]').exists()).toBe(true)
    expect(view.find('[data-testid="source-batch-form"]').exists()).toBe(false)
    expect(calls.some((c) => c.path === '/api/v1/products')).toBe(false)
  })
})

describe('SourceBatchCreateView', () => {
  it('loads only ACTIVE products from the real product API', async () => {
    const { calls } = backend(sourceOperator)
    const { view } = await mountAt('/app/batches/new')

    const productCall = calls.find((c) => c.path === '/api/v1/products')
    expect(productCall?.method).toBe('GET')
    expect(productCall?.search.get('status')).toBe('ACTIVE')
    const options = view.findAll('[data-testid="field-product"] option').map((o) => o.text())
    expect(options.some((t) => t.includes('冷冻大黄鱼'))).toBe(true)
    expect(options.some((t) => t.includes('冷冻南美白虾'))).toBe(true)
    expect(options.some((t) => t.includes('停用产品'))).toBe(false)
    expect(view.get('[data-testid="field-unit"]').text()).toBe('kg')
  })

  it('shows loading, empty and retryable error states for the product list', async () => {
    let fail = true
    backend(sourceOperator, {
      'GET /api/v1/products': () => fail
        ? problem(500, 'INTERNAL_ERROR', '服务暂不可用')
        : { status: 200, body: envelope([], { number: 1, size: 100, totalElements: 0, totalPages: 0 }) }
    })
    const { view } = await mountAt('/app/batches/new')
    expect(view.get('[data-testid="products-error"]').text()).toContain('服务暂不可用')
    expect(view.get('[data-testid="save-draft"]').attributes('disabled')).toBeDefined()

    fail = false
    await view.get('[data-testid="products-error"] button').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="products-empty"]').exists()).toBe(true)
  })

  it('validates required fields, quantity precision and text length before sending anything', async () => {
    const { calls } = backend(sourceOperator)
    const { view } = await mountAt('/app/batches/new')

    await view.get('[data-testid="save-draft"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="error-productId"]').text()).toContain('请选择')
    expect(view.get('[data-testid="error-quantity"]').text()).toContain('请填写')
    expect(view.find('[data-testid="error-originType"]').exists()).toBe(true)
    expect(view.find('[data-testid="error-originText"]').exists()).toBe(true)

    await fillValidForm(view)
    for (const bad of ['0', '-5', '12.3456', 'abc']) {
      await view.get('[data-testid="field-quantity"]').setValue(bad)
      await view.get('[data-testid="source-batch-form"]').trigger('submit')
      await flushPromises()
      expect(view.get('[data-testid="error-quantity"]').text()).toContain('大于 0')
    }
    expect(writes(calls, '/api/v1/batches')).toHaveLength(0)
  })

  it('saves a draft with CSRF + Idempotency-Key, sends only enterprise fields and opens the real detail', async () => {
    const { calls } = backend(sourceOperator)
    const { view, router } = await mountAt('/app/batches/new')
    await fillValidForm(view)
    await view.get('[data-testid="field-capture-date"]').setValue('2026-09-01')
    await view.get('[data-testid="field-shelf-life-days"]').setValue('365')

    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()

    const [create] = writes(calls, '/api/v1/batches')
    expect(create.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(create.headers['Idempotency-Key']).toMatch(/^.{16,128}$/)
    expect(create.body).toEqual({
      productId: 5, quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
      externalBatchNo: 'SRC-2026-001', captureDate: '2026-09-01', shelfLifeDays: 365
    })
    for (const serverField of ['batchType', 'traceBatchNo', 'orgId', 'creationOrgId', 'flowStatus', 'riskStatus', 'status', 'version']) {
      expect(create.body).not.toHaveProperty(serverField)
    }
    expect(writes(calls, '/api/v1/batches/101/submit')).toHaveLength(0)

    expect(router.currentRoute.value.fullPath).toBe('/app/batches/101')
    expect(view.get('[data-testid="batch-flash"]').text()).toContain('草稿已保存')
    expect(view.get('[data-testid="detail-trace-batch-no"]').text()).toBe('TB-SERVERGENERATED0000000001')
    expect(view.get('[data-testid="detail-flow-status"]').text()).toBe('草稿')
    expect(view.get('[data-testid="events-empty"]').text()).toContain('草稿尚未激活')
  })

  it('saves and activates, then the detail shows ACTIVE/NORMAL and the SOURCE event', async () => {
    const { calls } = backend(sourceOperator)
    const { view, router } = await mountAt('/app/batches/new')
    await fillValidForm(view)

    await view.get('[data-testid="save-and-activate"]').trigger('click')
    await flushPromises()

    expect(writes(calls, '/api/v1/batches')).toHaveLength(1)
    const [submit] = writes(calls, '/api/v1/batches/101/submit')
    expect(submit.body).toEqual({ version: 0 })
    expect(submit.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')

    expect(router.currentRoute.value.fullPath).toBe('/app/batches/101')
    expect(view.get('[data-testid="detail-flow-status"]').text()).toBe('可流转')
    expect(view.get('[data-testid="detail-risk-status"]').text()).toBe('正常')
    const eventsOnPage = view.findAll('[data-testid="trace-event"]')
    expect(eventsOnPage).toHaveLength(1)
    expect(eventsOnPage[0].attributes('data-event-type')).toBe('SOURCE')
    expect(view.find('[data-testid="submit-activation"]').exists()).toBe(false)
  })

  it('ignores repeated clicks while a save is in flight', async () => {
    let release: (value: FakeResponse) => void = () => {}
    const { calls, state } = backend(sourceOperator, {
      'POST /api/v1/batches': () => new Promise<FakeResponse>((resolve) => { release = resolve })
    })
    const { view } = await mountAt('/app/batches/new')
    await fillValidForm(view)

    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await view.get('[data-testid="save-and-activate"]').trigger('click')
    await flushPromises()
    expect(view.get('[data-testid="save-draft"]').attributes('disabled')).toBeDefined()
    expect(view.get('[data-testid="save-and-activate"]').attributes('disabled')).toBeDefined()

    release({ status: 201, body: envelope(state.batch) })
    await flushPromises()
    expect(writes(calls, '/api/v1/batches')).toHaveLength(1)
    expect(writes(calls, '/api/v1/batches/101/submit')).toHaveLength(0)
  })

  it.each([
    [403, 'ORG_TYPE_NOT_ALLOWED', '无权'],
    [409, 'IDEMPOTENCY_KEY_REUSED', '冲突'],
    [422, 'PRODUCT_NOT_ACTIVE', '业务状态不允许']
  ])('shows a %s %s error and stays on the form', async (status, code, text) => {
    backend(sourceOperator, { 'POST /api/v1/batches': () => problem(status, code, `服务端说明-${code}`) })
    const { view, router } = await mountAt('/app/batches/new')
    await fillValidForm(view)

    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()

    const message = view.get('[data-testid="source-batch-error"]').text()
    expect(message).toContain(text)
    expect(message).toContain(`服务端说明-${code}`)
    expect(message).toContain(`req-${status}`)
    expect(router.currentRoute.value.fullPath).toBe('/app/batches/new')
    expect(view.get('[data-testid="save-draft"]').attributes('disabled')).toBeUndefined()
  })

  it('maps 400 field errors onto the form', async () => {
    backend(sourceOperator, {
      'POST /api/v1/batches': () => ({
        status: 400,
        contentType: 'application/problem+json',
        body: { status: 400, code: 'INVALID_REQUEST', detail: '参数校验失败', requestId: 'req-400', fieldErrors: [{ field: 'quantity', code: 'Digits', message: '批次数量整数最多 15 位且小数最多 3 位' }] }
      })
    })
    const { view } = await mountAt('/app/batches/new')
    await fillValidForm(view)
    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="error-quantity"]').text()).toContain('小数最多 3 位')
    expect(view.get('[data-testid="source-batch-error"]').text()).toContain('提交内容不符合要求')
  })

  it('reuses the same Idempotency-Key when retrying after a network error and rotates it when the payload changes', async () => {
    let attempt = 0
    const { calls, state } = backend(sourceOperator, {
      'POST /api/v1/batches': () => {
        attempt += 1
        if (attempt <= 2) throw new TypeError('Failed to fetch')
        return { status: 201, body: envelope(state.batch) }
      }
    })
    const { view, router } = await mountAt('/app/batches/new')
    await fillValidForm(view)

    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="source-batch-error"]').text()).toContain('网络连接失败')

    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()
    await view.get('[data-testid="field-quantity"]').setValue('999')
    await view.get('[data-testid="source-batch-form"]').trigger('submit')
    await flushPromises()

    const keys = writes(calls, '/api/v1/batches').map((c) => c.headers['Idempotency-Key'])
    expect(keys).toHaveLength(3)
    expect(keys[1]).toBe(keys[0])
    expect(keys[2]).not.toBe(keys[0])
    expect(router.currentRoute.value.fullPath).toBe('/app/batches/101')
  })

  it('keeps the saved draft when activation fails and explains it on the detail page', async () => {
    backend(sourceOperator, { 'POST /api/v1/batches/101/submit': () => problem(422, 'PRODUCT_NOT_ACTIVE', '关联海产品必须处于 ACTIVE 启用状态') })
    const { view, router } = await mountAt('/app/batches/new')
    await fillValidForm(view)
    await view.get('[data-testid="save-and-activate"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/app/batches/101')
    const flash = view.get('[data-testid="batch-flash"]').text()
    expect(flash).toContain('草稿已保存')
    expect(flash).toContain('关联海产品必须处于 ACTIVE 启用状态')
    expect(view.find('[data-testid="submit-activation"]').exists()).toBe(true)
  })
})

describe('BatchDetailView source activation', () => {
  it('submits a DRAFT/NORMAL source batch with its version and refreshes to ACTIVE with exactly one SOURCE', async () => {
    const { calls } = backend(sourceOperator)
    const { view } = await mountAt('/app/batches/101')
    expect(view.get('[data-testid="detail-flow-status"]').text()).toBe('草稿')

    await view.get('[data-testid="submit-activation"]').trigger('click')
    await view.get('[data-testid="submit-activation"]').trigger('click')
    await flushPromises()

    const submits = writes(calls, '/api/v1/batches/101/submit')
    expect(submits).toHaveLength(1)
    expect(submits[0].body).toEqual({ version: 0 })
    expect(submits[0].headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    // 激活后重新读取服务端详情与事件
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/101').length).toBeGreaterThanOrEqual(2)
    expect(view.get('[data-testid="detail-flow-status"]').text()).toBe('可流转')
    expect(view.get('[data-testid="batch-flash"]').text()).toContain('SOURCE')
    expect(view.findAll('[data-testid="trace-event"]')).toHaveLength(1)
  })

  it('shows a 409 conflict, reloads the latest server state and keeps the message', async () => {
    const { state } = backend(sourceOperator, {
      'POST /api/v1/batches/101/submit': () => {
        state.batch = { ...draftBatch, version: 3 }
        return problem(409, 'VERSION_CONFLICT', '当前批次版本号为 3')
      }
    })
    const { view } = await mountAt('/app/batches/101')
    await view.get('[data-testid="submit-activation"]').trigger('click')
    await flushPromises()

    expect(view.get('[data-testid="submit-error"]').text()).toContain('当前批次版本号为 3')
    expect(view.get('[data-testid="detail-version"]').text()).toBe('3')
  })

  it.each([
    [403, 'ORG_TYPE_NOT_ALLOWED', '无权'],
    [422, 'PRODUCT_NOT_ACTIVE', '业务状态不允许']
  ])('shows a %s activation error', async (status, code, text) => {
    backend(sourceOperator, { 'POST /api/v1/batches/101/submit': () => problem(status, code, `说明-${code}`) })
    const { view } = await mountAt('/app/batches/101')
    await view.get('[data-testid="submit-activation"]').trigger('click')
    await flushPromises()
    const message = view.get('[data-testid="submit-error"]').text()
    expect(message).toContain(text)
    expect(message).toContain(`说明-${code}`)
  })

  it('does not offer activation to a non-SOURCE operator even for a draft', async () => {
    backend(processorOperator, { 'GET /api/v1/organizations/40': () => ({ status: 200, body: envelope(organization) }) })
    const { view } = await mountAt('/app/batches/101')
    expect(view.find('[data-testid="submit-activation"]').exists()).toBe(false)
  })

  it('renders the SOURCE event facts for an ACTIVE batch from the real events API', async () => {
    const { state, calls } = backend(sourceOperator)
    state.batch = { ...activeBatch }
    state.events = [sourceEvent]
    const { view } = await mountAt('/app/batches/101')

    expect(calls.some((c) => c.method === 'GET' && c.path === '/api/v1/batches/101/events')).toBe(true)
    const event = view.get('[data-testid="trace-event"]')
    expect(event.attributes('data-event-type')).toBe('SOURCE')
    expect(event.text()).toContain('来源（批次激活自动生成）')
    expect(event.text()).toContain('东海舟山渔场')
    expect(event.text()).toContain('国内捕捞')
    expect(event.text()).toContain('1,000 kg')
    expect(event.text()).toContain('TB-SERVERGENERATED0000000001')
  })

  it('shows a retryable error when the events API fails', async () => {
    let fail = true
    backend(sourceOperator, {
      'GET /api/v1/batches/101/events': () => (fail ? problem(500, 'INTERNAL_ERROR', '事件服务异常') : { status: 200, body: envelope([sourceEvent]) })
    })
    const { view } = await mountAt('/app/batches/101')
    expect(view.get('[data-testid="events-error"]').text()).toContain('事件服务异常')
    fail = false
    await view.get('[data-testid="events-error"] button').trigger('click')
    await flushPromises()
    expect(view.findAll('[data-testid="trace-event"]')).toHaveLength(1)
  })
})
