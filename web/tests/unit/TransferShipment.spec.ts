import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, type Router } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 2：Transfer + Shipment 企业端页面。
 * 以一个有状态的 fetch 替身模拟服务端契约（绑定、提交、发运、到达、接受及其前置条件），
 * 依次以来源、承运、加工三个账号走完整链，并验证入口可见性、服务端拒绝的展示、CSRF 与 Idempotency-Key。
 * 仅在单元测试中使用 fetch 替身；真实冒烟不拦截任何请求。
 */

const originalFetch = globalThis.fetch

const sourceOperator = {
  userId: 9, username: 'source_op', displayName: '来源操作员', orgId: 40, orgNo: 'ORG_SRC_01',
  orgName: '东海远洋捕捞有限公司', orgType: 'SOURCE', roles: ['OPERATOR'], scopes: ['ORG_ONLY']
}
const carrierOperator = { ...sourceOperator, userId: 11, username: 'carrier_op', orgId: 50, orgNo: 'ORG_CAR_01', orgName: '极冷冷链物流', orgType: 'CARRIER' }
const processorOperator = { ...sourceOperator, userId: 7, username: 'processor_op', orgId: 30, orgNo: 'ORG_PRC_01', orgName: '东海水产加工有限公司', orgType: 'PROCESSOR' }

const orgs = [
  { id: 40, orgNo: 'ORG_SRC_01', name: '东海远洋捕捞有限公司', orgType: 'SOURCE', status: 'ACTIVE' },
  { id: 30, orgNo: 'ORG_PRC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' },
  { id: 50, orgNo: 'ORG_CAR_01', name: '极冷冷链物流', orgType: 'CARRIER', status: 'ACTIVE' }
]
const sites: Record<number, unknown[]> = {
  40: [{ id: 401, orgId: 40, siteNo: 'SRC-PORT', name: '沈家门码头', siteType: 'PORT', status: 'ACTIVE' }],
  30: [{ id: 301, orgId: 30, siteNo: 'PRC-FAC', name: '舟山加工厂', siteType: 'FACTORY', status: 'ACTIVE' }]
}
const product = { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }

type Json = Record<string, unknown>
type Routes = Record<string, (call: RecordedCall) => FakeResponse | Promise<FakeResponse>>

/** 可变的“服务端”：按契约执行状态机与前置条件，页面只能看到服务端返回的状态。 */
function createWorld() {
  const world = {
    batch: {
      id: 101, orgId: 40, productId: 5, traceBatchNo: 'TB-SLICE2-B0', externalBatchNo: 'SRC-2026-001', batchType: 'SOURCE',
      quantity: 1000, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 1
    } as Json,
    events: [{
      id: 900, batchId: 101, orgId: 40, eventType: 'SOURCE', occurredAt: '2026-09-21T08:30:00.000Z', recordedAt: '2026-09-21T08:30:00.000Z',
      dataSource: 'MANUAL', status: 'SUBMITTED', summary: '来源批次激活：东海舟山渔场', detailsJson: { quantity: '1000.000', unitCode: 'kg' }
    }] as Json[],
    transfer: null as Json | null,
    shipment: null as Json | null
  }
  return world
}

function transferView(world: ReturnType<typeof createWorld>): Json | null {
  const t = world.transfer
  if (!t) return null
  const s = world.shipment
  return {
    ...t,
    traceBatchNo: 'TB-SLICE2-B0',
    ...(t.shipmentId && s ? { shipmentNo: s.shipmentNo, shipmentStatus: s.status } : {})
  }
}

function shipmentView(world: ReturnType<typeof createWorld>): Json | null {
  const s = world.shipment
  if (!s) return null
  const t = world.transfer
  const party = (id: number) => orgs.find((o) => o.id === id)
  return {
    ...s,
    senderOrg: party(40),
    receiverOrg: party(30),
    carrierOrg: party(50),
    originSite: sites[40][0],
    destinationSite: sites[30][0],
    transfers: t && t.shipmentId === s.id
      ? [{ transferId: t.id, transferNo: t.transferNo, batchId: 101, traceBatchNo: 'TB-SLICE2-B0', quantity: 1000, unitCode: 'kg', status: t.status, version: t.version }]
      : []
  }
}

