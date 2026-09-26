import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canRecordShipmentTemperature } from '@/utils/permissions'
import { formatTemperature, formatTemperatureEvaluation } from '@/utils/formatters'
import { toLocalDateTimeInput } from '@/utils/datetime'
import type { CurrentUser, Shipment } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B PB2：运输任务详情“在途温度记录”面板。
 * 运输任务指定承运组织的操作员在 IN_TRANSIT 期间逐条登记温度（CSRF + Idempotency-Key，结果未知时重试复用同一键）；
 * 服务端返回单点判定与判定依据；发货方 / 接收方只读；PLANNED 不显示面板；409 刷新运输任务并保留原因。
 * 单点越界只被表述为单点判定，从不表述为持续超温、告警或温控合规结论。
 */

const originalFetch = globalThis.fetch

const carrierOperator = { ...sampleUser, userId: 11, username: 'carrier_op', displayName: '承运操作员', orgId: 50, orgNo: 'ORG_CAR_01', orgName: '极冷冷链物流', orgType: 'CARRIER' }
const senderOperator = { ...sampleUser }
const receiverQm = { ...sampleUser, userId: 21, username: 'retail_qm', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER', roles: ['QUALITY_MANAGER'] }

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

const party = (id: number, name: string, orgType: string) => ({ id, orgNo: `ORG-${id}`, name, orgType })
const RULE = { ruleId: 51, name: '冷冻大黄鱼运输规则', versionNo: 1, ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 }

function record(id: number, temperature: number, evaluation: string, extra: Json = {}): Json {
  return {
    id, shipmentId: 601, stageCode: 'TRANSPORT', measuredAt: new Date(Date.now() - 600_000 + id * 1000).toISOString(),
    recordedAt: new Date().toISOString(), temperature, unitCode: 'CELSIUS', dataSource: 'MANUAL', evaluation,
    ...(evaluation === 'MISSING_CONTEXT' ? {} : { rule: RULE }), orgId: 50, actorUserId: 11, ...extra
  }
}

function classify(t: number): string {
  return t > RULE.upperLimit ? 'HIGH' : t < RULE.lowerLimit ? 'LOW' : 'NORMAL'
}

function backend(options: { user?: Json; status?: string; records?: Json[]; overrides?: Overrides } = {}) {
  const state = {
    shipment: {
      id: 601, shipmentNo: 'SHP-PB2-0001', status: options.status ?? 'IN_TRANSIT', vehicleOrContainerNo: '浙L·冷001', version: 2,
      senderOrg: party(30, '东海水产加工有限公司', 'PROCESSOR'), receiverOrg: party(60, '鲜到家零售', 'RETAILER'),
      carrierOrg: party(50, '极冷冷链物流', 'CARRIER'),
      originSite: { id: 301, orgId: 30, siteNo: 'PRC-COLD', name: '舟山自有冷库', siteType: 'COLD_STORE', status: 'ACTIVE' },
      destinationSite: { id: 601, orgId: 60, siteNo: 'RET-STORE', name: '鲜到家门店', siteType: 'STORE', status: 'ACTIVE' },
      loadedAt: options.status === 'PLANNED' ? undefined : new Date(Date.now() - 3_600_000).toISOString(),
      transfers: [{ transferId: 501, transferNo: 'TRF-1', batchId: 21, traceBatchNo: 'TB-B2', quantity: 600, unitCode: 'kg', status: 'PENDING', version: 2 }],
      createdAt: '2026-09-24T01:00:00Z', updatedAt: '2026-09-24T01:00:00Z'
    } as Json,
    records: [...(options.records ?? [])] as Json[]
  }
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...carrierOperator, ...(options.user ?? {}) }) }),
    'GET /api/v1/shipments/601': () => ({ status: 200, body: envelope(state.shipment) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 100, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/shipments/601/temperature-records': () => ({ status: 200, body: envelope(state.records) }),
    'POST /api/v1/shipments/601/temperature-records': (call) => {
      const body = call.body as Json
      const t = body.temperature as number
      const row = record(state.records.length + 100, t, classify(t), {
        measuredAt: body.measuredAt, dataSource: body.dataSource, ...(body.deviceNo ? { deviceNo: body.deviceNo } : {})
      })
      state.records.push(row)
      return { status: 201, body: envelope(row) }
    },
    ...(options.overrides ?? {})
  })
  return { ...handle, state }
}

let wrapper: VueWrapper | null = null

async function mountDetail() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/app/shipments/601')
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const panel = (view: VueWrapper) => view.find('[data-testid="temperature-panel"]')

