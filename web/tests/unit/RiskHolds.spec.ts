import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { CSRF_ROUTE, envelope, installFakeFetch, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B 独立评审修复：风险事项与召回通知属于批次。
 * - 同一批次多个风险事项：某个告警的放行结论只解除它自己的事项，批次仍冻结时告警详情说明仍未解除的事项；
 * - 批次风险面板（当前责任组织）显示仍未解除的告警风险事项、人工风险冻结与上游召回通知，仍有未处置告警时隐藏人工解除入口；
 * - 召回通知随批次交接：当前责任组织看到本组织负责的范围行，发起时的持有方只看到发起时快照。
 */

const originalFetch = globalThis.fetch

const processorQm = { ...sampleUser, userId: 8, username: 'processor_qm', displayName: '加工质量管理员', roles: ['QUALITY_MANAGER'] }
const retailerQm = { ...sampleUser, userId: 22, username: 'retail_qm', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER',
  roles: ['QUALITY_MANAGER'] }

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

const org = (id: number, name: string, orgType: string) => ({ status: 200, body: envelope({ id, orgNo: `ORG-${id}`, name, orgType, status: 'ACTIVE' }) })

let wrapper: VueWrapper | null = null

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
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
  vi.restoreAllMocks()
})

// =========================================================================
// 批次风险面板：未解除的风险事项与上游召回通知
// =========================================================================

function batchBackend(user: Json, batch: Json, holds: Json | null, overrides: Overrides = {}) {
  return installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
    'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(batch) }),
    'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/sales': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/public-trace-code': () => ({ status: 404, body: { status: 404, code: 'PUBLIC_TRACE_CODE_NOT_FOUND', title: '未找到' } }),
    'GET /api/v1/batches/21/risk-transitions': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/risk-holds': () => (holds ? { status: 200, body: envelope(holds) } : { status: 403, body: { status: 403, code: 'ORG_SCOPE_DENIED', title: '越权' } }),
    'GET /api/v1/batches/21/inspection-reports': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
    'GET /api/v1/organizations/30': () => org(30, '东海水产加工有限公司', 'PROCESSOR'),
    'GET /api/v1/organizations/40': () => org(40, '上游来源企业', 'SOURCE'),
    'GET /api/v1/organizations/60': () => org(60, '鲜到家零售', 'RETAILER'),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    ...overrides
  })
}

const batch = (extra: Json = {}) => ({
  id: 21, orgId: 30, productId: 5, traceBatchNo: 'TB-B2', batchType: 'PROCESSING', quantity: 600, remainingQuantity: 600, unitCode: 'kg',
  originType: 'DOMESTIC_CAPTURE', originText: '东海', flowStatus: 'ACTIVE', riskStatus: 'FROZEN', version: 5, ...extra
})