function page(items: unknown[]) {
  return envelope(items, { number: 1, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 })
}

function backend(world: ReturnType<typeof createWorld>, user: typeof sourceOperator, overrides: Routes = {}) {
  const ok = (data: unknown, status = 200): FakeResponse => ({ status, body: envelope(data) })
  const routes: Routes = {
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ok(user),
    'GET /api/v1/products/5': () => ok(product),
    'GET /api/v1/organizations': (call) => {
      const type = call.search.get('orgType')
      return ok(type ? orgs.filter((o) => o.orgType === type) : orgs)
    },
    'GET /api/v1/organizations/40': () => ok(orgs[0]),
    'GET /api/v1/organizations/30': () => ok(orgs[1]),
    'GET /api/v1/organizations/50': () => ok(orgs[2]),
    'GET /api/v1/organizations/40/sites': () => ok(sites[40]),
    'GET /api/v1/organizations/30/sites': () => ok(sites[30]),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: page([]) }),
    'GET /api/v1/batches/101': () => (world.batch.orgId === user.orgId
      ? ok(world.batch)
      : problem(403, 'ORG_SCOPE_DENIED', '无权访问其他组织的批次')),
    'GET /api/v1/batches/101/events': () => (world.batch.orgId === user.orgId
      ? ok(world.events)
      : ok(world.events.filter((e) => e.orgId === user.orgId))),
    'GET /api/v1/transfers': (call) => {
      const t = transferView(world)
      if (!t) return { status: 200, body: page([]) }
      const direction = call.search.get('direction')
      const status = call.search.get('status')
      const party = direction === 'SENT' ? t.senderOrgId === user.orgId
        : direction === 'RECEIVED' ? t.receiverOrgId === user.orgId
          : t.senderOrgId === user.orgId || t.receiverOrgId === user.orgId
      const visible = party && (!status || t.status === status)
      return { status: 200, body: page(visible ? [t] : []) }
    },
    'POST /api/v1/transfers': (call) => {
      const body = call.body as Json
      world.transfer = {
        id: 501, transferNo: 'TRF-SLICE2-0001', batchId: body.batchId, senderOrgId: 40, receiverOrgId: body.receiverOrgId,
        quantity: 1000, unitCode: 'kg', status: 'DRAFT', version: 0, createdAt: '2026-09-22T01:00:00Z', updatedAt: '2026-09-22T01:00:00Z'
      }
      return ok(transferView(world), 201)
    },
    'POST /api/v1/transfers/501/submit': () => {
      const t = world.transfer!
      if (!t.shipmentId) return problem(409, 'SHIPMENT_NOT_BOUND', '交接提交为 PENDING 前必须先绑定一个 PLANNED 运输任务')
      if (world.shipment!.status !== 'PLANNED') return problem(409, 'SHIPMENT_NOT_PLANNED', '运输任务已非 PLANNED')
      t.status = 'PENDING'
      t.version = (t.version as number) + 1
      t.submittedRecordedAt = '2026-09-22T01:10:00Z'
      return ok(transferView(world))
    },
    'POST /api/v1/transfers/501/accept': () => {
      if (user.orgId !== 30) return problem(403, 'ORG_SCOPE_DENIED', '仅指定接收企业有权接受货物交接')
      if (world.shipment!.status !== 'DELIVERED') return problem(409, 'SHIPMENT_NOT_DELIVERED', '运输任务尚未到达')
      world.transfer!.status = 'ACCEPTED'
      world.transfer!.version = (world.transfer!.version as number) + 1
      world.batch = { ...world.batch, orgId: 30, version: 2 }
      return ok(transferView(world))
    },
    'POST /api/v1/shipments': () => {
      world.shipment = {
        id: 601, shipmentNo: 'SHP-SLICE2-0001', status: 'PLANNED', vehicleOrContainerNo: '浙L·冷001', version: 0,
        createdAt: '2026-09-22T01:05:00Z', updatedAt: '2026-09-22T01:05:00Z'
      }
      return ok(shipmentView(world), 201)
    },
    'GET /api/v1/shipments': (call) => {
      const s = shipmentView(world)
      const role = call.search.get('role')
      const mine = s && ((role === 'SENDER' && user.orgId === 40) || (role === 'CARRIER' && user.orgId === 50) || (role === 'RECEIVER' && user.orgId === 30))
      return { status: 200, body: page(mine ? [s] : []) }
    },
    'GET /api/v1/shipments/601': () => ([40, 30, 50].includes(user.orgId) ? ok(shipmentView(world)) : problem(403, 'ORG_SCOPE_DENIED')),
    'POST /api/v1/shipments/601/transfers': (call) => {
      const body = call.body as Json
      if (world.shipment!.status !== 'PLANNED') return problem(409, 'SHIPMENT_NOT_PLANNED', '仅 PLANNED 运输任务允许增删交接')
      world.transfer = { ...world.transfer!, shipmentId: 601, version: (body.expectedTransferVersion as number) + 1 }
      world.shipment!.version = (world.shipment!.version as number) + 1
      return ok(shipmentView(world))
    },
    'POST /api/v1/shipments/601/dispatch': (call) => {
      if (user.orgId !== 50) return problem(403, 'ORG_SCOPE_DENIED', '仅指定承运组织可发运')
      if ((call.body as Json).expectedVersion !== world.shipment!.version) return problem(409, 'VERSION_CONFLICT', '运输任务版本已发生变化')
      if (world.transfer!.status !== 'PENDING') return problem(409, 'SHIPMENT_TRANSFER_NOT_PENDING', '全部交接必须已提交')
      world.shipment = { ...world.shipment!, status: 'IN_TRANSIT', loadedAt: (call.body as Json).loadedAt, version: (world.shipment!.version as number) + 1 }
      world.events.push({
        id: 901, batchId: 101, orgId: 40, eventType: 'TRANSPORT', occurredAt: (call.body as Json).loadedAt, recordedAt: '2026-09-22T02:00:00Z',
        dataSource: 'MANUAL', status: 'SUBMITTED', summary: '冷链运输发运：沈家门码头 → 舟山加工厂',
        detailsJson: { sourceObjectType: 'SHIPMENT', shipmentId: 601, shipmentNo: 'SHP-SLICE2-0001', carrierOrgName: '极冷冷链物流', vehicleOrContainerNo: '浙L·冷001', originSiteName: '沈家门码头', destinationSiteName: '舟山加工厂' }
      })
      return ok(shipmentView(world))
    },
    'POST /api/v1/shipments/601/arrive': (call) => {
      if (user.orgId !== 50) return problem(403, 'ORG_SCOPE_DENIED')
      world.shipment = { ...world.shipment!, status: 'DELIVERED', unloadedAt: (call.body as Json).unloadedAt, version: (world.shipment!.version as number) + 1 }
      world.events.push({
        id: 902, batchId: 101, orgId: 40, eventType: 'ARRIVAL', occurredAt: (call.body as Json).unloadedAt, recordedAt: '2026-09-22T05:00:00Z',
        dataSource: 'MANUAL', status: 'SUBMITTED', summary: '冷链运输到达：舟山加工厂',
        detailsJson: { sourceObjectType: 'SHIPMENT', shipmentId: 601, shipmentNo: 'SHP-SLICE2-0001', carrierOrgName: '极冷冷链物流', destinationSiteName: '舟山加工厂' }
      })
      return ok(shipmentView(world))
    },
    ...overrides
  }
  return installFakeFetch(routes)
}

