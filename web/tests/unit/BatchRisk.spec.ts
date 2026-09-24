import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canFreezeBatch, canManageBatchRisk, canReleaseBatch } from '@/utils/permissions'
import type { Batch, CurrentUser } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B PB1：批次详情“风险状态”面板。
 * 当前责任组织的质量管理员填写原因、二次确认后风险冻结 / 解除冻结（ACTIVE 与 CLOSED 批次）；其他角色只读查看历史；
 * 写请求只发送原因并带 CSRF 与 Idempotency-Key，结果未知时重试复用同一键；409 刷新并保留原因提示。
 * 消费者 FROZEN 文案为模拟 / 教学实训专用。
 */

const originalFetch = globalThis.fetch

const processorQm = { ...sampleUser, userId: 8, username: 'processor_qm', displayName: '加工质量管理员', roles: ['QUALITY_MANAGER'] }

const baseBatch = {
  id: 21, orgId: 30, productId: 5, traceBatchNo: 'TB-B2AAAAAAAAAAAAAAAAAAAAAAAA', batchType: 'PROCESSING',
  quantity: 600, remainingQuantity: 600, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
  flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 5
}

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function transition(id: number, from: string, to: string, extra: Json = {}) {
  return {
    id, batchId: 21, orgId: 30, flowStatus: 'ACTIVE', fromStatus: from, toStatus: to, sourceType: 'MANUAL',
    reason: to === 'FROZEN' ? '来料抽检异常，等待复检' : '复检合格', actorUserId: 8, occurredAt: '2026-09-23T01:30:15.123456Z',
    ...extra
  }
}

function backend(options: { user?: Json; batch?: Json; history?: Json[]; overrides?: Overrides } = {}) {
  const state = {
    batch: { ...baseBatch, ...(options.batch ?? {}) } as Json,
    history: [...(options.history ?? [])] as Json[]
  }
  const move = (to: 'FROZEN' | 'NORMAL', call: RecordedCall): FakeResponse => {
    const from = state.batch.riskStatus as string
    const row = transition(state.history.length + 100, from, to, { reason: (call.body as Json).reason, flowStatus: state.batch.flowStatus })
    state.history.push(row)
    state.batch = { ...state.batch, riskStatus: to, version: (state.batch.version as number) + 1 }
    return { status: 201, body: envelope(row) }
  }
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...processorQm, ...(options.user ?? {}) }) }),
    'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(state.batch) }),
    'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/sales': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/public-trace-code': () => problem(404, 'PUBLIC_TRACE_CODE_NOT_FOUND', '该批次尚未激活公开追溯码'),
    'GET /api/v1/batches/21/risk-transitions': () => ({ status: 200, body: envelope(state.history) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
    'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'POST /api/v1/batches/21/risk/freeze': (call) => move('FROZEN', call),
    'POST /api/v1/batches/21/risk/release': (call) => move('NORMAL', call),
    ...(options.overrides ?? {})
  })
  return { ...handle, state }
}

let wrapper: VueWrapper | null = null

async function mountDetail() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/app/batches/21')
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const panel = (view: VueWrapper) => view.find('[data-testid="risk-panel"]')

async function fillAndConfirm(view: VueWrapper, openTestId: string, reason: string) {
  await view.find(`[data-testid="${openTestId}"]`).trigger('click')
  await view.find('[data-testid="field-risk-reason"]').setValue(reason)
  await view.find('[data-testid="risk-form"]').trigger('submit')
  await flushPromises()
}

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
  vi.unstubAllGlobals()
})

