import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, type Router } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 3：加工 / 拆分向导、批次操作详情与批次详情谱系。
 * 有状态的 fetch 替身按契约执行：OUTPUT 由“服务端”生成为 DRAFT，提交时关闭 INPUT、激活 OUTPUT；
 * 页面只能看到服务端返回的状态。仅用于单元测试，真实冒烟不拦截任何请求。
 */

const originalFetch = globalThis.fetch

const processorOperator = {
  userId: 7, username: 'processor_op', displayName: '加工操作员', orgId: 30, orgNo: 'ORG_PRC_01',
  orgName: '东海水产加工有限公司', orgType: 'PROCESSOR', roles: ['OPERATOR'], scopes: ['ORG_ONLY']
}
const processorQm = { ...processorOperator, userId: 8, username: 'processor_qm', roles: ['QUALITY_MANAGER'] }
const sourceOperator = { ...processorOperator, userId: 9, username: 'source_op', orgId: 40, orgNo: 'ORG_SRC_01', orgName: '东海远洋捕捞有限公司', orgType: 'SOURCE' }

const products = [
  { id: 5, productCode: 'P-YELLOW', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g/条', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 },
  { id: 6, productCode: 'P-FILLET', publicName: '大黄鱼鱼片', category: 'FISH', specification: '1kg/袋', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }
]
const organization = { id: 30, orgNo: 'ORG_PRC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }

type Json = Record<string, unknown>
type Routes = Record<string, (call: RecordedCall) => FakeResponse | Promise<FakeResponse>>

function baseBatch(id: number, overrides: Json = {}): Json {
  return {
    id, orgId: 30, productId: 5, traceBatchNo: `TB-${id}`, batchType: 'SOURCE', quantity: 1000, remainingQuantity: 1000,
    unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场', flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 3,
    ...overrides
  }
}

function createWorld() {
  return {
    batches: new Map<number, Json>([[101, baseBatch(101)]]),
    operations: new Map<number, Json>(),
    transfers: [] as Json[],
    events: new Map<number, Json[]>(),
    nextBatchId: 201,
    nextOperationId: 701,
    submitFailure: null as FakeResponse | null
  }
}
type World = ReturnType<typeof createWorld>

function page(items: unknown[]) {
  return envelope(items, { number: 1, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 })
}

function operationView(world: World, op: Json): Json {
  const items = (op.items as Json[]).map((i) => {
    const b = typeof i.batchId === 'number' ? world.batches.get(i.batchId) : undefined
    return b ? { ...i, traceBatchNo: b.traceBatchNo, productId: b.productId, batchType: b.batchType, batchFlowStatus: b.flowStatus, batchRiskStatus: b.riskStatus } : i
  })
  return { ...op, items }
}

function backend(world: World, user: typeof processorOperator, overrides: Routes = {}) {
  const ok = (data: unknown, status = 200): FakeResponse => ({ status, body: envelope(data) })
  const routes: Routes = {
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ok(user),
    'GET /api/v1/products': () => ({ status: 200, body: page(products) }),
    'GET /api/v1/products/5': () => ok(products[0]),
    'GET /api/v1/products/6': () => ok(products[1]),
    'GET /api/v1/organizations/30': () => ok(organization),
    'GET /api/v1/transfers': () => ({ status: 200, body: page(world.transfers) }),
    'GET /api/v1/batch-operations': (call) => {
      const batchId = Number(call.search.get('batchId'))
      const own = [...world.operations.values()]
        .filter((op) => op.orgId === user.orgId && (op.items as Json[]).some((i) => i.batchId === batchId))
        .map((op) => operationView(world, op))
      const batch = world.batches.get(batchId)
      if (batch?.orgId !== user.orgId && own.length === 0) return problem(403, 'ORG_SCOPE_DENIED')
      return { status: 200, body: page(own) }
    },
    'POST /api/v1/batch-operations': (call) => {
      const body = call.body as { operationType: string; items: Json[] }
      const input = body.items.find((i) => i.role === 'INPUT')!
      const b = world.batches.get(Number(input.batchId))!
      if (Number(input.quantity) !== Number(b.remainingQuantity)) return problem(422, 'PARTIAL_INPUT_NOT_ALLOWED', '禁止部分投入')
      const opId = world.nextOperationId++
      const items = body.items.map((i, index) => {
        if (i.role !== 'OUTPUT') return { id: index + 1, operationId: opId, ...i, normalizedQuantity: i.quantity }
        const id = world.nextBatchId++
        world.batches.set(id, baseBatch(id, {
          batchType: body.operationType === 'PROCESS' ? 'PROCESSING' : b.batchType,
          productId: i.productId ?? b.productId,
          quantity: i.quantity, remainingQuantity: i.quantity, flowStatus: 'DRAFT', producedByOperationId: opId, version: 0
        }))
        return { id: index + 1, operationId: opId, role: 'OUTPUT', batchId: id, quantity: i.quantity, unitCode: 'kg', normalizedQuantity: i.quantity }
      })
      const op = { id: opId, orgId: user.orgId, operationNo: `OP-${opId}`, operationType: body.operationType, occurredAt: '2026-09-22T08:00:00.000Z',
        recordedAt: '2026-09-22T08:00:00.000Z', status: 'DRAFT', balanced: true, inputTotal: input.quantity, version: 0,
        createdAt: '2026-09-22T08:00:00.000Z', updatedAt: '2026-09-22T08:00:00.000Z', items, relations: [] }
      world.operations.set(opId, op)
      return ok(operationView(world, op), 201)
    },
    ...overrides
  }
  // 批次（含服务端后来生成的输出批次）按 ID 区间预注册，处理时再读取当前“服务端”状态
  for (let id = 100; id < 230; id++) {
    routes[`GET /api/v1/batches/${id}`] = () => {
      const b = world.batches.get(id)
      if (!b) return problem(404, 'RESOURCE_NOT_FOUND')
      return b.orgId === user.orgId ? ok(b) : problem(403, 'ORG_SCOPE_DENIED')
    }
    routes[`GET /api/v1/batches/${id}/events`] = () => ok(world.events.get(id) ?? [])
  }
  for (let opId = 701; opId < 720; opId++) {
    routes[`GET /api/v1/batch-operations/${opId}`] = () => {
      const op = world.operations.get(opId)
      if (!op) return problem(404, 'RESOURCE_NOT_FOUND')
      return op.orgId === user.orgId ? ok(operationView(world, op)) : problem(403, 'ORG_SCOPE_DENIED')
    }
    routes[`POST /api/v1/batch-operations/${opId}/submit`] = () => {
      if (world.submitFailure) return world.submitFailure
      const op = world.operations.get(opId)!
      const items = op.items as Json[]
      for (const i of items) {
        const b = typeof i.batchId === 'number' ? world.batches.get(i.batchId) : undefined
        if (!b) continue
        if (i.role === 'INPUT') Object.assign(b, { flowStatus: 'CLOSED', remainingQuantity: 0, consumedByOperationId: opId })
        if (i.role === 'OUTPUT') {
          Object.assign(b, { flowStatus: 'ACTIVE' })
          if (op.operationType === 'PROCESS') {
            world.events.set(b.id as number, [{ id: 900 + opId, batchId: b.id, orgId: 30, eventType: 'PROCESS', occurredAt: op.occurredAt,
              recordedAt: op.occurredAt, dataSource: 'MANUAL', status: 'SUBMITTED', summary: '加工产出 960 kg（投入 1000 kg，损耗 30 kg，留样 10 kg）',
              detailsJson: { sourceObjectType: 'BATCH_OPERATION', sourceObjectId: opId, operationNo: op.operationNo, lossQuantity: '30.000', wasteQuantity: '0', sampleQuantity: '10.000' } }])
          }
        }
      }
      Object.assign(op, { status: 'SUBMITTED', version: 1 })
      return ok(operationView(world, op))
    }
    routes[`DELETE /api/v1/batch-operations/${opId}`] = () => {
      const op = world.operations.get(opId)!
      world.operations.delete(opId)
      for (const i of op.items as Json[]) {
        if (i.role === 'OUTPUT' && typeof i.batchId === 'number') world.batches.delete(i.batchId)
      }
      return { status: 204 }
    }
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

describe('BatchDetailView (Slice 3)', () => {
  it('shows remaining quantity and PROCESS / SPLIT entries only to a PROCESSOR operator holding an ACTIVE batch', async () => {
    const world = createWorld()
    backend(world, processorOperator)
    const { view } = await mountAt('/app/batches/101')

    expect(view.get('[data-testid="detail-remaining-quantity"]').text()).toContain('1,000 kg')
    expect(view.find('[data-testid="start-process"]').exists()).toBe(true)
    expect(view.find('[data-testid="start-split"]').exists()).toBe(true)
    expect(view.find('[data-testid="batch-lineage-empty"]').exists()).toBe(true)
  })

  it('hides the entries for a quality manager, when a transfer is open, and for a closed batch', async () => {
    const world = createWorld()
    backend(world, processorQm)
    let mounted = await mountAt('/app/batches/101')
    expect(mounted.view.find('[data-testid="start-process"]').exists()).toBe(false)
    wrapper?.unmount()

    resetSessionForTests()
    world.transfers = [{ id: 501, transferNo: 'TRF-501', batchId: 101, senderOrgId: 30, receiverOrgId: 60, status: 'DRAFT', quantity: 1000, unitCode: 'kg', version: 0 }]
    backend(world, processorOperator)
    mounted = await mountAt('/app/batches/101')
    expect(mounted.view.find('[data-testid="start-process"]').exists()).toBe(false)
    wrapper?.unmount()

    resetSessionForTests()
    world.transfers = []
    Object.assign(world.batches.get(101)!, { flowStatus: 'CLOSED', remainingQuantity: 0, consumedByOperationId: 700 })
    backend(world, processorOperator)
    mounted = await mountAt('/app/batches/101')
    expect(mounted.view.find('[data-testid="start-split"]').exists()).toBe(false)
    expect(mounted.view.get('[data-testid="operation-consumed"]').text()).toContain('全量消耗')
  })

  it('does not offer PROCESS / SPLIT to a SOURCE operator', async () => {
    const world = createWorld()
    world.batches.get(101)!.orgId = 40
    backend(world, sourceOperator)
    const { view } = await mountAt('/app/batches/101')
    expect(view.find('[data-testid="start-process"]').exists()).toBe(false)
  })
})

describe('BatchOperationWizardView', () => {
  it('PROCESS: input fixed to the full remaining quantity, balance enforced, create + submit then lands on the new batch', async () => {
    const world = createWorld()
    const { calls } = backend(world, processorOperator)
    const { view, router } = await mountAt('/app/batches/101')
    await view.get('[data-testid="start-process"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/app/batches/101/operations/new')
    expect(view.get('[data-testid="wizard-title"]').text()).toBe('加工批次')
    expect(view.get('[data-testid="wizard-input-quantity"]').text()).toContain('1,000 kg')
    expect(view.find('[data-testid="wizard-input"] input').exists()).toBe(false)

    await view.get('[data-testid="output-quantity-0"]').setValue('960')
    await view.get('[data-testid="loss-quantity"]').setValue('30')
    expect(view.get('[data-testid="balance-indicator"]').attributes('data-balanced')).toBe('false')
    expect(view.get('[data-testid="balance-indicator"]').text()).toContain('差 10')
    expect(view.get('[data-testid="wizard-submit"]').attributes('disabled')).toBeDefined()

    await view.get('[data-testid="sample-quantity"]').setValue('10')
    expect(view.get('[data-testid="balance-indicator"]').attributes('data-balanced')).toBe('true')
    await view.get('[data-testid="wizard-form"]').trigger('submit')
    await flushPromises()

    const w = writes(calls)
    expect(w.map((c) => `${c.method} ${c.path}`)).toEqual(['POST /api/v1/batch-operations', 'POST /api/v1/batch-operations/701/submit'])
    for (const call of w) {
      expect(call.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
      expect(call.headers['Idempotency-Key']?.length).toBeGreaterThanOrEqual(16)
    }
    expect(w[0].headers['Idempotency-Key']).not.toBe(w[1].headers['Idempotency-Key'])
    const created = w[0].body as { operationType: string; items: Json[] }
    expect(created.operationType).toBe('PROCESS')
    expect(created.items).toEqual([
      { role: 'INPUT', batchId: 101, quantity: 1000, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 960, unitCode: 'kg' },
      { role: 'LOSS', quantity: 30, unitCode: 'kg' },
      { role: 'SAMPLE', quantity: 10, unitCode: 'kg' }
    ])
    expect(w[1].body).toEqual({ version: 0 })

    expect(router.currentRoute.value.path).toBe('/app/batches/201')
    expect(view.get('[data-testid="batch-flash"]').text()).toContain('PROCESS')
    expect(view.get('[data-testid="lineage-produced-by"]').text()).toContain('OP-701')
    expect(view.get('[data-testid="lineage-parent"]').text()).toBe('TB-101')
    expect(view.get('[data-testid="trace-event"]').attributes('data-event-type')).toBe('PROCESS')
    expect(view.get('[data-testid="event-fact-加工单号"]').text()).toBe('OP-701')
  })

  it('SPLIT: needs at least two outputs, has no product choice, and lands on the operation detail', async () => {
    const world = createWorld()
    Object.assign(world.batches.get(101)!, { batchType: 'PROCESSING', quantity: 960, remainingQuantity: 960 })
    const { calls } = backend(world, processorOperator)
    const { view, router } = await mountAt('/app/batches/101/operations/new?type=SPLIT')

    expect(view.get('[data-testid="wizard-title"]').text()).toBe('拆分批次')
    expect(view.findAll('[data-testid="wizard-output-row"]')).toHaveLength(2)
    expect(view.find('[data-testid="output-product-0"]').exists()).toBe(false)
    expect(view.find('[data-testid="remove-output-0"]').exists()).toBe(false)

    await view.get('[data-testid="output-quantity-0"]').setValue('600')
    await view.get('[data-testid="output-quantity-1"]').setValue('360')
    await view.get('[data-testid="wizard-form"]').trigger('submit')
    await flushPromises()

    const created = writes(calls)[0].body as { operationType: string; items: Json[] }
    expect(created.operationType).toBe('SPLIT')
    expect(created.items.filter((i) => i.role === 'OUTPUT')).toEqual([
      { role: 'OUTPUT', quantity: 600, unitCode: 'kg' },
      { role: 'OUTPUT', quantity: 360, unitCode: 'kg' }
    ])
    expect(created.items.every((i) => i.role === 'INPUT' || i.batchId === undefined)).toBe(true)
    expect(router.currentRoute.value.path).toBe('/app/batch-operations/701')
    expect(view.get('[data-testid="operation-flash"]').text()).toContain('不生成包装事件')
    expect(view.get('[data-testid="operation-status"]').attributes('data-status')).toBe('SUBMITTED')
    const flows = view.findAll('[data-testid="operation-item-flow"]').map((b) => b.attributes('data-flow'))
    expect(flows).toEqual(['CLOSED', 'ACTIVE', 'ACTIVE'])
    expect(view.find('[data-testid="operation-submit"]').exists()).toBe(false)
  })

  it('keeps a link to the created draft when the server rejects the submit', async () => {
    const world = createWorld()
    world.submitFailure = problem(409, 'BATCH_TRANSFER_OPEN', '存在未结束交接')
    backend(world, processorOperator)
    const { view, router } = await mountAt('/app/batches/101/operations/new?type=PROCESS')
    await view.get('[data-testid="output-quantity-0"]').setValue('1000')
    await view.get('[data-testid="wizard-form"]').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/app/batches/101/operations/new')
    expect(view.get('[data-testid="wizard-submit-error"]').text()).toContain('存在未结束交接')
    expect(view.get('[data-testid="wizard-draft-link"]').text()).toBe('OP-701')
    expect(view.get('[data-testid="wizard-submit"]').text()).toContain('重新提交草稿')
  })

  it('shows the server refusal for a partial input', async () => {
    const world = createWorld()
    backend(world, processorOperator, {
      'POST /api/v1/batch-operations': () => problem(422, 'PARTIAL_INPUT_NOT_ALLOWED', 'INPUT 必须全量消耗；如需部分加工，请先执行 SPLIT 拆分')
    })
    const { view } = await mountAt('/app/batches/101/operations/new?type=PROCESS')
    await view.get('[data-testid="output-quantity-0"]').setValue('1000')
    await view.get('[data-testid="wizard-form"]').trigger('submit')
    await flushPromises()
    expect(view.get('[data-testid="wizard-submit-error"]').text()).toContain('请先执行 SPLIT')
    expect(view.find('[data-testid="wizard-draft-link"]').exists()).toBe(false)
  })

  it('refuses to open for a batch with an open transfer', async () => {
    const world = createWorld()
    world.transfers = [{ id: 501, transferNo: 'TRF-501', batchId: 101, senderOrgId: 30, receiverOrgId: 60, status: 'PENDING', quantity: 1000, unitCode: 'kg', version: 1 }]
    backend(world, processorOperator)
    const { view } = await mountAt('/app/batches/101/operations/new?type=PROCESS')
    expect(view.get('[data-testid="wizard-open-transfer"]').text()).toContain('TRF-501')
    expect(view.find('[data-testid="wizard-form"]').exists()).toBe(false)
  })
})

describe('BatchOperationDetailView', () => {
  it('lets the owner delete a draft, which removes its output drafts and returns to the input batch', async () => {
    const world = createWorld()
    const { calls } = backend(world, processorOperator)
    const { view, router } = await mountAt('/app/batches/101/operations/new?type=SPLIT')
    world.submitFailure = problem(409, 'VERSION_CONFLICT', '版本冲突')
    await view.get('[data-testid="output-quantity-0"]').setValue('500')
    await view.get('[data-testid="output-quantity-1"]').setValue('500')
    await view.get('[data-testid="wizard-form"]').trigger('submit')
    await flushPromises()
    expect(world.batches.has(201) && world.batches.has(202)).toBe(true)

    await view.get('[data-testid="wizard-draft-link"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/app/batch-operations/701')
    expect(view.get('[data-testid="operation-status"]').attributes('data-status')).toBe('DRAFT')
    await view.get('[data-testid="operation-delete"]').trigger('click')
    await flushPromises()

    const del = calls.find((c) => c.method === 'DELETE')!
    expect(del.path).toBe('/api/v1/batch-operations/701')
    expect(del.search.get('expectedVersion')).toBe('0')
    expect(del.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(router.currentRoute.value.path).toBe('/app/batches/101')
    expect(view.get('[data-testid="batch-flash"]').text()).toContain('已删除')
    expect(world.batches.has(201)).toBe(false)
  })

  it('is read-only for a quality manager and forbidden for another organization', async () => {
    const world = createWorld()
    world.operations.set(701, { id: 701, orgId: 30, operationNo: 'OP-701', operationType: 'PROCESS', occurredAt: '2026-09-22T08:00:00.000Z',
      recordedAt: '2026-09-22T08:00:00.000Z', status: 'DRAFT', balanced: true, version: 0, createdAt: '', updatedAt: '',
      items: [{ id: 1, operationId: 701, role: 'INPUT', batchId: 101, quantity: 1000, unitCode: 'kg', normalizedQuantity: 1000 }], relations: [] })
    backend(world, processorQm)
    let mounted = await mountAt('/app/batch-operations/701')
    expect(mounted.view.get('[data-testid="operation-no"]').text()).toBe('OP-701')
    expect(mounted.view.find('[data-testid="operation-submit"]').exists()).toBe(false)
    expect(mounted.view.find('[data-testid="operation-delete"]').exists()).toBe(false)
    wrapper?.unmount()

    resetSessionForTests()
    backend(world, sourceOperator)
    mounted = await mountAt('/app/batch-operations/701')
    expect(mounted.view.find('[data-testid="operation-forbidden"]').exists()).toBe(true)
  })
})

describe('historical participant (Slice 3)', () => {
  it('lists its own batch operations read-only after the batch has been handed over', async () => {
    const world = createWorld()
    world.batches.get(101)!.orgId = 60
    world.operations.set(701, { id: 701, orgId: 30, operationNo: 'OP-701', operationType: 'SPLIT', occurredAt: '2026-09-22T08:00:00.000Z',
      recordedAt: '2026-09-22T08:00:00.000Z', status: 'SUBMITTED', balanced: true, version: 1, createdAt: '', updatedAt: '',
      items: [{ id: 2, operationId: 701, role: 'OUTPUT', batchId: 101, quantity: 1000, unitCode: 'kg', normalizedQuantity: 1000 }], relations: [] })
    backend(world, processorOperator)
    const { view } = await mountAt('/app/batches/101')
    expect(view.find('[data-testid="batch-detail-forbidden"]').exists()).toBe(true)
    expect(view.get('[data-testid="history-operation"]').text()).toContain('OP-701')
    expect(view.find('[data-testid="start-process"]').exists()).toBe(false)
  })
})