describe('BatchRiskPanel risk holds (review fix)', () => {
  it('lists an open alert hold and the manual freeze, and hides manual release until the alert decision exists', async () => {
    batchBackend(processorQm, batch(), {
      batchId: 21, riskStatus: 'FROZEN', alertHolds: [{ alertId: 5002, alertNo: 'ALT-2', alertStatus: 'ACKNOWLEDGED' }],
      manualFreezeHold: true, recallNotices: []
    })
    const view = await mountAt('/app/batches/21')
    const holds = view.get('[data-testid="risk-holds"]')
    expect(holds.get('[data-testid="risk-hold-alert"]').text()).toContain('ALT-2')
    expect(holds.get('[data-testid="risk-hold-alert"] a').attributes('href')).toBe('/app/alerts/5002')
    expect(holds.get('[data-testid="risk-hold-manual"]').text()).toContain('告警结论全部形成后')
    expect(holds.text()).toContain('任何一个事项的结论都不会单独解除其他事项')
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)
  })

  it('offers manual release when only the manual freeze remains', async () => {
    batchBackend(processorQm, batch(), { batchId: 21, riskStatus: 'FROZEN', alertHolds: [], manualFreezeHold: true, recallNotices: [] })
    const view = await mountAt('/app/batches/21')
    expect(view.get('[data-testid="risk-hold-manual"]').text()).toContain('人工解除')
    expect(view.find('[data-testid="risk-hold-alert"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(true)
  })

  it('does not offer manual release while the risk holds cannot be read, and offers it after a successful retry', async () => {
    let failures = 1
    const holds = { batchId: 21, riskStatus: 'FROZEN', alertHolds: [], manualFreezeHold: true, recallNotices: [] }
    const { calls } = batchBackend(processorQm, batch(), holds, {
      'GET /api/v1/batches/21/risk-holds': () => (failures-- > 0
        ? { status: 500, body: { status: 500, code: 'INTERNAL_ERROR', title: '服务器错误' } }
        : { status: 200, body: envelope(holds) })
    })
    const view = await mountAt('/app/batches/21')
    expect(view.get('[data-testid="risk-holds-error"]').text()).toContain('确认没有未处置告警前暂不提供人工解除冻结')
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-history-empty"]').exists()).toBe(true)

    await view.get('[data-testid="risk-holds-retry"]').trigger('click')
    await flushPromises()
    expect(calls.filter((c) => c.path.endsWith('/risk-holds'))).toHaveLength(2)
    expect(view.find('[data-testid="risk-holds-error"]').exists()).toBe(false)
    expect(view.get('[data-testid="risk-hold-manual"]').text()).toContain('人工解除')
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(true)
  })

  it('does not offer manual release or show holds when the holds answer belongs to another batch', async () => {
    batchBackend(processorQm, batch(), { batchId: 22, riskStatus: 'FROZEN', alertHolds: [], manualFreezeHold: true, recallNotices: [] })
    const view = await mountAt('/app/batches/21')
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-holds"]').exists()).toBe(false)
  })

  it.each([
    ['the holds read fails', { status: 500, body: { status: 500, code: 'INTERNAL_ERROR', title: '服务器错误' } }],
    ['an alert hold appears', { status: 200, body: envelope({ batchId: 21, riskStatus: 'FROZEN',
      alertHolds: [{ alertId: 5002, alertNo: 'ALT-2', alertStatus: 'OPEN' }], manualFreezeHold: true, recallNotices: [] }) }]
  ])('closes an open manual release form and sends nothing when, after a reload, %s', async (_case, second) => {
    let historyFailures = 1
    let holdsCalls = 0
    const { calls } = batchBackend(processorQm, batch(), null, {
      'GET /api/v1/batches/21/risk-transitions': () => (historyFailures-- > 0
        ? { status: 500, body: { status: 500, code: 'INTERNAL_ERROR', title: '服务器错误' } }
        : { status: 200, body: envelope([]) }),
      'GET /api/v1/batches/21/risk-holds': () => (holdsCalls++ === 0
        ? { status: 200, body: envelope({ batchId: 21, riskStatus: 'FROZEN', alertHolds: [], manualFreezeHold: true, recallNotices: [] }) }
        : second)
    })
    const view = await mountAt('/app/batches/21')
    await view.get('[data-testid="risk-release-open"]').trigger('click')
    await view.get('[data-testid="field-risk-reason"]').setValue('复检合格')
    await view.get('[data-testid="risk-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="risk-confirm"]').exists()).toBe(true)

    await view.get('[data-testid="risk-history-retry"]').trigger('click')
    await flushPromises()
    expect(holdsCalls).toBe(2)
    expect(view.find('[data-testid="risk-form"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-confirm"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)
    expect(calls.some((c) => c.method === 'POST' && c.path.endsWith('/risk/release'))).toBe(false)
  })

  it('shows upstream recall notices to the current responsible organization with the next step', async () => {
    batchBackend(processorQm, batch({ riskStatus: 'NORMAL' }), {
      batchId: 21, riskStatus: 'NORMAL', alertHolds: [], manualFreezeHold: false,
      recallNotices: [{ recallId: 7001, recallNo: 'RCL-1', recallStatus: 'IN_PROGRESS', ownerOrgId: 40, notifiedAt: '2026-09-27T01:00:00.000000Z', depth: 1 }]
    })
    const view = await mountAt('/app/batches/21')
    const notices = view.get('[data-testid="risk-recall-notices"]')
    expect(notices.get('[data-testid="risk-recall-notice"] a').attributes('href')).toBe('/app/recalls/7001')
    expect(notices.text()).toContain('上游来源企业')
    expect(notices.text()).toContain('以上游召回为证据发起本组织的模拟召回')
    expect(view.find('[data-testid="risk-holds"]').exists()).toBe(false)
  })

  it('does not request batch risk holds for a historical participant', async () => {
    const { calls } = batchBackend(retailerQm, batch({ riskStatus: 'NORMAL' }), null)
    const view = await mountAt('/app/batches/21')
    expect(calls.some((c) => c.path.endsWith('/risk-holds'))).toBe(false)
    expect(view.find('[data-testid="risk-recall-notices"]').exists()).toBe(false)
  })
})

// =========================================================================
// 告警详情：放行结论已记录、批次仍被其他风险事项冻结
// =========================================================================

function alertBody(batchExtra: Json = {}): Json {
  return {
    id: 5001, alertNo: 'ALT-1', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status: 'ACKNOWLEDGED', reason: '持续超温',
    orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
    episodeStartRecordId: 1, sustainedRecordId: 1, episodeStartedAt: '2026-09-27T01:10:00Z', sustainedAt: '2026-09-27T01:10:00Z',
    durationSeconds: 0, rule: { ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 0 },
    triggeredAt: '2026-09-27T01:10:01Z', acknowledgedAt: '2026-09-27T02:00:00Z', acknowledgedBy: 8, version: 1,
    batches: [{ batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'PENDING', riskStatusBefore: 'NORMAL',
      autoFrozen: true, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN', quantity: 600, unitCode: 'kg',
      released: false, latestInspectionConclusion: 'PASS', inspectionCount: 1, ...batchExtra }],
    actions: [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, occurredAt: '2026-09-27T02:00:00Z' }]
  }
}

describe('AlertDetailView with other open holds (review fix)', () => {
  it('records this alert decision but explains that the batch is still held by another alert and the manual freeze', async () => {
    const state = { alert: alertBody() }
    const { calls } = installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/me': () => ({ status: 200, body: envelope(processorQm) }),
      'GET /api/v1/alerts/5001': () => ({ status: 200, body: envelope(state.alert) }),
      'GET /api/v1/organizations/30': () => org(30, '东海水产加工有限公司', 'PROCESSOR'),
      'GET /api/v1/batches/21/inspection-reports': () => ({ status: 200, body: envelope([
        { id: 1, batchId: 21, orgId: 30, submitterRole: 'CURRENT_ORG', alertId: 5001, reportNo: 'R-1', institutionName: '中心',
          inspectedAt: '2026-09-27T02:30:00Z', itemsSummary: '感官', conclusion: 'PASS', dataSource: 'SIMULATED', actorUserId: 8,
          recordedAt: '2026-09-27T02:31:00Z' }
      ]) }),
      'POST /api/v1/alerts/5001/batches/21/release': () => {
        state.alert = alertBody({ released: true, pendingHolds: [{ type: 'ALERT', alertId: 5002, alertNo: 'ALT-2' }, { type: 'MANUAL_FREEZE' }] })
        return { status: 200, body: envelope(state.alert) }
      }
    })
    const view = await mountAt('/app/alerts/5001')
    await view.get('[data-testid="alert-release-open-21"]').trigger('click')
    await view.get('[data-testid="alert-release-next"]').trigger('click')
    await view.get('[data-testid="alert-release-confirm"]').trigger('click')
    await flushPromises()
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/api/v1/alerts/5001/batches/21/release'])
    expect(view.get('[data-testid="alert-flash"]').text()).toContain('批次仍被告警 ALT-2、人工风险冻结冻结')
    expect(view.get('[data-testid="alert-flash"]').classes()).toContain('warning')
    expect(view.get('[data-testid="alert-batch-disposition"]').text()).toBe('已记录本告警的放行结论；批次仍被告警 ALT-2、人工风险冻结冻结')
    const pending = view.get('[data-testid="alert-batch-pending-holds"]')
    expect(pending.get('[data-testid="alert-batch-pending-alert"]').attributes('href')).toBe('/app/alerts/5002')
    expect(pending.get('[data-testid="alert-batch-pending-manual"]').text()).toContain('人工解除')
    expect(view.find('[data-testid="alert-release-open-21"]').exists()).toBe(false)
  })
})

