import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canActivatePublicTraceCode, canManagePublicTraceCode } from '@/utils/permissions'
import type { Batch, CurrentUser } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase A / Slice 6：批次详情公开追溯码面板。
 * 当前责任组织查看当前码；OPERATOR 在 ACTIVE + NORMAL 批次上激活（关闭后不能首次激活）；已激活码在关闭后仍显示消费者入口；
 * 停用为终态且需二次确认；写请求带 CSRF 与 Idempotency-Key，结果未知时重试复用同一键。
 */

const originalFetch = globalThis.fetch

const retailerUser = { ...sampleUser, username: 'retail_op', displayName: '零售操作员', orgId: 40, orgNo: 'ORG_RET_01', orgName: '海港生鲜超市', orgType: 'RETAILER' }

const baseBatch = {
  id: 21, orgId: 40, productId: 5, traceBatchNo: 'TB-B2AAAAAAAAAAAAAAAAAAAAAAAA', batchType: 'PROCESSING',
  quantity: 600, remainingQuantity: 600, unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场',
  flowStatus: 'ACTIVE', riskStatus: 'NORMAL', version: 5
}

const PUBLIC_ID = 'ABCDEF234567ABCDEF234567AB'

function codeRow(status: 'ACTIVE' | 'DISABLED', extra: Record<string, unknown> = {}) {
  return {
    id: 90, batchId: 21, publicId: PUBLIC_ID, status,
    activatedAt: '2026-09-21T08:00:00Z',
    disabledAt: status === 'DISABLED' ? '2026-09-21T09:00:00Z' : null,
    createdAt: '2026-09-21T08:00:00Z', updatedAt: '2026-09-21T08:00:00Z',
    ...extra
  }
}

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>

function backend(options: { user?: Json; batch?: Json; code?: Json | null; overrides?: Overrides } = {}) {
  const state = { batch: { ...baseBatch, ...(options.batch ?? {}) } as Json, code: options.code ?? null as Json | null }
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...retailerUser, ...(options.user ?? {}) }) }),
    'GET /api/v1/batches/21': () => ({ status: 200, body: envelope(state.batch) }),
    'GET /api/v1/batches/21/events': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/batches/21/sales': () => ({ status: 200, body: envelope([]) }),
    'GET /api/v1/products/5': () => ({ status: 200, body: envelope({ id: 5, productCode: 'P', publicName: '冷冻大黄鱼', category: 'FISH', specification: '500g', sourceType: 'DOMESTIC_CAPTURE', baseUnitCode: 'kg', status: 'ACTIVE', version: 0 }) }),
    'GET /api/v1/organizations/40': () => ({ status: 200, body: envelope({ id: 40, orgNo: 'ORG_RET_01', name: '海港生鲜超市', orgType: 'RETAILER', status: 'ACTIVE' }) }),
    'GET /api/v1/transfers': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batch-operations': () => ({ status: 200, body: envelope([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }) }),
    'GET /api/v1/batches/21/public-trace-code': () => state.code
      ? { status: 200, body: envelope(state.code) }
      : problem(404, 'PUBLIC_TRACE_CODE_NOT_FOUND', '该批次尚未激活公开追溯码'),
    'POST /api/v1/batches/21/public-trace-code/activate': () => {
      state.code = state.code ?? codeRow('ACTIVE')
      return { status: 200, body: envelope(state.code) }
    },
    'POST /api/v1/batches/21/public-trace-code/disable': () => {
      state.code = codeRow('DISABLED')
      return { status: 200, body: envelope(state.code) }
    },
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

const panel = (view: VueWrapper) => view.find('[data-testid="public-code-panel"]')

beforeEach(() => resetSessionForTests())
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
  vi.unstubAllGlobals()
})

