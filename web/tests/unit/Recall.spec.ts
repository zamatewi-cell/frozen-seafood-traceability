import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import App from '@/App.vue'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canCloseRecall, canStartRecall } from '@/utils/permissions'
import { formatRecallDisposition, formatRecallScopeAction, formatRecallStatus } from '@/utils/formatters'
import type { CurrentUser, Recall } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B PB5：模拟召回（发起、范围展示、关闭）。
 * 发起与关闭都需要二次确认并带 CSRF + Idempotency-Key；范围持有方只读；全部文案为教学演练，不暗示真实法定召回。
 */

const originalFetch = globalThis.fetch

const processorQm = { ...sampleUser, userId: 8, username: 'processor_qm', displayName: '加工质量管理员', roles: ['QUALITY_MANAGER'] }
const retailerQm = { ...sampleUser, userId: 22, username: 'retail_qm', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER',
  roles: ['QUALITY_MANAGER'] }

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function scopeRow(extra: Json): Json {
  return {
    batchId: 21, traceBatchNo: 'TB-B1', productName: '冷冻大黄鱼', scopeRole: 'SEED', depth: 0, holderOrgId: 30,
    flowStatus: 'CLOSED', riskStatusBefore: 'NORMAL', currentFlowStatus: 'CLOSED', currentRiskStatus: 'RECALLED', action: 'RECALLED',
    riskTransitionId: 900, declaredQuantity: 960, remainingQuantity: 0, soldQuantity: 0, unitCode: 'kg', publicCodeActive: false, ...extra
  }
}

function recallBody(extra: Json = {}): Json {
  return {
    id: 7001, recallNo: 'RCL-20260926010000-123456', ownerOrgId: 30, reason: '加工批次检出微生物超标（教学演练）', status: 'IN_PROGRESS',
    startedAt: '2026-09-26T01:00:00.000000Z', startedBy: 8, version: 0,
    summary: { seedCount: 1, descendantCount: 2, ancestorCount: 1, recalledCount: 2, notifiedCount: 1, openTransferCount: 0,
      publicCodeCount: 1, remainingQuantity: 600, soldQuantity: 360 },
    scope: [
      scopeRow({}),
      scopeRow({ batchId: 22, traceBatchNo: 'TB-B2', scopeRole: 'DESCENDANT', depth: 1, flowStatus: 'ACTIVE', currentFlowStatus: 'ACTIVE',
        remainingQuantity: 600, declaredQuantity: 600 }),
      scopeRow({ batchId: 23, traceBatchNo: 'TB-B3', scopeRole: 'DESCENDANT', depth: 1, holderOrgId: 60, action: 'NOTIFY_HOLDER', riskTransitionId: undefined,
        currentRiskStatus: 'NORMAL', declaredQuantity: 360, soldQuantity: 360, publicCodeActive: true }),
      scopeRow({ batchId: 20, traceBatchNo: 'TB-B0', scopeRole: 'ANCESTOR', depth: -1, action: 'TRACE_ONLY', riskTransitionId: undefined,
        currentRiskStatus: 'NORMAL', declaredQuantity: 1000 })
    ],
    ...extra
  }
}

function backend(user: Json, overrides: Overrides = {}) {
  const state = { recall: recallBody() }
  return {
    state,
    ...installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
      'GET /api/v1/recalls': () => ({ status: 200, body: envelope([{ ...state.recall, scope: undefined, summary: undefined }]) }),
      'GET /api/v1/recalls/7001': () => ({ status: 200, body: envelope(state.recall) }),
      'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'O', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
      'GET /api/v1/organizations/60': () => ({ status: 200, body: envelope({ id: 60, orgNo: 'R', name: '鲜到家零售', orgType: 'RETAILER', status: 'ACTIVE' }) }),
      'POST /api/v1/recalls/7001/close': (call) => {
        const body = call.body as Json
        state.recall = { ...state.recall, status: 'CLOSED', publicDisposition: body.publicDisposition, resultSummary: body.resultSummary,
          closedAt: '2026-09-26T03:00:00.000000Z', closedBy: 8, version: 1 }
        return { status: 200, body: envelope(state.recall) }
      },
      ...overrides
    })
  }
}

let wrapper: VueWrapper | null = null

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { view: wrapper, router }
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