// =========================================================================
// 召回通知随批次：当前责任组织与发起时的持有方
// =========================================================================

function recallBody(viewerRelation: string, row: Json): Json {
  return {
    id: 7001, recallNo: 'RCL-1', ownerOrgId: 30, reason: '上游检验不合格（教学演练）', status: 'IN_PROGRESS',
    startedAt: '2026-09-27T01:00:00.000000Z', startedBy: 8, version: 0, viewerRelation,
    summary: { seedCount: 0, descendantCount: 1, ancestorCount: 0, recalledCount: 0, notifiedCount: 1, openTransferCount: 0, publicCodeCount: 0,
      remainingQuantity: 480, soldQuantity: 0 },
    scope: [{ batchId: 23, traceBatchNo: 'TB-C', productName: '冷冻大黄鱼段', scopeRole: 'DESCENDANT', depth: 1, holderOrgId: 60, flowStatus: 'ACTIVE',
      riskStatusBefore: 'NORMAL', action: 'NOTIFY_HOLDER', declaredQuantity: 480, remainingQuantity: 480, soldQuantity: 0, unitCode: 'kg',
      publicCodeActive: false, ...row }]
  }
}

function recallBackend(user: Json, body: Json, list: Json[] = []) {
  return installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
    'GET /api/v1/recalls': () => ({ status: 200, body: envelope(list) }),
    'GET /api/v1/recalls/7001': () => ({ status: 200, body: envelope(body) }),
    'GET /api/v1/organizations/30': () => org(30, '东海水产加工有限公司', 'PROCESSOR'),
    'GET /api/v1/organizations/60': () => org(60, '鲜到家零售', 'RETAILER')
  })
}