describe('PublicTraceCode panel', () => {
  it('lets the current responsible OPERATOR activate a code on an ACTIVE + NORMAL batch (CSRF + Idempotency-Key) and then shows the consumer entry', async () => {
    const { calls } = backend()
    const view = await mountDetail()
    expect(panel(view).find('[data-testid="public-code-none"]').exists()).toBe(true)

    await view.find('[data-testid="public-code-activate"]').trigger('click')
    await flushPromises()

    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/public-trace-code/activate')
    expect(post).toBeDefined()
    expect(post!.headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(16)
    expect(post!.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(post!.body).toBeUndefined()
    expect(view.find('[data-testid="public-code-value"]').text()).toBe(PUBLIC_ID)
    expect(view.find('[data-testid="public-code-status"]').attributes('data-status')).toBe('ACTIVE')
    expect(view.find('[data-testid="public-code-link"]').attributes('href')).toBe(`/trace/${PUBLIC_ID}`)
    expect((view.find('[data-testid="public-code-url"]').element as HTMLInputElement).value).toContain(`/trace/${PUBLIC_ID}`)
    expect(view.find('[data-testid="batch-flash"]').text()).toContain('公开追溯码已激活')
    expect(view.find('[data-testid="public-code-activate"]').exists()).toBe(false)
  })

  it('keeps an already ACTIVE code queryable after the batch is CLOSED: code, consumer link and copy are shown, no activation', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const { calls } = backend({ batch: { flowStatus: 'CLOSED', remainingQuantity: 0 }, code: codeRow('ACTIVE') })
    const view = await mountDetail()

    expect(view.find('[data-testid="public-code-value"]').text()).toBe(PUBLIC_ID)
    expect(view.find('[data-testid="public-code-link"]').exists()).toBe(true)
    expect(view.find('[data-testid="public-code-activate"]').exists()).toBe(false)
    await view.find('[data-testid="public-code-copy"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(`/trace/${PUBLIC_ID}`))
    expect(view.find('[data-testid="public-code-copy-message"]').text()).toContain('已复制')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('does not offer first activation on a CLOSED batch without a code (existing lifecycle: 422)', async () => {
    const { calls } = backend({ batch: { flowStatus: 'CLOSED', remainingQuantity: 0 } })
    const view = await mountDetail()
    expect(view.find('[data-testid="public-code-none"]').exists()).toBe(true)
    expect(view.find('[data-testid="public-code-closed-none"]').exists()).toBe(true)
    expect(view.find('[data-testid="public-code-activate"]').exists()).toBe(false)
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('shows a DISABLED code as terminal: no consumer link, no activation, no disable', async () => {
    backend({ code: codeRow('DISABLED') })
    const view = await mountDetail()
    expect(view.find('[data-testid="public-code-status"]').attributes('data-status')).toBe('DISABLED')
    expect(view.find('[data-testid="public-code-disabled-note"]').exists()).toBe(true)
    expect(view.find('[data-testid="public-code-link"]').exists()).toBe(false)
    expect(view.find('[data-testid="public-code-activate"]').exists()).toBe(false)
    expect(view.find('[data-testid="public-code-disable"]').exists()).toBe(false)
  })

  it.each([
    ['non-OPERATOR of the current org', { user: { roles: ['QUALITY_MANAGER'] } }],
    ['PLATFORM scope', { user: { scopes: ['PLATFORM'] } }]
  ])('shows the code read-only to a %s (no activate / disable)', async (_label, options) => {
    backend({ ...(options as { user: Json }), code: codeRow('ACTIVE') })
    const view = await mountDetail()
    expect(view.find('[data-testid="public-code-value"]').text()).toBe(PUBLIC_ID)
    expect(view.find('[data-testid="public-code-disable"]').exists()).toBe(false)
  })

  it('requires an explicit second confirmation before the terminal disable', async () => {
    const { calls } = backend({ code: codeRow('ACTIVE') })
    const view = await mountDetail()

    await view.find('[data-testid="public-code-disable"]').trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    expect(view.find('[data-testid="public-code-disable-confirm-panel"]').text()).toContain('终态')

    await view.find('[data-testid="public-code-disable-cancel"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="public-code-disable-confirm-panel"]').exists()).toBe(false)

    await view.find('[data-testid="public-code-disable"]').trigger('click')
    await view.find('[data-testid="public-code-disable-confirm"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/batches/21/public-trace-code/disable')
    expect(post!.headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(16)
    expect(view.find('[data-testid="public-code-status"]').attributes('data-status')).toBe('DISABLED')
    expect(view.find('[data-testid="public-code-link"]').exists()).toBe(false)
  })

  it('retries an activation with an unknown outcome using the same Idempotency-Key', async () => {
    let attempt = 0
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/public-trace-code/activate': () => {
          attempt += 1
          if (attempt === 1) throw new TypeError('network down')
          return { status: 200, body: envelope(codeRow('ACTIVE')) }
        }
      }
    })
    const view = await mountDetail()
    await view.find('[data-testid="public-code-activate"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="public-code-error"]').exists()).toBe(true)
    await view.find('[data-testid="public-code-activate"]').trigger('click')
    await flushPromises()

    const posts = calls.filter((c) => c.method === 'POST' && c.path.endsWith('/activate'))
    expect(posts).toHaveLength(2)
    expect(posts[1].headers['Idempotency-Key']).toBe(posts[0].headers['Idempotency-Key'])
    expect(view.find('[data-testid="public-code-value"]').text()).toBe(PUBLIC_ID)
  })

  it('shows the server rejection and reloads the current code on 422', async () => {
    const { calls } = backend({
      overrides: {
        'POST /api/v1/batches/21/public-trace-code/activate': () => problem(422, 'BATCH_FLOW_BLOCKED', '批次当前流转或风险状态不允许激活公开追溯码')
      }
    })
    const view = await mountDetail()
    await view.find('[data-testid="public-code-activate"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="public-code-error"]').exists()).toBe(true)
    expect(calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/batches/21/public-trace-code')).toHaveLength(2)
  })

  it('is not rendered for a historical participant (batch detail 403) and never requests the code', async () => {
    const { calls } = backend({
      overrides: {
        'GET /api/v1/batches/21': () => problem(403, 'ORG_SCOPE_DENIED', '无权访问')
      }
    })
    const view = await mountDetail()
    expect(panel(view).exists()).toBe(false)
    expect(calls.some((c) => c.path === '/api/v1/batches/21/public-trace-code')).toBe(false)
  })
})

describe('PublicTraceCode permissions', () => {
  const user = retailerUser as unknown as CurrentUser
  const batch = baseBatch as unknown as Batch

  it('lets only the current responsible org OPERATOR (non-platform) manage the code', () => {
    expect(canManagePublicTraceCode(user, batch)).toBe(true)
    expect(canManagePublicTraceCode({ ...user, orgId: 30 }, batch)).toBe(false)
    expect(canManagePublicTraceCode({ ...user, roles: ['QUALITY_MANAGER'] }, batch)).toBe(false)
    expect(canManagePublicTraceCode({ ...user, scopes: ['PLATFORM'] }, batch)).toBe(false)
    expect(canManagePublicTraceCode(null, batch)).toBe(false)
  })

  it('allows first activation only for ACTIVE + NORMAL batches', () => {
    expect(canActivatePublicTraceCode(user, batch)).toBe(true)
    for (const patch of [{ flowStatus: 'CLOSED' }, { flowStatus: 'DRAFT' }, { riskStatus: 'FROZEN' }, { riskStatus: 'RECALLED' }]) {
      expect(canActivatePublicTraceCode(user, { ...batch, ...patch } as Batch)).toBe(false)
    }
  })
})