let wrapper: VueWrapper | null = null

async function mountAt(path: string): Promise<{ router: Router; view: VueWrapper }> {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { router, view: wrapper }
}

/** 切换浏览器身份：卸载当前页面并清空会话与 CSRF（模拟另一个浏览器 Profile）。 */
function switchUser() {
  wrapper?.unmount()
  wrapper = null
  resetSessionForTests()
  clearCsrfToken()
}

function writes(calls: RecordedCall[]) {
  return calls.filter((c) => c.method !== 'GET')
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

async function sourceCreatesTransferAndShipment(world: ReturnType<typeof createWorld>) {
  const { calls } = backend(world, sourceOperator)
  const { view, router } = await mountAt('/app/batches/101')

  await view.get('[data-testid="initiate-transfer"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/app/batches/101/transfers/new')
  const receiverOptions = view.findAll('[data-testid="field-receiver"] option').map((o) => o.text())
  expect(receiverOptions.some((t) => t.includes('极冷冷链物流'))).toBe(false)
  await view.get('[data-testid="field-receiver"]').setValue('30')
  await view.get('[data-testid="transfer-create-form"]').trigger('submit')
  await flushPromises()

  expect(router.currentRoute.value.path).toBe('/app/shipments/new')
  expect(router.currentRoute.value.query.transferId).toBe('501')
  expect(view.get('[data-testid="shipment-create-flash"]').text()).toContain('下一步：创建运输任务')
  expect((view.get('[data-testid="select-transfer-501"]').element as HTMLInputElement).checked).toBe(true)
  await view.get('[data-testid="field-carrier"]').setValue('50')
  await view.get('[data-testid="field-vehicle"]').setValue('浙L·冷001')
  await view.get('[data-testid="field-origin-site"]').setValue('401')
  await view.get('[data-testid="field-destination-site"]').setValue('301')
  await view.get('[data-testid="shipment-create-form"]').trigger('submit')
  await flushPromises()

  expect(router.currentRoute.value.path).toBe('/app/shipments/601')
  expect(view.get('[data-testid="shipment-flash"]').text()).toContain('装载 1 张交接')
  expect(view.get('[data-testid="shipment-status"]').attributes('data-status')).toBe('PLANNED')
  expect(view.get('[data-testid="shipment-next-step"]').text()).toContain('提交交接')
  expect(view.find('[data-testid="dispatch-shipment"]').exists()).toBe(false)

  await view.get('[data-testid="submit-transfer-501"]').trigger('click')
  await flushPromises()
  expect(view.get('[data-testid="manifest-transfer-status"]').attributes('data-status')).toBe('PENDING')
  expect(view.get('[data-testid="shipment-next-step"]').text()).toContain('等待承运商确认装载发运')

  const w = writes(calls)
  expect(w.map((c) => `${c.method} ${c.path}`)).toEqual([
    'POST /api/v1/transfers',
    'POST /api/v1/shipments',
    'POST /api/v1/shipments/601/transfers',
    'POST /api/v1/transfers/501/submit'
  ])
  for (const call of w) {
    expect(call.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(call.headers['Idempotency-Key']?.length).toBeGreaterThanOrEqual(16)
  }
  expect(new Set(w.map((c) => c.headers['Idempotency-Key'])).size).toBe(4)
  expect(calls.find((c) => c.path === '/api/v1/shipments' && c.method === 'POST')?.body).toEqual({
    carrierOrgId: 50, originSiteId: 401, destinationSiteId: 301, vehicleOrContainerNo: '浙L·冷001'
  })
  expect(calls.find((c) => c.path === '/api/v1/transfers/501/submit')?.body).toEqual({ expectedVersion: 1 })
}

async function carrierDispatchesAndArrives(world: ReturnType<typeof createWorld>) {
  switchUser()
  const { calls } = backend(world, carrierOperator)
  const { view, router } = await mountAt('/app')
  expect(view.find('[data-testid="entry-carrier-shipments"]').exists()).toBe(true)
  expect(view.find('[data-testid="nav-inbound"]').exists()).toBe(false)
  expect(view.find('[data-testid="entry-new-source-batch"]').exists()).toBe(false)

  await router.push('/app/shipments')
  await flushPromises()
  expect(view.get('[data-testid="shipment-tab-CARRIER"]').attributes('aria-pressed')).toBe('true')
  expect(view.find('[data-testid="new-shipment"]').exists()).toBe(false)
  await view.get('[data-testid="open-shipment-601"]').trigger('click')
  await flushPromises()

  expect(view.get('[data-testid="shipment-next-step"]').text()).toContain('确认装载发运')
  expect(view.find('[data-testid="submit-transfer-501"]').exists()).toBe(false)
  await view.get('[data-testid="dispatch-shipment"]').trigger('click')
  await flushPromises()
  expect(view.get('[data-testid="shipment-status"]').attributes('data-status')).toBe('IN_TRANSIT')
  expect(view.get('[data-testid="shipment-flash"]').text()).toContain('TRANSPORT')

  await view.get('[data-testid="arrive-shipment"]').trigger('click')
  await flushPromises()
  expect(view.get('[data-testid="shipment-status"]').attributes('data-status')).toBe('DELIVERED')
  expect(view.get('[data-testid="shipment-flash"]').text()).toContain('ARRIVAL')
  expect(view.find('[data-testid="carrier-actions"]').exists()).toBe(false)

  const dispatchCall = calls.find((c) => c.path === '/api/v1/shipments/601/dispatch')!
  expect(dispatchCall.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
  expect((dispatchCall.body as Json).expectedVersion).toBe(1)
  expect(typeof (dispatchCall.body as Json).loadedAt).toBe('string')
  // 承运商从未调用接受接口
  expect(calls.some((c) => c.path.endsWith('/accept'))).toBe(false)
}

describe('Slice 2 full chain: source → carrier → processor', () => {
  it('ships B0 through a real shipment and changes the responsible organization only on ACCEPT', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)
    await carrierDispatchesAndArrives(world)

    switchUser()
    const { calls } = backend(world, processorOperator)
    const { view, router } = await mountAt('/app/transfers/inbound')
    const card = view.get('[data-testid="inbound-transfer"]')
    expect(card.get('[data-testid="inbound-trace-batch-no"]').text()).toBe('TB-SLICE2-B0')
    expect(card.get('[data-testid="inbound-shipment-status"]').attributes('data-status')).toBe('DELIVERED')
    // 到达后才出现可操作的接受 / 拒收
    await view.get('[data-testid="accept-transfer-501"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/app/batches/101')
    expect(view.get('[data-testid="batch-flash"]').text()).toContain('当前责任组织')
    expect(view.get('[data-testid="detail-org-name"]').text()).toBe('东海水产加工有限公司')
    expect(view.get('[data-testid="detail-quantity"]').text()).toBe('1,000 kg')
    expect(view.get('[data-testid="detail-flow-status"]').text()).toBe('可流转')
    expect(view.get('[data-testid="detail-risk-status"]').text()).toBe('正常')
    const eventTypes = view.findAll('[data-testid="trace-event"]').map((e) => e.attributes('data-event-type'))
    expect(eventTypes).toEqual(['SOURCE', 'TRANSPORT', 'ARRIVAL'])
    expect(view.text()).toContain('SHP-SLICE2-0001')
    expect(view.text()).toContain('极冷冷链物流')
    expect(view.get('[data-testid="batch-transfer-status"]').attributes('data-status')).toBe('ACCEPTED')

    const acceptCall = calls.find((c) => c.path === '/api/v1/transfers/501/accept')!
    expect(acceptCall.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(acceptCall.headers['Idempotency-Key']?.length).toBeGreaterThanOrEqual(16)
    expect(acceptCall.body).toMatchObject({ receivedQuantity: 1000, unitCode: 'kg', expectedVersion: 2 })
    expect((acceptCall.body as Json).differenceReason).toBeUndefined()

    // 原发货方在批次转出后仍可只读查看本组织参与的历史记录，但不能再查看批次详情或发起交接
    switchUser()
    backend(world, sourceOperator)
    const source = await mountAt('/app/batches/101')
    expect(source.view.find('[data-testid="batch-detail-forbidden"]').exists()).toBe(true)
    expect(source.view.find('[data-testid="initiate-transfer"]').exists()).toBe(false)
    expect(source.view.findAll('[data-testid="history-transfer"]')).toHaveLength(1)
    expect(source.view.findAll('[data-testid="history-event"]').map((e) => e.attributes('data-event-type'))).toEqual(['SOURCE', 'TRANSPORT', 'ARRIVAL'])
  })
})

describe('receiver decisions are gated by the shipment status', () => {
  it('shows a waiting notice instead of ACCEPT / REJECT while the shipment is in transit', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)
    world.shipment = { ...world.shipment!, status: 'IN_TRANSIT', loadedAt: '2026-09-22T02:00:00Z' }

    switchUser()
    backend(world, processorOperator)
    const { view } = await mountAt('/app/transfers/inbound')
    expect(view.get('[data-testid="inbound-waiting"]').text()).toContain('运输中')
    expect(view.find('[data-testid="accept-transfer-501"]').exists()).toBe(false)
    expect(view.find('[data-testid="start-reject-501"]').exists()).toBe(false)
  })

  it('renders the server refusal when ACCEPT is rejected with 409 SHIPMENT_NOT_DELIVERED', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)
    world.shipment = { ...world.shipment!, status: 'DELIVERED' }

    switchUser()
    backend(world, processorOperator, {
      'POST /api/v1/transfers/501/accept': () => problem(409, 'SHIPMENT_NOT_DELIVERED', '运输任务尚未到达')
    })
    const { view, router } = await mountAt('/app/transfers/inbound')
    await view.get('[data-testid="accept-transfer-501"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/app/transfers/inbound')
    expect(view.get('[data-testid="inbound-error-501"]').text()).toContain('运输任务尚未到达')
    expect(world.batch.orgId).toBe(40)
  })

  it('requires a difference reason when the received quantity differs', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)
    world.shipment = { ...world.shipment!, status: 'DELIVERED' }

    switchUser()
    const { calls } = backend(world, processorOperator)
    const { view } = await mountAt('/app/transfers/inbound')
    await view.get('[data-testid="received-quantity-501"]').setValue('998.5')
    await view.get('[data-testid="accept-transfer-501"]').trigger('click')
    await flushPromises()
    expect(view.get('[data-testid="inbound-error-501"]').text()).toContain('差异原因')
    expect(calls.some((c) => c.path.endsWith('/accept'))).toBe(false)
  })
})