describe('recall notices follow the batch (review fix)', () => {
  it('shows a former holder only the recall-time snapshot, without the new holder current state or a batch link', async () => {
    recallBackend(retailerQm, recallBody('HISTORICAL_HOLDER', { heldByViewer: false }))
    const view = await mountAt('/app/recalls/7001')
    const row = view.get('[data-testid="recall-scope-row"]')
    expect(row.attributes('data-held-by-viewer')).toBe('false')
    expect(row.attributes('data-risk-status')).toBeUndefined()
    expect(row.get('[data-testid="recall-scope-current-hidden"]').text()).toContain('只显示发起时快照')
    expect(row.find('a').exists()).toBe(false)
    expect(view.get('[data-testid="recall-next-step"]').text()).toContain('召回通知由批次的当前责任组织处置')
  })

  it('shows the current holder its batch with the current state, a batch link and the next step', async () => {
    recallBackend({ ...retailerQm, orgId: 70, orgNo: 'ORG_RET_02', orgName: '海港生鲜' },
      recallBody('CURRENT_HOLDER', { heldByViewer: true, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN' }))
    const view = await mountAt('/app/recalls/7001')
    const row = view.get('[data-testid="recall-scope-row"]')
    expect(row.attributes('data-held-by-viewer')).toBe('true')
    expect(row.attributes('data-risk-status')).toBe('FROZEN')
    expect(row.get('a').attributes('href')).toBe('/app/batches/23')
    expect(row.get('[data-testid="recall-scope-held"]').text()).toBe('本组织当前负责')
    expect(view.get('[data-testid="recall-next-step"]').text()).toContain('本组织当前负责的后续批次已被上游模拟召回圈定')
  })

  it('labels the relation of every listed recall', async () => {
    const base = { recallNo: 'RCL', ownerOrgId: 30, reason: '演练', status: 'IN_PROGRESS', startedAt: '2026-09-27T01:00:00Z', startedBy: 8, version: 0 }
    recallBackend(retailerQm, recallBody('CURRENT_HOLDER', {}), [
      { ...base, id: 1, viewerRelation: 'CURRENT_HOLDER' },
      { ...base, id: 2, viewerRelation: 'HISTORICAL_HOLDER' }
    ])
    const view = await mountAt('/app/recalls')
    const labels = view.findAll('[data-testid="recall-relation"]').map((c) => c.text())
    expect(labels).toEqual(['本组织当前负责范围批次', '本组织曾持有范围批次（历史快照，只读）'])
  })
})
