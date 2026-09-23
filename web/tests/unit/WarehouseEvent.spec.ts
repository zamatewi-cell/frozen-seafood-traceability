import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canRecordWarehouseEvent } from '@/utils/permissions'
import { toLocalDateTimeInput } from '@/utils/datetime'
import type { Batch, CurrentUser } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 4：批次详情自有冷库入库 / 出库面板。
 * 仅当前责任组织的企业操作员对 ACTIVE + NORMAL 批次可见；冷库选项来自本组织场所目录（COLD_STORE），不硬编码；
 * 提交只发送白名单字段（dataSource 固定 MANUAL，无 detailsJson），成功后刷新批次与时间线。
 */

const originalFetch = globalThis.fetch

const baseBatch = {
  id: 21, orgId: 30, productId: 5, traceBatchNo: 'TB-B2AAAAAAAAAAAAAAAAAAAAAAAA', batchType: 'PROCESSING',
  quantity: 600, remainingQuantity: 600, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
  flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 3
}

const sites = [
  { id: 301, orgId: 30, siteNo: 'PRC-FAC', name: '舟山加工厂', siteType: 'FACTORY', status: 'ACTIVE' },
  { id: 302, orgId: 30, siteNo: 'PRC-COLD', name: '舟山自有冷库', siteType: 'COLD_STORE', status: 'ACTIVE' },
  { id: 303, orgId: 30, siteNo: 'PRC-COLD2', name: '沈家门二号冷库', siteType: 'COLD_STORE', status: 'ACTIVE' },
  { id: 304, orgId: 30, siteNo: 'PRC-COLD-OLD', name: '停用冷库', siteType: 'COLD_STORE', status: 'INACTIVE' }
]

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function backend(options: { user?: Json; batch?: Json; sites?: Json[]; overrides?: Overrides } = {}) {
  const batch = { ...baseBatch, ...(options.batch ?? {}) }
  const events: Json[] = []
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...sampleUser, ...(options.user ?? {}) }) }),
    'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(batch) }),
    'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope(events) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
    'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
    'GET /api/v1/organizations/30/sites': () => ({ status: 200, body: envelope(options.sites ?? sites) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'POST /api/v1/batches/21/events': (call) => {
      const body = call.body as Json
      const event = {
        id: 900 + events.length, batchId: 21, orgId: 30, siteId: body.siteId, eventType: body.eventType,
        occurredAt: body.occurredAt, recordedAt: '2026-09-23T02:00:00.000Z', operatorId: 7,
        dataSource: body.dataSource, status: 'SUBMITTED', summary: body.summary
      }
      events.push(event)
      return { status: 201, body: envelope(event) }
    },
    ...(options.overrides ?? {})
  })
  return { ...handle, events }
}

let wrapper: VueWrapper | null = null

async function mountDetail() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/app/batches/21')
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

describe('Warehouse panel visibility', () => {
  it('is shown to the current responsible org OPERATOR on an ACTIVE + NORMAL batch without loading sites up front', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    expect(view.find('[data-testid="warehouse-panel"]').exists()).toBe(true)
    expect(view.find('[data-testid="warehouse-in"]').text()).toBe('冷库入库')
    expect(view.find('[data-testid="warehouse-out"]').text()).toBe('冷库出库')
    expect(calls.some((c) => c.path === '/api/v1/organizations/30/sites')).toBe(false)
  })

  it.each([
    ['CLOSED batch', { batch: { flowStatus: 'CLOSED', remainingQuantity: 0 } }],
    ['FROZEN batch', { batch: { riskStatus: 'FROZEN' } }],
    ['batch held by another org', { batch: { orgId: 40 } }],
    ['non-OPERATOR user', { user: { roles: ['QUALITY_MANAGER'] } }],
    ['PLATFORM scope', { user: { scopes: ['PLATFORM'] } }]
  ])('is hidden for %s', async (_label, options) => {
    backend(options as { user?: Json; batch?: Json })
    const view = await mountDetail()
    expect(view.find('[data-testid="warehouse-panel"]').exists()).toBe(false)
  })

  it('does not restrict org type: a RETAILER operator holding the batch sees the panel', async () => {
    backend({ user: { orgType: 'RETAILER' } })
    const view = await mountDetail()
    expect(view.find('[data-testid="warehouse-panel"]').exists()).toBe(true)
  })
})

