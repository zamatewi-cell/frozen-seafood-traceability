import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canInitiateTransfer, canOperateBatch, canRecordSale } from '@/utils/permissions'
import { toLocalDateTimeInput } from '@/utils/datetime'
import type { Batch, CurrentUser } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 5：批次详情终端销售面板与销售记录。
 * 仅 RETAILER 的企业操作员对本组织负责、ACTIVE + NORMAL 且有剩余量的批次可见；门店选项来自本组织场所目录（STORE）；
 * 提交只发送白名单字段（不发送剩余量或单位），成功后以服务端最新批次刷新；首次销售后隐藏交接入口。
 */

const originalFetch = globalThis.fetch

const retailerUser = { ...sampleUser, username: 'retail_op', displayName: '零售操作员', orgId: 40, orgNo: 'ORG_RET_01', orgName: '海港生鲜超市', orgType: 'RETAILER' }

const baseBatch = {
  id: 21, orgId: 40, productId: 5, traceBatchNo: 'TB-B2AAAAAAAAAAAAAAAAAAAAAAAA', batchType: 'PROCESSING',
  quantity: 600, remainingQuantity: 600, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
  flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 5
}

const sites = [
  { id: 401, orgId: 40, siteNo: 'RT-STORE', name: '海港一号门店', siteType: 'STORE', status: 'ACTIVE' },
  { id: 402, orgId: 40, siteNo: 'RT-DC', name: '配送中心', siteType: 'LOGISTICS_HUB', status: 'ACTIVE' },
  { id: 403, orgId: 40, siteNo: 'RT-OLD', name: '停用门店', siteType: 'STORE', status: 'INACTIVE' },
  { id: 404, orgId: 40, siteNo: 'RT-STORE2', name: '海港二号门店', siteType: 'STORE', status: 'ACTIVE' }
]

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function backend(options: { user?: Json; batch?: Json; sites?: Json[]; transfers?: Json[]; overrides?: Overrides } = {}) {
  const state = { batch: { ...baseBatch, ...(options.batch ?? {}) } as Json }
  const sales: Json[] = []
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...retailerUser, ...(options.user ?? {}) }) }),
    'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(state.batch) }),
    'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/sales': () => ({ status: 200, body: envelope(sales) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
    'GET /api/v1/organizations/40': () => ({ status: 200, body: envelope({ id: 40, orgNo: 'ORG_RET_01', name: '海港生鲜超市', orgType: 'RETAILER', status: 'ACTIVE' }) }),
    'GET /api/v1/organizations/40/sites': () => ({ status: 200, body: envelope(options.sites ?? sites) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope(options.transfers ?? [], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'POST /api/v1/batches/21/sales': (call) => {
      const body = call.body as Json
      const sale = { id: 7000 + sales.length, batchId: 21, orgId: 40, siteId: body.siteId, siteName: '海港一号门店', quantity: body.quantity, unitCode: 'kg', occurredAt: body.occurredAt, status: 'SUBMITTED' }
      sales.push(sale)
      const remaining = Number(state.batch.remainingQuantity) - Number(body.quantity)
      state.batch = {
        ...state.batch,
        remainingQuantity: remaining,
        firstSaleId: state.batch.firstSaleId ?? sale.id,
        flowStatus: remaining === 0 ? 'CLOSED' : 'ACTIVE',
        version: Number(state.batch.version) + 1
      }
      return { status: 201, body: envelope(sale) }
    },
    ...(options.overrides ?? {})
  })
  return { ...handle, sales, state }
}

let wrapper: VueWrapper | null = null

async function mountDetail() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/app/batches/21')
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function openForm(view: VueWrapper) {
  await view.find('[data-testid="sale-start"]').trigger('click')
  await flushPromises()
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

describe('Sale panel visibility', () => {
  it('is shown to a RETAILER operator holding an ACTIVE + NORMAL batch with remaining quantity', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    expect(view.find('[data-testid="sale-panel"]').exists()).toBe(true)
    expect(view.find('[data-testid="sale-remaining"]').text()).toBe('600 kg')
    expect(view.find('[data-testid="sale-history-empty"]').exists()).toBe(true)
    expect(calls.some((c) => c.path === '/api/v1/organizations/40/sites')).toBe(false)
  })

  it.each([
    ['PROCESSOR operator', { user: { orgType: 'PROCESSOR' } }],
    ['non-OPERATOR user', { user: { roles: ['QUALITY_MANAGER'] } }],
    ['PLATFORM scope', { user: { scopes: ['PLATFORM'] } }],
    ['batch held by another org', { batch: { orgId: 30 } }],
    ['CLOSED batch', { batch: { flowStatus: 'CLOSED', remainingQuantity: 0 } }],
    ['FROZEN batch', { batch: { riskStatus: 'FROZEN' } }],
    ['RECALLED batch', { batch: { riskStatus: 'RECALLED' } }],
    ['open transfer', { transfers: [{ id: 5, transferNo: 'TRF-1', batchId: 21, senderOrgId: 40, receiverOrgId: 30, quantity: 600, unitCode: 'kg', status: 'DRAFT', version: 0 }] }]
  ])('is hidden for %s', async (_label, options) => {
    backend(options as { user?: Json; batch?: Json; transfers?: Json[] })
    const view = await mountDetail()
    expect(view.find('[data-testid="sale-panel"]').exists()).toBe(false)
  })
})

describe('Sale form', () => {
  it('lists only active STORE sites of the own org', async () => {
    backend()
    const view = await mountDetail()
    await openForm(view)
    const options = view.findAll('[data-testid="field-sale-site"] option').map((o) => o.text())
    expect(options).toEqual(['请选择本组织门店', '海港一号门店（RT-STORE）', '海港二号门店（RT-STORE2）'])
  })

  it('shows an empty state when the org has no active store', async () => {
    backend({ sites: [sites[1], sites[2]] })
    const view = await mountDetail()
    await openForm(view)
    expect(view.find('[data-testid="sale-no-store"]').exists()).toBe(true)
  })

  it.each([
    ['', '销售数量必须大于 0'],
    ['0', '销售数量必须大于 0'],
    ['1.0005', '销售数量最多 3 位小数'],
    ['600.001', '不能超过当前剩余 600 kg']
  ])('rejects quantity %s locally without calling the API', async (qty, message) => {
    const { calls } = backend()
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue(qty)
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="error-sale-quantity"]').text()).toBe(message)
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')).toBe(false)
  })

  it('submits a whitelisted payload with CSRF + Idempotency-Key, refreshes the batch, lists the sale and hides transfer after the first sale', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    expect(view.find('[data-testid="initiate-transfer"]').exists()).toBe(true)
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('200')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')
    expect(posts).toHaveLength(1)
    const body = posts[0].body as Json
    expect(Object.keys(body).sort()).toEqual(['occurredAt', 'quantity', 'siteId'])
    expect(body).toMatchObject({ siteId: 401, quantity: 200 })
    expect(String(body.occurredAt)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
    expect(posts[0].headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(16)
    expect(posts[0].headers['X-CSRF-TOKEN']).toBe('csrf-token-1')

    expect(view.find('[data-testid="batch-flash"]').text()).toContain('已登记终端销售 200 kg；剩余 400 kg')
    expect(view.find('[data-testid="detail-remaining-quantity"]').text()).toContain('400 kg')
    expect(view.find('[data-testid="sale-started"]').exists()).toBe(true)
    expect(view.find('[data-testid="initiate-transfer"]').exists()).toBe(false)
    expect(view.findAll('[data-testid="sale-row"]')).toHaveLength(1)
    expect(view.find('[data-testid="sale-panel"]').exists()).toBe(true)
    expect(view.find('[data-testid="sale-remaining"]').text()).toBe('400 kg')
  })

  it('sells the remainder to zero: batch closes, panel disappears and history stays', async () => {
    backend({ batch: { remainingQuantity: 400, firstSaleId: 6999 } })
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="sale-all"]').trigger('click')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="batch-flash"]').text()).toContain('批次已售罄')
    expect(view.find('[data-testid="sale-sold-out"]').exists()).toBe(true)
    expect(view.find('[data-testid="sale-panel"]').exists()).toBe(false)
    expect(view.findAll('[data-testid="sale-row"]')).toHaveLength(1)
  })

  it('shows the server oversell rejection and reloads the batch', async () => {
    const { calls } = backend({ overrides: { 'POST /api/v1/batches/21/sales': () => problem(422, 'SALE_QUANTITY_EXCEEDS_REMAINING', '本次销售超过批次当前剩余') } })
    const view = await mountDetail()
    const loadsBefore = calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21').length
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('100')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21').length).toBe(loadsBefore + 1)
  })

  it('reuses the same Idempotency-Key and payload when retrying after a network failure', async () => {
    let attempts = 0
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/sales': (call) => {
          attempts += 1
          if (attempts === 1) throw new TypeError('Failed to fetch')
          const body = call.body as Json
          return { status: 201, body: envelope({ id: 7100, batchId: 21, orgId: 40, siteId: body.siteId, quantity: body.quantity, unitCode: 'kg', occurredAt: body.occurredAt, status: 'SUBMITTED' }) }
        }
      }
    })
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('50')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="sale-error"]').exists()).toBe(true)
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[1].body).toEqual(posts[0].body)
  })

  it('defaults the business time to the current local time with seconds (datetime-local step="1")', async () => {
    backend()
    const view = await mountDetail()
    const before = Date.now()
    await openForm(view)
    const input = view.find('[data-testid="field-sale-occurred-at"]')
    expect(input.attributes('step')).toBe('1')
    // 界面取值为本地 YYYY-MM-DDTHH:mm:ss（jsdom 回读 value 时会补 .000，按时间值比较）
    const max = input.attributes('max') ?? ''
    expect(max).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    const value = (input.element as HTMLInputElement).value
    expect(new Date(value).getTime()).toBe(new Date(max).getTime())
    expect(new Date(value).getMilliseconds()).toBe(0)
    // 默认值即打开表单时刻（到秒），不向下取整到分钟
    expect(Math.abs(new Date(value).getTime() - before)).toBeLessThan(2000)
  })

  it('preserves the entered seconds in the API payload without inventing milliseconds', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('10')
    await view.find('[data-testid="field-sale-occurred-at"]').setValue('2026-01-15T10:40:18')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    const body = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')!.body as Json
    expect(body.occurredAt).toBe(new Date('2026-01-15T10:40:18').toISOString())
    expect(String(body.occurredAt)).toMatch(/:18\.000Z$/)
  })

  it('still rejects a business time later than now (seconds precision) without calling the API', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('10')
    await view.find('[data-testid="field-sale-occurred-at"]').setValue(toLocalDateTimeInput(new Date(Date.now() + 5 * 60_000)))
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="error-sale-occurred-at"]').text()).toBe('销售时间不能晚于当前时间')
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')).toBe(false)
  })

  it('retries with the identical second-precision timestamp and the same Idempotency-Key', async () => {
    let attempts = 0
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/sales': (call) => {
          attempts += 1
          if (attempts === 1) throw new TypeError('Failed to fetch')
          const body = call.body as Json
          return { status: 201, body: envelope({ id: 7101, batchId: 21, orgId: 40, siteId: body.siteId, quantity: body.quantity, unitCode: 'kg', occurredAt: body.occurredAt, status: 'SUBMITTED' }) }
        }
      }
    })
    const view = await mountDetail()
    await openForm(view)
    await view.find('[data-testid="field-sale-site"]').setValue('401')
    await view.find('[data-testid="field-sale-quantity"]').setValue('20')
    await view.find('[data-testid="field-sale-occurred-at"]').setValue('2026-01-15T10:40:59')
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    await view.find('[data-testid="sale-form"]').trigger('submit')
    await flushPromises()
    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/sales')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect((posts[0].body as Json).occurredAt).toBe(new Date('2026-01-15T10:40:59').toISOString())
    expect((posts[1].body as Json).occurredAt).toBe((posts[0].body as Json).occurredAt)
  })
})