describe('recall permissions and wording', () => {
  it('only the current organization QM starts a recall on an effective, not yet recalled batch; only the owner QM closes', () => {
    const qm = processorQm as CurrentUser
    expect(canStartRecall(qm, { orgId: 30, flowStatus: 'ACTIVE', riskStatus: 'FROZEN' })).toBe(true)
    expect(canStartRecall(qm, { orgId: 30, flowStatus: 'CLOSED', riskStatus: 'NORMAL' })).toBe(true)
    expect(canStartRecall(qm, { orgId: 30, flowStatus: 'ACTIVE', riskStatus: 'RECALLED' })).toBe(false)
    expect(canStartRecall(qm, { orgId: 30, flowStatus: 'DRAFT', riskStatus: 'NORMAL' })).toBe(false)
    expect(canStartRecall(qm, { orgId: 60, flowStatus: 'ACTIVE', riskStatus: 'FROZEN' })).toBe(false)
    expect(canStartRecall({ ...qm, roles: ['OPERATOR'] }, { orgId: 30, flowStatus: 'ACTIVE', riskStatus: 'FROZEN' })).toBe(false)
    expect(canStartRecall({ ...qm, scopes: ['PLATFORM'] }, { orgId: 30, flowStatus: 'ACTIVE', riskStatus: 'FROZEN' })).toBe(false)
    const recall = recallBody() as unknown as Recall
    expect(canCloseRecall(qm, recall)).toBe(true)
    expect(canCloseRecall(qm, { ...recall, status: 'CLOSED' })).toBe(false)
    expect(canCloseRecall(retailerQm as CurrentUser, recall)).toBe(false)
    expect(formatRecallStatus('IN_PROGRESS').label).toBe('模拟召回处置中')
    expect(formatRecallScopeAction('NOTIFY_HOLDER')).toContain('由持有方发起召回')
    expect(formatRecallDisposition('DESTROYED')).toBe('按演练流程销毁处置')
  })
})