describe('BatchRiskPanel', () => {
  it('lets the current responsible QUALITY_MANAGER freeze an ACTIVE batch: required reason, second confirmation, CSRF + Idempotency-Key, reason-only body, then reload', async () => {
    const { calls, state } = backend()
    const view = await mountDetail()
    expect(panel(view).find('[data-testid="risk-current"]').attributes('data-status')).toBe('NORMAL')
    expect(view.find('[data-testid="risk-history-empty"]').exists()).toBe(true)
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)

    await fillAndConfirm(view, 'risk-freeze-open', '   ')
    expect(view.find('[data-testid="error-risk-reason"]').text()).toBe('请填写原因')
    expect(view.find('[data-testid="risk-confirm-panel"]').exists()).toBe(false)

    await view.find('[data-testid="field-risk-reason"]').setValue('  来料抽检异常，等待复检  ')
    await view.find('[data-testid="risk-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="risk-confirm-panel"]').text()).toContain('数量、当前责任组织与流转状态保持不变')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    await view.find('[data-testid="risk-confirm"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/risk/freeze')!
    expect(post.body).toEqual({ reason: '来料抽检异常，等待复检' })
    expect(post.headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(16)
    expect(post.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(state.batch.riskStatus).toBe('FROZEN')
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21')).toHaveLength(2)
    expect(view.find('[data-testid="batch-flash"]').text()).toContain('已风险冻结（模拟质量处置）')
    expect(panel(view).find('[data-testid="risk-current"]').attributes('data-status')).toBe('FROZEN')
    expect(view.find('[data-testid="risk-frozen-effects"]').text()).toContain('终端销售')
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(true)
    expect(view.find('[data-testid="risk-freeze-open"]').exists()).toBe(false)
    const rows = view.findAll('[data-testid="risk-transition-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].attributes('data-to-status')).toBe('FROZEN')
    expect(rows[0].find('[data-testid="risk-transition-reason"]').text()).toContain('来料抽检异常，等待复检')
  })

  it('releases a CLOSED + FROZEN batch (flow stays CLOSED) and shows the release in the history', async () => {
    const { calls, state } = backend({
      batch: { flowStatus: 'CLOSED', riskStatus: 'FROZEN', remainingQuantity: 0 },
      history: [transition(1, 'NORMAL', 'FROZEN', { flowStatus: 'CLOSED' })]
    })
    const view = await mountDetail()
    expect(view.find('[data-testid="risk-freeze-open"]').exists()).toBe(false)
    await fillAndConfirm(view, 'risk-release-open', '调查结束')
    expect(view.find('[data-testid="risk-confirm-panel"]').text()).toContain('流转状态仍保持已关闭')
    await view.find('[data-testid="risk-confirm"]').trigger('click')
    await flushPromises()

    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/risk/release')).toBe(true)
    expect(state.batch).toMatchObject({ flowStatus: 'CLOSED', riskStatus: 'NORMAL' })
    expect(view.find('[data-testid="batch-flash"]').text()).toContain('已解除冻结')
    const rows = view.findAll('[data-testid="risk-transition-row"]')
    expect(rows.map((r) => r.attributes('data-to-status'))).toEqual(['FROZEN', 'NORMAL'])
    expect(rows[1].attributes('data-flow-status')).toBe('CLOSED')
  })

  it('lets the QUALITY_MANAGER go back from the confirmation to edit the reason, and cancel without any write', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    await fillAndConfirm(view, 'risk-freeze-open', '抽检')
    await view.find('[data-testid="risk-back"]').trigger('click')
    expect(view.find('[data-testid="risk-confirm-panel"]').exists()).toBe(false)
    expect((view.find('[data-testid="field-risk-reason"]').element as HTMLTextAreaElement).disabled).toBe(false)
    await view.find('[data-testid="risk-cancel"]').trigger('click')
    expect(view.find('[data-testid="risk-form"]').exists()).toBe(false)
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('retries a freeze with an unknown outcome using the same Idempotency-Key and reason', async () => {
    let attempt = 0
    const { calls, state } = backend({
      overrides: {
        'POST /api/v1/batches/21/risk/freeze': (call) => {
          attempt += 1
          if (attempt === 1) throw new TypeError('network down')
          state.batch = { ...state.batch, riskStatus: 'FROZEN', version: 6 }
          state.history.push(transition(9, 'NORMAL', 'FROZEN', { reason: (call.body as Json).reason }))
          return { status: 201, body: envelope(state.history[0]) }
        }
      }
    })
    const view = await mountDetail()
    await fillAndConfirm(view, 'risk-freeze-open', '抽检')
    await view.find('[data-testid="risk-confirm"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="risk-error"]').text()).toContain('结果未知')
    await view.find('[data-testid="risk-confirm"]').trigger('click')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(posts[1].body).toEqual(posts[0].body)
    expect(panel(view).find('[data-testid="risk-current"]').attributes('data-status')).toBe('FROZEN')
  })

  it('reloads on 409 and keeps the server reason visible as a warning', async () => {
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/risk/freeze': () => problem(409, 'INVALID_STATE_TRANSITION', '批次已处于风险冻结状态 (riskStatus=FROZEN)')
      }
    })
    const view = await mountDetail()
    await fillAndConfirm(view, 'risk-freeze-open', '抽检')
    await view.find('[data-testid="risk-confirm"]').trigger('click')
    await flushPromises()
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21')).toHaveLength(2)
    const flash = view.find('[data-testid="batch-flash"]')
    expect(flash.classes()).toContain('warning')
    expect(flash.text()).toContain('批次已处于风险冻结状态')
  })

  it.each([
    ['an OPERATOR of the current org', { user: { roles: ['OPERATOR'] } }],
    ['a platform administrator', { user: { roles: ['SYSTEM_ADMIN'], scopes: ['PLATFORM'] } }],
    ['a RECALLED batch (terminal)', { batch: { riskStatus: 'RECALLED' } }]
  ])('is read-only for %s: history visible, no freeze / release entry', async (_label, options) => {
    backend({ ...options, history: [transition(1, 'NORMAL', 'FROZEN'), transition(2, 'FROZEN', 'NORMAL')] })
    const view = await mountDetail()
    expect(panel(view).exists()).toBe(true)
    expect(view.findAll('[data-testid="risk-transition-row"]')).toHaveLength(2)
    expect(view.find('[data-testid="risk-freeze-open"]').exists()).toBe(false)
    expect(view.find('[data-testid="risk-release-open"]').exists()).toBe(false)
  })

  it('is not rendered for a DRAFT batch and never requests the risk history', async () => {
    const { calls } = backend({ batch: { flowStatus: 'DRAFT' } })
    const view = await mountDetail()
    expect(panel(view).exists()).toBe(false)
    expect(calls.some((c) => c.path.endsWith('/risk-transitions'))).toBe(false)
  })

  it('degrades gracefully when the history is forbidden or fails', async () => {
    backend({ overrides: { 'GET /api/v1/batches/21/risk-transitions': () => problem(403, 'ORG_SCOPE_DENIED', '无权查看') } })
    const view = await mountDetail()
    expect(view.find('[data-testid="risk-history-forbidden"]').exists()).toBe(true)
    wrapper?.unmount()
    wrapper = null

    let fail = true
    backend({ overrides: { 'GET /api/v1/batches/21/risk-transitions': () => (fail ? problem(500, 'INTERNAL_ERROR') : { status: 200, body: envelope([]) }) } })
    const view2 = await mountDetail()
    expect(view2.find('[data-testid="risk-history-error"]').exists()).toBe(true)
    fail = false
    await view2.find('[data-testid="risk-history-retry"]').trigger('click')
    await flushPromises()
    expect(view2.find('[data-testid="risk-history-empty"]').exists()).toBe(true)
  })
})

