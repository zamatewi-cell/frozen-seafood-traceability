import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, type Router } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { clearSession, resetSessionForTests } from '@/stores/session'
import { envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

const originalFetch = globalThis.fetch

const batches = [
  {
    id: 12, orgId: 30, productId: 5, traceBatchNo: 'TB-AAAAAAAAAAAAAAAAAAAAAAAAAA', externalBatchNo: 'SUP-2026-001',
    batchType: 'SOURCE', quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
    productionDate: '2026-09-01', captureDate: '2026-08-30', shelfLifeDays: 365,
    flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 1,
    createdAt: '2026-09-01T08:00:00Z', updatedAt: '2026-09-02T08:00:00Z'
  },
  {
    id: 11, orgId: 30, productId: 6, traceBatchNo: 'TB-BBBBBBBBBBBBBBBBBBBBBBBBBB',
    batchType: 'PROCESSING', quantity: 12.5, unitCode: 'kg', originType: 'IMPORT', originText: '进口原料',
    flowStatus: 'CLOSED', riskStatus: 'RECALLED', version: 4
  }
]

const products: Record<number, unknown> = {
  5: { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 },
  6: { id: 6, productCode: 'P-SHRIMP', publicName: '冷冻南美白虾', category: 'CRUSTACEAN', specification: '1kg/袋', sourceType: 'IMPORT', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
}

const organization = { id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }

type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function backend(overrides: Overrides = {}) {
  return installFakeFetch({
    'GET /api/v1/me': () => ({ status: 200, body: envelope(sampleUser) }),
    'GET /api/v1/batches': () => ({ status: 200, body: envelope(batches, { number: 1, size: 20, totalElements: 2, totalPages: 1 }) }),
    'GET /api/v1/batches/12': () => ({ status: 200, body: envelope(batches[0]) }),
    'GET /api/v1/batches/12/events': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope(products[5]) }),
    'GET /api/v1/products/6': () => ({ status: 200, body: envelope(products[6]) }),
    'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope(organization) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batches/12/public-trace-code': () => problem(404, 'PUBLIC_TRACE_CODE_NOT_FOUND', '该批次尚未激活公开追溯码'),
    ...overrides
  })
}

let wrapper: VueWrapper | null = null

async function mountAt(path: string): Promise<{ router: Router; view: VueWrapper }> {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { router, view: wrapper }
}

function listCalls(calls: RecordedCall[]) {
  return calls.filter((c) => c.path === '/api/v1/batches')
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

describe('BatchListView', () => {
  it('renders dual identifiers, product, quantity, responsible org and dual status from the API', async () => {
    const { calls } = backend()
    const { view } = await mountAt('/app/batches')

    const rows = view.findAll('.batch-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('TB-AAAAAAAAAAAAAAAAAAAAAAAAAA')
    expect(rows[0].text()).toContain('SUP-2026-001')
    expect(rows[0].text()).toContain('冷冻大黄鱼')
    expect(rows[0].text()).toContain('1,000 kg')
    expect(rows[0].text()).toContain('东海水产加工有限公司')
    expect(rows[0].text()).toContain('可流转')
    expect(rows[0].text()).toContain('正常')
    expect(rows[1].text()).toContain('未填写')
    expect(rows[1].text()).toContain('冷冻南美白虾')
    expect(rows[1].text()).toContain('12.5 kg')
    expect(rows[1].text()).toContain('已关闭')
    expect(rows[1].text()).toContain('模拟召回')
    expect(view.find('[data-testid="batch-page-summary"]').text()).toContain('共 2 条')

    const listCall = listCalls(calls)[0]
    expect(listCall.search.get('page')).toBe('1')
    expect(listCall.search.get('size')).toBe('20')
    expect(listCall.search.has('flowStatus')).toBe(false)
    // 同一组织只读取一次目录
    expect(calls.filter((c) => c.path === '/api/v1/organizations/30')).toHaveLength(1)
  })

  it('applies flow and risk filters through the URL and clears them', async () => {
    const { calls } = backend()
    const { router, view } = await mountAt('/app/batches?page=3')

    await view.find('[data-testid="filter-flow-status"]').setValue('ACTIVE')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ flowStatus: 'ACTIVE' })

    await view.find('[data-testid="filter-risk-status"]').setValue('FROZEN')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ flowStatus: 'ACTIVE', riskStatus: 'FROZEN' })

    const filtered = listCalls(calls).at(-1)!
    expect(filtered.search.get('flowStatus')).toBe('ACTIVE')
    expect(filtered.search.get('riskStatus')).toBe('FROZEN')
    expect(filtered.search.get('page')).toBe('1')

    await view.find('[data-testid="clear-filters"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({})
    const cleared = listCalls(calls).at(-1)!
    expect(cleared.search.has('flowStatus')).toBe(false)
    expect(cleared.search.has('riskStatus')).toBe(false)
  })

  it('ignores invalid status values in the URL instead of sending them', async () => {
    const { calls } = backend()
    await mountAt('/app/batches?flowStatus=QUARANTINED&riskStatus=bogus&page=-2')

    const call = listCalls(calls)[0]
    expect(call.search.has('flowStatus')).toBe(false)
    expect(call.search.has('riskStatus')).toBe(false)
    expect(call.search.get('page')).toBe('1')
  })

  it('shows an empty state for a filtered empty page', async () => {
    backend({
      'GET /api/v1/batches': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) })
    })
    const { view } = await mountAt('/app/batches?riskStatus=FROZEN')

    expect(view.find('[data-testid="batch-list-empty"]').text()).toContain('没有符合当前筛选条件的批次')
  })

  it('shows a failure state with the request id and retries', async () => {
    let fail = true
    backend({
      'GET /api/v1/batches': () => (fail
        ? problem(500, 'INTERNAL_ERROR', '服务器处理请求时发生内部错误')
        : { status: 200, body: envelope(batches, { number: 1, size: 20, totalElements: 2, totalPages: 1 }) })
    })
    const { view } = await mountAt('/app/batches')

    const error = view.find('[data-testid="batch-list-error"]')
    expect(error.text()).toContain('服务器处理请求时发生内部错误')
    expect(error.text()).toContain('req-500')

    fail = false
    await error.find('button').trigger('click')
    await flushPromises()
    expect(view.findAll('.batch-row')).toHaveLength(2)
  })

  it('paginates with the real page metadata', async () => {
    const { calls } = backend({
      'GET /api/v1/batches': (call) => ({
        status: 200,
        body: envelope(batches, { number: Number(call.search.get('page')), size: 20, totalElements: 41, totalPages: 3 })
      })
    })
    const { router, view } = await mountAt('/app/batches')

    expect(view.find('[data-testid="page-prev"]').attributes('disabled')).toBeDefined()
    await view.find('[data-testid="page-next"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ page: '2' })
    expect(listCalls(calls).at(-1)!.search.get('page')).toBe('2')
    expect(view.find('[data-testid="batch-page-summary"]').text()).toContain('第 2 / 3 页')
  })

  it('opens the batch detail when a row is clicked', async () => {
    backend()
    const { router, view } = await mountAt('/app/batches')

    await view.find('.batch-row').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/app/batches/12')
  })
})