describe('RecallDetailView', () => {
  it('shows the full scope to the owner and closes after a controlled disposition and a second confirmation', async () => {
    const { calls } = backend(processorQm)
    const { view } = await mountAt('/app/recalls/7001')
    expect(view.get('[data-testid="recall-status"]').attributes('data-status')).toBe('IN_PROGRESS')
    expect(view.get('[data-testid="recall-disclaimer"]').text()).toContain('不代表真实法定召回')
    expect(view.get('[data-testid="recall-summary-counts"]').text()).toContain('模拟召回 2 个批次，通知持有方 1 个批次')
    expect(view.get('[data-testid="recall-summary-quantities"]').text()).toContain('已售 360 kg')
    expect(view.findAll('[data-testid="recall-scope-SEED"] [data-testid="recall-scope-row"]')).toHaveLength(1)
    expect(view.findAll('[data-testid="recall-scope-DESCENDANT"] [data-testid="recall-scope-row"]')).toHaveLength(2)
    const notified = view.get('[data-testid="recall-scope-row"][data-batch-id="23"]')
    expect(notified.attributes('data-action')).toBe('NOTIFY_HOLDER')
    expect(notified.text()).toContain('启用中（消费者可见模拟召回提示）')
    expect(view.get('[data-testid="recall-next-step"]').text()).toContain('已通知持有方')

    await view.get('[data-testid="recall-close-open"]').trigger('click')
    await view.get('[data-testid="recall-close-form"]').trigger('submit')
    expect(view.get('[data-testid="recall-close-error"]').text()).toBe('请选择公开处置结论')
    await view.get('[data-testid="field-recall-disposition"]').setValue('DESTROYED')
    await view.get('[data-testid="field-recall-summary"]').setValue('  库存 600 kg 已按演练流程销毁  ')
    await view.get('[data-testid="recall-close-form"]').trigger('submit')
    expect(view.get('[data-testid="recall-close-confirm-panel"]').text()).toContain('按演练流程销毁处置')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await view.get('[data-testid="recall-close-confirm"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.path).toBe('/api/v1/recalls/7001/close')
    expect(post.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect((post.headers['Idempotency-Key'] || '').length).toBeGreaterThanOrEqual(16)
    expect(post.body).toEqual({ publicDisposition: 'DESTROYED', resultSummary: '库存 600 kg 已按演练流程销毁' })
    expect(view.get('[data-testid="recall-status"]').attributes('data-status')).toBe('CLOSED')
    expect(view.get('[data-testid="recall-disposition"]').text()).toContain('按演练流程销毁处置')
    expect(view.find('[data-testid="recall-close"]').exists()).toBe(false)
  })

  it('shows the holder only its own rows with a hint to start its own recall', async () => {
    backend(retailerQm, {
      'GET /api/v1/recalls/7001': () => ({ status: 200, body: envelope(recallBody({
        viewerRelation: 'CURRENT_HOLDER',
        summary: { seedCount: 0, descendantCount: 1, ancestorCount: 0, recalledCount: 0, notifiedCount: 1, openTransferCount: 0, publicCodeCount: 1,
          remainingQuantity: 0, soldQuantity: 360 },
        scope: [scopeRow({ batchId: 23, traceBatchNo: 'TB-B3', scopeRole: 'DESCENDANT', depth: 1, holderOrgId: 60, action: 'NOTIFY_HOLDER',
          currentRiskStatus: 'NORMAL', soldQuantity: 360, declaredQuantity: 360, heldByViewer: true })]
      })) })
    })
    const { view } = await mountAt('/app/recalls/7001')
    expect(view.findAll('[data-testid="recall-scope-row"]')).toHaveLength(1)
    expect(view.get('[data-testid="recall-next-step"]').text()).toContain('发起本组织的模拟召回')
    expect(view.get('[data-testid="recall-scope-held"]').text()).toBe('本组织当前负责')
    expect(view.find('[data-testid="recall-close"]').exists()).toBe(false)
    expect(view.find('[data-testid="recall-result-summary"]').exists()).toBe(false)
  })

  it('shows a forbidden state for organizations outside the recall scope', async () => {
    backend(retailerQm, { 'GET /api/v1/recalls/7001': () => problem(403, 'ORG_SCOPE_DENIED') })
    const { view } = await mountAt('/app/recalls/7001')
    expect(view.find('[data-testid="recall-forbidden"]').exists()).toBe(true)
  })
})

describe('starting a recall from the batch risk panel', () => {
  it('lets the current QM start a recall after a second confirmation and opens the recall detail', async () => {
    const batch = { id: 21, orgId: 30, productId: 5, traceBatchNo: 'TB-B2', batchType: 'PROCESSING', quantity: 600, remainingQuantity: 600,
      unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海', flowStatus: 'ACTIVE', riskStatus: 'FROZEN', version: 5 }
    const { calls } = backend(processorQm, {
      'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(batch) }),
      'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope([]) }),
      'GET /api/v1/batches/21/sales': () => ({ status: 200, body: envelope([]) }),
      'GET /api/v1/batches/21/public-trace-code': () => problem(404, 'PUBLIC_TRACE_CODE_NOT_FOUND'),
      'GET /api/v1/batches/21/risk-transitions': () => ({ status: 200, body: envelope([]) }),
      'GET /api/v1/batches/21/risk-holds': () => ({ status: 200, body: envelope({
        batchId: 21, riskStatus: 'FROZEN', alertHolds: [], manualFreezeHold: true, recallNotices: []
      }) }),
      'GET /api/v1/batches/21/inspection-reports': () => ({ status: 200, body: envelope([]) }),
      'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
      'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
      'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
      'POST /api/v1/recalls': () => ({ status: 201, body: envelope(recallBody()) })
    })
    const { view, router } = await mountAt('/app/batches/21')
    const panel = view.get('[data-testid="risk-panel"]')
    expect(panel.find('[data-testid="recall-evidence-hint"]').exists()).toBe(false)
    await panel.get('[data-testid="recall-start-open"]').trigger('click')
    await panel.get('[data-testid="recall-start-form"]').trigger('submit')
    expect(panel.get('[data-testid="recall-start-error"]').text()).toBe('请填写召回原因')
    await panel.get('[data-testid="field-recall-reason"]').setValue('检验不合格，启动模拟召回')
    await panel.get('[data-testid="recall-start-form"]').trigger('submit')
    expect(panel.get('[data-testid="recall-start-confirm-panel"]').text()).toContain('不代表真实法定召回')
    await panel.get('[data-testid="recall-start-confirm"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.path).toBe('/api/v1/recalls')
    expect(post.body).toEqual({ batchIds: [21], reason: '检验不合格，启动模拟召回' })
    expect(router.currentRoute.value.path).toBe('/app/recalls/7001')
  })
})