describe('batch risk permissions', () => {
  const qm = processorQm as CurrentUser
  const batch = baseBatch as unknown as Batch

  it('allows only the current responsible org QUALITY_MANAGER (non-platform) on ACTIVE or CLOSED batches', () => {
    expect(canManageBatchRisk(qm, batch)).toBe(true)
    expect(canManageBatchRisk(qm, { ...batch, flowStatus: 'CLOSED' })).toBe(true)
    expect(canManageBatchRisk(qm, { ...batch, flowStatus: 'DRAFT' })).toBe(false)
    expect(canManageBatchRisk({ ...qm, roles: ['OPERATOR'] }, batch)).toBe(false)
    expect(canManageBatchRisk({ ...qm, orgId: 40 }, batch)).toBe(false)
    expect(canManageBatchRisk({ ...qm, scopes: ['PLATFORM'] }, batch)).toBe(false)
    expect(canManageBatchRisk({ ...qm, roles: ['QUALITY_MANAGER', 'SYSTEM_ADMIN'] }, batch)).toBe(false)
    expect(canManageBatchRisk(null, batch)).toBe(false)
  })

  it('freezes only NORMAL and releases only FROZEN; RECALLED is terminal', () => {
    expect(canFreezeBatch(qm, batch)).toBe(true)
    expect(canReleaseBatch(qm, batch)).toBe(false)
    expect(canFreezeBatch(qm, { ...batch, riskStatus: 'FROZEN' })).toBe(false)
    expect(canReleaseBatch(qm, { ...batch, riskStatus: 'FROZEN' })).toBe(true)
    expect(canFreezeBatch(qm, { ...batch, riskStatus: 'RECALLED' })).toBe(false)
    expect(canReleaseBatch(qm, { ...batch, riskStatus: 'RECALLED' })).toBe(false)
  })
})