describe('BatchDetailView', () => {
  it('renders the read-only batch detail with product and responsible organization', async () => {
    backend()
    const { view } = await mountAt('/app/batches/12')

    expect(view.find('[data-testid="detail-trace-batch-no"]').text()).toBe('TB-AAAAAAAAAAAAAAAAAAAAAAAAAA')
    expect(view.find('[data-testid="detail-external-batch-no"]').text()).toBe('SUP-2026-001')
    expect(view.find('[data-testid="detail-product-name"]').text()).toBe('冷冻大黄鱼')
    expect(view.find('[data-testid="detail-quantity"]').text()).toBe('1,000 kg')
    expect(view.find('[data-testid="detail-origin-text"]').text()).toBe('东海舟山渔场')
    expect(view.find('[data-testid="detail-org-name"]').text()).toBe('东海水产加工有限公司')
    expect(view.find('[data-testid="detail-flow-status"]').text()).toBe('可流转')
    expect(view.find('[data-testid="detail-risk-status"]').text()).toBe('正常')
    expect(view.find('[data-testid="detail-version"]').text()).toBe('1')
    expect(view.text()).toContain('来源批次')
    expect(view.text()).toContain('P-YELLOW')
    expect(view.text()).toContain('2026-08-30')
    expect(view.text()).toContain('365 天')
    // 非来源企业查看 ACTIVE 批次：不提供提交激活等写操作入口
    expect(view.find('[data-testid="submit-activation"]').exists()).toBe(false)
    // 按钮只有当前责任组织的公开追溯码激活（Slice 6）与自有冷库入库 / 出库（Slice 4），不含其他写操作
    expect(view.findAll('.enterprise-main button').map((b) => b.attributes('data-testid'))).toEqual(['warehouse-in', 'warehouse-out', 'public-code-activate'])
    // 当前责任组织操作员查看 ACTIVE/NORMAL 批次且无未结束交接：提供“发起交接”入口（服务端仍独立校验）
    expect(view.find('[data-testid="initiate-transfer"]').attributes('href')).toBe('/app/batches/12/transfers/new')
    expect(view.find('[data-testid="batch-transfers-empty"]').exists()).toBe(true)
  })

  it('keeps the last list filters on the back link', async () => {
    backend()
    const { router, view } = await mountAt('/app/batches?flowStatus=ACTIVE')

    await view.find('.batch-row').trigger('click')
    await flushPromises()
    await view.find('[data-testid="back-to-list"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/app/batches?flowStatus=ACTIVE')
  })

  it('shows not-found and forbidden states from the API', async () => {
    backend({
      'GET /api/v1/batches/404': () => problem(404, 'RESOURCE_NOT_FOUND'),
      'GET /api/v1/batches/403': () => problem(403, 'ORG_SCOPE_DENIED')
    })
    const { router, view } = await mountAt('/app/batches/404')
    expect(view.find('[data-testid="batch-detail-not-found"]').exists()).toBe(true)

    await router.push('/app/batches/403')
    await flushPromises()
    expect(view.find('[data-testid="batch-detail-forbidden"]').exists()).toBe(true)
  })

  it('still renders the batch when a directory read fails', async () => {
    backend({ 'GET /api/v1/organizations/30': () => problem(403, 'ORG_SCOPE_DENIED') })
    const { view } = await mountAt('/app/batches/12')

    expect(view.find('[data-testid="detail-trace-batch-no"]').exists()).toBe(true)
    expect(view.find('[data-testid="detail-org-name"]').text()).toContain('组织 #30')
  })

  it('returns to /login when the session expires while loading', async () => {
    backend({ 'GET /api/v1/batches/12': () => problem(401, 'AUTH_REQUIRED') })
    const { router } = await mountAt('/app/batches')
    setUnauthorizedHandler(() => {
      clearSession()
      router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    })

    await router.push('/app/batches/12')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