describe('shipment detail role separation and server refusals', () => {
  it('reloads the shipment and shows the conflict when dispatch is refused', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)

    switchUser()
    const { calls } = backend(world, carrierOperator, {
      'POST /api/v1/shipments/601/dispatch': () => problem(409, 'VERSION_CONFLICT', '运输任务版本已发生变化（装载清单可能已变更）')
    })
    const { view } = await mountAt('/app/shipments/601')
    const before = calls.filter((c) => c.path === '/api/v1/shipments/601' && c.method === 'GET').length
    await view.get('[data-testid="dispatch-shipment"]').trigger('click')
    await flushPromises()
    expect(view.get('[data-testid="shipment-action-error"]').text()).toContain('装载清单可能已变更')
    expect(calls.filter((c) => c.path === '/api/v1/shipments/601' && c.method === 'GET').length).toBe(before + 1)
    expect(view.get('[data-testid="shipment-status"]').attributes('data-status')).toBe('PLANNED')
  })

  it('disables dispatch until every transfer in the manifest has been submitted', async () => {
    const world = createWorld()
    backend(world, sourceOperator)
    world.transfer = { id: 501, transferNo: 'TRF-SLICE2-0001', batchId: 101, senderOrgId: 40, receiverOrgId: 30, quantity: 1000, unitCode: 'kg', status: 'DRAFT', version: 1, shipmentId: 601, createdAt: 'x', updatedAt: 'x' }
    world.shipment = { id: 601, shipmentNo: 'SHP-SLICE2-0001', status: 'PLANNED', vehicleOrContainerNo: '浙L·冷001', version: 1, createdAt: 'x', updatedAt: 'x' }

    switchUser()
    backend(world, carrierOperator)
    const { view } = await mountAt('/app/shipments/601')
    expect(view.get('[data-testid="dispatch-shipment"]').attributes('disabled')).toBeDefined()
    expect(view.get('[data-testid="shipment-next-step"]').text()).toContain('等待发货方提交全部交接')
  })

  it('shows the receiver only a link to the inbound page, never shipment actions', async () => {
    const world = createWorld()
    await sourceCreatesTransferAndShipment(world)
    world.shipment = { ...world.shipment!, status: 'DELIVERED' }

    switchUser()
    backend(world, processorOperator)
    const { view } = await mountAt('/app/shipments/601')
    expect(view.find('[data-testid="carrier-actions"]').exists()).toBe(false)
    expect(view.find('[data-testid="submit-transfer-501"]').exists()).toBe(false)
    expect(view.get('[data-testid="go-inbound"]').attributes('href')).toBe('/app/transfers/inbound')
  })

  it('blocks the carrier from the shipment creation page', async () => {
    const world = createWorld()
    const { calls } = backend(world, carrierOperator)
    const { view } = await mountAt('/app/shipments/new')
    expect(view.find('[data-testid="shipment-create-forbidden"]').exists()).toBe(true)
    expect(calls.some((c) => c.path === '/api/v1/transfers')).toBe(false)
  })
})