async function fill(view: VueWrapper, values: { temperature: string; source?: string; device?: string; measuredAt?: string }) {
  await view.find('[data-testid="temperature-open"]').trigger('click')
  if (values.measuredAt !== undefined) await view.find('[data-testid="field-temperature-measured-at"]').setValue(values.measuredAt)
  await view.find('[data-testid="field-temperature-value"]').setValue(values.temperature)
  if (values.source) await view.find('[data-testid="field-temperature-source"]').setValue(values.source)
  if (values.device !== undefined) await view.find('[data-testid="field-temperature-device"]').setValue(values.device)
  await view.find('[data-testid="temperature-form"]').trigger('submit')
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

describe('carrier records in-transit temperatures', () => {
  it('records single points with CSRF and an Idempotency-Key and shows only single-point evaluations', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    expect(panel(view).exists()).toBe(true)
    expect(view.find('[data-testid="temperature-empty"]').exists()).toBe(true)
    const disclaimer = view.get('[data-testid="temperature-disclaimer"]').text()
    expect(disclaimer).toContain('不等于持续超温')
    expect(disclaimer).toContain('不产生告警')
    expect(disclaimer).toContain('不对消费者公开')
    expect(disclaimer).toContain('未接入真实温度设备')

    await fill(view, { temperature: '-18.5' })
    await fill(view, { temperature: '-12.50', source: 'SIMULATED', device: 'PROBE-01' })

    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    for (const call of posts) {
      expect(call.path).toBe('/api/v1/shipments/601/temperature-records')
      expect(call.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
      expect(call.headers['Idempotency-Key']?.length).toBeGreaterThanOrEqual(16)
    }
    expect(posts[0].headers['Idempotency-Key']).not.toBe(posts[1].headers['Idempotency-Key'])
    expect(Object.keys(posts[0].body as Json).sort()).toEqual(['dataSource', 'measuredAt', 'temperature'])
    expect(posts[0].body).toMatchObject({ temperature: -18.5, dataSource: 'MANUAL' })
    expect(posts[1].body).toMatchObject({ temperature: -12.5, dataSource: 'SIMULATED', deviceNo: 'PROBE-01' })
    expect(String((posts[0].body as Json).measuredAt)).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.000Z$/)

    const rows = view.findAll('[data-testid="temperature-row"]')
    expect(rows.map((r) => r.attributes('data-evaluation'))).toEqual(['NORMAL', 'HIGH'])
    expect(rows[0].find('[data-testid="temperature-value"]').text()).toBe('-18.50 ℃')
    expect(rows[0].find('[data-testid="temperature-evaluation"]').text()).toBe('单点在范围内')
    expect(rows[1].find('[data-testid="temperature-evaluation"]').text()).toBe('单点高于上限')
    expect(rows[1].text()).toContain('教学模拟数据')
    expect(rows[1].text()).toContain('PROBE-01')
    expect(view.get('[data-testid="temperature-rule-band"]').text()).toContain('-25.00 ℃ ~ -15.00 ℃')
    expect(view.get('[data-testid="temperature-summary"]').text()).toContain('单点越界 1 条（单点判定，不等于持续超温）')
    expect(panel(view).text()).not.toMatch(/告警已触发|持续超温告警|全程温控正常|温控合格|超温事件/)
  })

  it('validates time, value and device before sending anything', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    const before = toLocalDateTimeInput(new Date(Date.now() - 2 * 3_600_000))
    const future = toLocalDateTimeInput(new Date(Date.now() + 3_600_000))

    await fill(view, { temperature: '' })
    expect(view.get('[data-testid="error-temperature-value"]').text()).toBe('请填写温度')
    for (const [value, message] of [['-18.555', '温度最多保留两位小数'], ['abc', '温度最多保留两位小数'], ['-80.01', '温度必须在 -80 到 60 ℃ 之间'], ['61', '温度必须在 -80 到 60 ℃ 之间']]) {
      await view.find('[data-testid="field-temperature-value"]').setValue(value)
      await view.find('[data-testid="temperature-form"]').trigger('submit')
      await flushPromises()
      expect(view.get('[data-testid="error-temperature-value"]').text()).toBe(message)
    }
    await view.find('[data-testid="field-temperature-value"]').setValue('-18')
    await view.find('[data-testid="field-temperature-measured-at"]').setValue(future)
    await view.find('[data-testid="temperature-form"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="error-temperature-measured-at"]').text()).toBe('测量时间不能晚于当前时间')
    await view.find('[data-testid="field-temperature-measured-at"]').setValue(before)
    await view.find('[data-testid="temperature-form"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="error-temperature-measured-at"]').text()).toBe('测量时间不能早于装载发运时间')
    await view.find('[data-testid="field-temperature-measured-at"]').setValue(toLocalDateTimeInput(new Date()))
    await view.find('[data-testid="field-temperature-device"]').setValue('探头 1号')
    await view.find('[data-testid="temperature-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="error-temperature-device"]').exists()).toBe(true)

    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('retries an unknown outcome with the same Idempotency-Key and the same payload', async () => {
    let attempt = 0
    const { calls, state } = backend({
      overrides: {
        'POST /api/v1/shipments/601/temperature-records': (call) => {
          attempt += 1
          if (attempt === 1) throw new TypeError('network down')
          const row = record(100, -18, 'NORMAL', { measuredAt: (call.body as Json).measuredAt })
          state.records.push(row)
          return { status: 201, body: envelope(row) }
        }
      }
    })
    const view = await mountDetail()
    await fill(view, { temperature: '-18' })
    expect(view.get('[data-testid="temperature-error"]').text()).toContain('结果未知')
    await view.find('[data-testid="temperature-form"]').trigger('submit')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[1].body).toEqual(posts[0].body)
    expect(view.findAll('[data-testid="temperature-row"]')).toHaveLength(1)
  })

  it('refreshes the shipment and keeps the reason when the server answers 409 (already delivered)', async () => {
    const { state } = backend({
      overrides: {
        'POST /api/v1/shipments/601/temperature-records': () => {
          state.shipment = { ...state.shipment, status: 'DELIVERED', unloadedAt: new Date().toISOString(), version: 3 }
          return problem(409, 'SHIPMENT_NOT_IN_TRANSIT', '仅运输途中（IN_TRANSIT）的运输任务可以登记在途温度，当前状态为: DELIVERED')
        }
      }
    })
    const view = await mountDetail()
    await fill(view, { temperature: '-18' })

    expect(view.get('[data-testid="shipment-flash"]').text()).toContain('仅运输途中')
    expect(view.get('[data-testid="shipment-status"]').attributes('data-status')).toBe('DELIVERED')
    expect(view.find('[data-testid="temperature-open"]').exists()).toBe(false)
    expect(view.find('[data-testid="temperature-form"]').exists()).toBe(false)
  })
})