describe('Warehouse inbound / outbound form', () => {
  it('lists only active COLD_STORE sites of the own org from the directory API', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    expect(calls.filter((c) => c.path === '/api/v1/organizations/30/sites')).toHaveLength(1)
    const options = view.findAll('[data-testid="field-warehouse-site"] option').map((o) => o.text())
    expect(options).toEqual(['请选择本组织冷库', '舟山自有冷库（PRC-COLD）', '沈家门二号冷库（PRC-COLD2）'])
  })

  it('shows an empty state when the org has no active cold store', async () => {
    backend({ sites: [sites[0]] })
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="warehouse-no-cold-store"]').exists()).toBe(true)
    expect(view.find('[data-testid="warehouse-form"]').exists()).toBe(false)
  })

  it('requires a cold store before submitting', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="error-warehouse-site"]').text()).toBe('请选择本组织冷库')
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')).toBe(false)
  })

  it('submits a whitelisted MANUAL payload with CSRF + Idempotency-Key, then refreshes batch and timeline with the site name', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    const batchLoadsBefore = calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21').length
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    expect((view.find('[data-testid="field-warehouse-summary"]').element as HTMLInputElement).value).toBe('冷库入库：舟山自有冷库')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')
    expect(posts).toHaveLength(1)
    const body = posts[0].body as Json
    expect(Object.keys(body).sort()).toEqual(['dataSource', 'eventType', 'occurredAt', 'siteId', 'summary'])
    expect(body).toMatchObject({ eventType: 'WAREHOUSE_IN', siteId: 302, dataSource: 'MANUAL', summary: '冷库入库：舟山自有冷库' })
    expect(String(body.occurredAt)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
    expect(posts[0].headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(16)
    expect(posts[0].headers['X-CSRF-TOKEN']).toBe('csrf-token-1')

    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21').length).toBe(batchLoadsBefore + 1)
    expect(view.find('[data-testid="batch-flash"]').text()).toContain('冷库入库已记录')
    const event = view.find('[data-testid="trace-event"][data-event-type="WAREHOUSE_IN"]')
    expect(event.exists()).toBe(true)
    expect(event.find('[data-testid="event-warehouse-site"]').text()).toBe('舟山自有冷库')
    expect(view.find('[data-testid="detail-quantity"]').text()).toContain('600 kg')
  })

  it('pre-selects the latest inbound cold store for outbound (convenience only, no ordering rule)', async () => {
    backend()
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('303')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()

    await view.find('[data-testid="warehouse-out"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="warehouse-form"]').attributes('data-mode')).toBe('WAREHOUSE_OUT')
    expect((view.find('[data-testid="field-warehouse-site"]').element as HTMLSelectElement).value).toBe('303')
    expect((view.find('[data-testid="field-warehouse-summary"]').element as HTMLInputElement).value).toBe('冷库出库：沈家门二号冷库')
  })

  it('shows the server rejection and keeps the form open', async () => {
    backend({ overrides: { 'POST /api/v1/batches/21/events': () => problem(422, 'WAREHOUSE_SITE_TYPE_INVALID', '冷库出入库事件只能引用类型为 COLD_STORE 的场所') } })
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-out"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="warehouse-error"]').exists()).toBe(true)
    expect(view.find('[data-testid="warehouse-form"]').exists()).toBe(true)
  })

  it('reuses the same Idempotency-Key when retrying after a network failure', async () => {
    let attempts = 0
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/events': (call) => {
          attempts += 1
          if (attempts === 1) throw new TypeError('Failed to fetch')
          return { status: 201, body: envelope({ id: 950, batchId: 21, orgId: 30, siteId: 302, eventType: 'WAREHOUSE_IN', occurredAt: (call.body as Json).occurredAt, recordedAt: '2026-09-23T02:00:00.000Z', dataSource: 'MANUAL', status: 'SUBMITTED', summary: '冷库入库：舟山自有冷库' }) }
        }
      }
    })
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="warehouse-error"]').exists()).toBe(true)
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[1].body).toEqual(posts[0].body)
  })

  it('defaults the business time to the current local time with seconds (datetime-local step="1")', async () => {
    backend()
    const view = await mountDetail()
    const before = Date.now()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    const input = view.find('[data-testid="field-warehouse-occurred-at"]')
    expect(input.attributes('step')).toBe('1')
    // 界面取值为本地 YYYY-MM-DDTHH:mm:ss（jsdom 回读 value 时会补 .000，按时间值比较）
    const max = input.attributes('max') ?? ''
    expect(max).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    const value = (input.element as HTMLInputElement).value
    expect(new Date(value).getTime()).toBe(new Date(max).getTime())
    expect(new Date(value).getMilliseconds()).toBe(0)
    expect(Math.abs(new Date(value).getTime() - before)).toBeLessThan(2000)
  })

  it('preserves the entered seconds in the API payload without inventing milliseconds', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    await view.find('[data-testid="field-warehouse-occurred-at"]').setValue('2026-01-15T10:40:18')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    const body = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')!.body as Json
    expect(body.occurredAt).toBe(new Date('2026-01-15T10:40:18').toISOString())
    expect(String(body.occurredAt)).toMatch(/:18\.000Z$/)
  })

  it('still rejects a business time later than now (seconds precision) without calling the API', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    await view.find('[data-testid="field-warehouse-occurred-at"]').setValue(toLocalDateTimeInput(new Date(Date.now() + 5 * 60_000)))
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="error-warehouse-occurred-at"]').text()).toBe('发生时间不能晚于当前时间')
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')).toBe(false)
  })

  it('retries with the identical second-precision timestamp and the same Idempotency-Key', async () => {
    let attempts = 0
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/events': (call) => {
          attempts += 1
          if (attempts === 1) throw new TypeError('Failed to fetch')
          return { status: 201, body: envelope({ id: 951, batchId: 21, orgId: 30, siteId: 302, eventType: 'WAREHOUSE_IN', occurredAt: (call.body as Json).occurredAt, recordedAt: '2026-09-23T02:00:00.000Z', dataSource: 'MANUAL', status: 'SUBMITTED', summary: '冷库入库：舟山自有冷库' }) }
        }
      }
    })
    const view = await mountDetail()
    await view.find('[data-testid="warehouse-in"]').trigger('click')
    await flushPromises()
    await view.find('[data-testid="field-warehouse-site"]').setValue('302')
    await view.find('[data-testid="field-warehouse-occurred-at"]').setValue('2026-01-15T10:40:59')
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    await view.find('[data-testid="warehouse-form"]').trigger('submit')
    await flushPromises()
    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/events')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect((posts[0].body as Json).occurredAt).toBe(new Date('2026-01-15T10:40:59').toISOString())
    expect((posts[1].body as Json).occurredAt).toBe((posts[0].body as Json).occurredAt)
  })
})