describe('permission helpers with the first-sale lock', () => {
  const retailer = retailerUser as CurrentUser
  const processor = sampleUser as CurrentUser
  const batch = baseBatch as unknown as Batch
  it('canRecordSale follows RETAILER / operator / non-platform / responsible org / ACTIVE + NORMAL / remaining > 0', () => {
    expect(canRecordSale(retailer, batch)).toBe(true)
    expect(canRecordSale(retailer, { ...batch, firstSaleId: 1, remainingQuantity: 1 })).toBe(true)
    expect(canRecordSale(processor, { ...batch, orgId: 30 })).toBe(false)
    expect(canRecordSale(retailer, { ...batch, orgId: 30 })).toBe(false)
    expect(canRecordSale(retailer, { ...batch, remainingQuantity: 0 })).toBe(false)
    expect(canRecordSale(retailer, { ...batch, remainingQuantity: undefined })).toBe(false)
    expect(canRecordSale(retailer, { ...batch, flowStatus: 'CLOSED' })).toBe(false)
    expect(canRecordSale(retailer, { ...batch, riskStatus: 'FROZEN' })).toBe(false)
    expect(canRecordSale({ ...retailer, roles: ['QUALITY_MANAGER'] }, batch)).toBe(false)
    expect(canRecordSale({ ...retailer, scopes: ['PLATFORM'] }, batch)).toBe(false)
    expect(canRecordSale(null, batch)).toBe(false)
  })

  it('hides transfer and batch operation entries once the first sale exists, even with remaining quantity', () => {
    expect(canInitiateTransfer(retailer, batch)).toBe(true)
    expect(canInitiateTransfer(retailer, { ...batch, firstSaleId: 7000 })).toBe(false)
    const processorBatch = { ...batch, orgId: 30 }
    expect(canOperateBatch(processor, processorBatch)).toBe(true)
    expect(canOperateBatch(processor, { ...processorBatch, firstSaleId: 7000 })).toBe(false)
  })
})