describe('read-only parties and states', () => {
  it('shows records read-only to the sender and to the receiver quality manager, MISSING_CONTEXT without a rule', async () => {
    for (const user of [senderOperator, receiverQm]) {
      backend({ user, records: [record(1, -18, 'NORMAL'), record(2, 4, 'MISSING_CONTEXT')] })
      const view = await mountDetail()
      expect(panel(view).exists()).toBe(true)
      expect(view.find('[data-testid="temperature-open"]').exists()).toBe(false)
      const rows = view.findAll('[data-testid="temperature-row"]')
      expect(rows).toHaveLength(2)
      expect(rows[1].find('[data-testid="temperature-evaluation"]').text()).toBe('缺少适用规则')
      expect(rows[1].text()).toContain('无适用规则')
      wrapper?.unmount()
      wrapper = null
      resetSessionForTests()
      clearCsrfToken()
    }
  })

  it('hides the panel for a planned shipment and the record button once delivered', async () => {
    backend({ status: 'PLANNED' })
    let view = await mountDetail()
    expect(panel(view).exists()).toBe(false)
    wrapper?.unmount()
    wrapper = null
    resetSessionForTests()

    backend({ status: 'DELIVERED', records: [record(1, -18, 'NORMAL')] })
    view = await mountDetail()
    expect(panel(view).exists()).toBe(true)
    expect(view.find('[data-testid="temperature-open"]').exists()).toBe(false)
    expect(view.findAll('[data-testid="temperature-row"]')).toHaveLength(1)
  })
})

describe('permission and wording helpers', () => {
  const shipment = (status: string, carrierId = 50) => ({ status, carrierOrg: { id: carrierId } }) as unknown as Shipment
  const user = (extra: Partial<CurrentUser>) => ({ ...carrierOperator, ...extra }) as unknown as CurrentUser

  it('only the assigned carrier operator may record, and only in transit', () => {
    expect(canRecordShipmentTemperature(user({}), shipment('IN_TRANSIT'))).toBe(true)
    for (const status of ['PLANNED', 'DELIVERED', 'CANCELLED']) {
      expect(canRecordShipmentTemperature(user({}), shipment(status))).toBe(false)
    }
    expect(canRecordShipmentTemperature(user({}), shipment('IN_TRANSIT', 99))).toBe(false)
    expect(canRecordShipmentTemperature(user({ roles: ['QUALITY_MANAGER'] }), shipment('IN_TRANSIT'))).toBe(false)
    expect(canRecordShipmentTemperature(user({ scopes: ['PLATFORM'] }), shipment('IN_TRANSIT'))).toBe(false)
    expect(canRecordShipmentTemperature(user({ orgType: 'PROCESSOR', orgId: 50 }), shipment('IN_TRANSIT'))).toBe(false)
    expect(canRecordShipmentTemperature(null, shipment('IN_TRANSIT'))).toBe(false)
  })

  it('labels every evaluation as a single point and never as a sustained excursion', () => {
    expect(formatTemperatureEvaluation('NORMAL').label).toBe('单点在范围内')
    expect(formatTemperatureEvaluation('HIGH').label).toBe('单点高于上限')
    expect(formatTemperatureEvaluation('LOW').label).toBe('单点低于下限')
    expect(formatTemperatureEvaluation('MISSING_CONTEXT').label).toBe('缺少适用规则')
    for (const e of ['HIGH', 'LOW']) {
      const info = formatTemperatureEvaluation(e)
      expect(info.tone).toBe('warning')
      expect(info.description).toContain('不等于持续超温')
    }
    expect(formatTemperature(-18.5)).toBe('-18.50 ℃')
    expect(formatTemperature(null)).toBe('未标明')
  })
})