describe('canRecordWarehouseEvent', () => {
  const user = sampleUser as CurrentUser
  const batch = baseBatch as unknown as Batch
  it('follows only operator / non-platform / responsible org / ACTIVE + NORMAL', () => {
    expect(canRecordWarehouseEvent(user, batch)).toBe(true)
    expect(canRecordWarehouseEvent({ ...user, orgType: 'SOURCE' }, batch)).toBe(true)
    expect(canRecordWarehouseEvent(user, { ...batch, orgId: 99 })).toBe(false)
    expect(canRecordWarehouseEvent(user, { ...batch, flowStatus: 'CLOSED' })).toBe(false)
    expect(canRecordWarehouseEvent(user, { ...batch, flowStatus: 'DRAFT' })).toBe(false)
    expect(canRecordWarehouseEvent(user, { ...batch, riskStatus: 'RECALLED' })).toBe(false)
    expect(canRecordWarehouseEvent({ ...user, roles: ['AUDITOR'] }, batch)).toBe(false)
    expect(canRecordWarehouseEvent({ ...user, scopes: ['PLATFORM'] }, batch)).toBe(false)
    expect(canRecordWarehouseEvent(null, batch)).toBe(false)
    expect(canRecordWarehouseEvent(user, null)).toBe(false)
  })
})
