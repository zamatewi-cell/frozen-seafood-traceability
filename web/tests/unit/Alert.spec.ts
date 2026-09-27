import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import { canAcknowledgeAlert, canHandleAlert } from '@/utils/permissions'
import { formatAlertStatus, formatAlertType, formatDurationSeconds } from '@/utils/formatters'
import type { Alert, CurrentUser } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B PB3：在途持续超温告警（列表、详情、确认异常）与运输任务 / 批次风险历史中的告警入口。
 * 告警只由系统创建；归属组织的质量管理员二次确认后确认异常（CSRF + Idempotency-Key，409 刷新保留原因）；
 * 接收方 / 承运方只读；所有文案为教学演示的模拟质量处置。
 */

const originalFetch = globalThis.fetch

const senderQm = { ...sampleUser, userId: 8, username: 'processor_qm', displayName: '加工质量管理员', roles: ['QUALITY_MANAGER'] }
const receiverOp = { ...sampleUser, userId: 21, username: 'retail_op', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER' }

type Json = Record<string, unknown>

function alertBody(extra: Json = {}): Json {
  return {
    id: 5001, alertNo: 'ALT-20260925011000-123456', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status: 'OPEN',
    reason: '运输任务 SHP-1 在途温度连续高于上限 -15.00 ℃，已持续 1800 秒，达到判定依据允许的连续越界时长 1800 秒，形成持续超温告警',
    orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
    episodeStartRecordId: 701, sustainedRecordId: 702, episodeStartedAt: '2026-09-25T01:10:00.000000Z',
    sustainedAt: '2026-09-25T01:40:00.000000Z', durationSeconds: 1800,
    rule: { ruleId: 51, name: '冷冻大黄鱼运输规则', versionNo: 1, ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 },
    triggeredAt: '2026-09-25T01:40:01.000000Z', version: 0,
    batches: [
      { batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'PENDING', riskStatusBefore: 'NORMAL',
        autoFrozen: true, freezeTransitionId: 9001, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN', quantity: 600, unitCode: 'kg' },
      { batchId: 22, traceBatchNo: 'TB-B3', transferId: 502, transferNo: 'TRF-2', transferStatus: 'PENDING', riskStatusBefore: 'FROZEN',
        autoFrozen: false, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN', quantity: 360, unitCode: 'kg' }
    ],
    actions: [],
    ...extra
  }
}

function backend(options: { user?: Json; alert?: Json; overrides?: Record<string, (call: RecordedCall) => FakeResponse> } = {}) {
  const state = { alert: alertBody(options.alert ?? {}) }
  const handle = installFakeFetch({
    ...CSRF_ROUTE,
    'GET /api/v1/me': () => ({ status: 200, body: envelope({ ...senderQm, ...(options.user ?? {}) }) }),
    'GET /api/v1/alerts': () => ({ status: 200, body: envelope([{ ...state.alert, batches: undefined, actions: undefined }]) }),
    'GET /api/v1/alerts/5001': () => ({ status: 200, body: envelope(state.alert) }),
    'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
    'POST /api/v1/alerts/5001/acknowledge': (call) => {
      state.alert = {
        ...state.alert, status: 'ACKNOWLEDGED', acknowledgedAt: '2026-09-25T02:00:00.000000Z', acknowledgedBy: 8, version: 1,
        actions: [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, note: (call.body as Json).note, occurredAt: '2026-09-25T02:00:00.000000Z' }]
      }
      return { status: 200, body: envelope(state.alert) }
    },
    ...(options.overrides ?? {})
  })
  return { ...handle, state }
}

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
  vi.unstubAllGlobals()
})

describe('alert formatters and permissions', () => {
  it('labels alert types, statuses and durations', () => {
    expect(formatAlertType('TEMP_OVER_UPPER')).toBe('在途持续高于温度上限')
    expect(formatAlertType('TEMP_UNDER_LOWER')).toBe('在途持续低于温度下限')
    expect(formatAlertType('X')).toBe('未知告警类型')
    expect(formatAlertStatus('OPEN')).toMatchObject({ label: '待确认', tone: 'danger' })
    expect(formatAlertStatus('ACKNOWLEDGED').label).toBe('处置中')
    expect(formatAlertStatus('RESOLVED').label).toBe('已处置')
    expect(formatDurationSeconds(0)).toBe('0 秒')
    expect(formatDurationSeconds(1800)).toBe('30 分钟')
    expect(formatDurationSeconds(3725)).toBe('1 小时 2 分 5 秒')
    expect(formatDurationSeconds(null)).toBe('—')
  })

  it('only the owning organization QUALITY_MANAGER (not platform / admin) can handle; acknowledge only while OPEN', () => {
    const alert = alertBody() as unknown as Alert
    const qm = senderQm as CurrentUser
    expect(canHandleAlert(qm, alert)).toBe(true)
    expect(canAcknowledgeAlert(qm, alert)).toBe(true)
    expect(canAcknowledgeAlert(qm, { ...alert, status: 'ACKNOWLEDGED' })).toBe(false)
    expect(canHandleAlert({ ...qm, roles: ['OPERATOR'] }, alert)).toBe(false)
    expect(canHandleAlert({ ...qm, orgId: 60 }, alert)).toBe(false)
    expect(canHandleAlert({ ...qm, scopes: ['PLATFORM'] }, alert)).toBe(false)
    expect(canHandleAlert({ ...qm, roles: ['QUALITY_MANAGER', 'SYSTEM_ADMIN'] }, alert)).toBe(false)
    expect(canHandleAlert(null, alert)).toBe(false)
  })
})

describe('AlertListView', () => {
  it('lists visible alerts with the organization relation and links to the detail and shipment', async () => {
    backend()
    const view = await mountAt('/app/alerts')
    const row = view.find('[data-testid="alert-row"]')
    expect(row.attributes('data-status')).toBe('OPEN')
    expect(row.text()).toContain('ALT-20260925011000-123456')
    expect(row.text()).toContain('本组织负责处置')
    expect(row.text()).toContain('30 分钟')
    expect(view.text()).toContain('单点越界不会形成告警')
    expect(view.find('[data-testid="nav-alerts"]').exists()).toBe(true)
  })

  it('marks alerts of other organizations as read-only for the receiver', async () => {
    backend({ user: receiverOp })
    const view = await mountAt('/app/alerts')
    expect(view.find('[data-testid="alert-row"]').text()).toContain('本组织为接收方（只读）')
  })
})

describe('AlertDetailView', () => {
  it('shows the sustained basis snapshot, affected batches and the simulation disclaimer', async () => {
    backend()
    const view = await mountAt('/app/alerts/5001')
    expect(view.find('[data-testid="alert-status"]').attributes('data-status')).toBe('OPEN')
    expect(view.find('[data-testid="alert-reason"]').text()).toContain('连续高于上限 -15.00')
    expect(view.find('[data-testid="alert-rule"]').text()).toContain('-25.00 ℃ ~ -15.00 ℃')
    expect(view.find('[data-testid="alert-rule"]').text()).toContain('允许连续越界 30 分钟')
    expect(view.find('[data-testid="alert-duration"]').text()).toBe('30 分钟')
    expect(view.find('[data-testid="alert-disclaimer"]').text()).toContain('不代表真实的产品扣留、安全判定或监管措施')
    const rows = view.findAll('[data-testid="alert-batch-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('[data-testid="alert-batch-auto-frozen"]').text()).toContain('系统自动冻结')
    expect(rows[1].find('[data-testid="alert-batch-auto-frozen"]').text()).toContain('告警时已非正常')
    expect(rows[0].attributes('data-risk-status')).toBe('FROZEN')
    expect(view.find('[data-testid="alert-next-step"]').text()).toContain('确认异常')
    expect(view.findAll('[data-testid="alert-history-row"]')).toHaveLength(1)
  })

  it('acknowledges after a second confirmation with CSRF + Idempotency-Key and a note-only body', async () => {
    const { calls } = backend()
    const view = await mountAt('/app/alerts/5001')
    await view.find('[data-testid="alert-ack-open"]').trigger('click')
    await view.find('[data-testid="field-alert-ack-note"]').setValue('  安排复检  ')
    await view.find('[data-testid="alert-ack-form"]').trigger('submit')
    await flushPromises()
    expect(view.find('[data-testid="alert-ack-confirm-panel"]').text()).toContain('处置负责人')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    await view.find('[data-testid="alert-ack-confirm"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/alerts/5001/acknowledge')!
    expect(post.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect((post.headers['Idempotency-Key'] || '').length).toBeGreaterThanOrEqual(16)
    expect(post.body).toEqual({ note: '安排复检' })
    expect(view.find('[data-testid="alert-status"]').attributes('data-status')).toBe('ACKNOWLEDGED')
    expect(view.find('[data-testid="alert-flash"]').text()).toContain('处置负责人')
    expect(view.find('[data-testid="alert-ack-open"]').exists()).toBe(false)
    const history = view.findAll('[data-testid="alert-history-row"]')
    expect(history).toHaveLength(2)
    expect(history[1].text()).toContain('确认异常')
    expect(history[1].find('[data-testid="alert-history-note"]').text()).toBe('说明：安排复检')
  })

  it('reloads and keeps the reason on 409', async () => {
    let first = true
    backend({
      overrides: {
        'POST /api/v1/alerts/5001/acknowledge': () => {
          first = false
          return problem(409, 'INVALID_STATE_TRANSITION', '只有待确认（OPEN）的告警可以确认')
        }
      }
    })
    const view = await mountAt('/app/alerts/5001')
    await view.find('[data-testid="alert-ack-open"]').trigger('click')
    await view.find('[data-testid="alert-ack-form"]').trigger('submit')
    await flushPromises()
    await view.find('[data-testid="alert-ack-confirm"]').trigger('click')
    await flushPromises()
    expect(first).toBe(false)
    expect(view.find('[data-testid="alert-flash"]').text()).toContain('只有待确认（OPEN）的告警可以确认')
    expect(view.find('[data-testid="alert-ack-form"]').exists()).toBe(false)
  })

  it('is read-only for the receiver and for an operator of the owning organization', async () => {
    backend({ user: receiverOp })
    let view = await mountAt('/app/alerts/5001')
    expect(view.find('[data-testid="alert-actions"]').exists()).toBe(false)
    expect(view.find('[data-testid="alert-next-step"]').text()).toContain('等待发货方质量管理员确认异常')
    view.unmount()
    wrapper = null
    backend({ user: { ...senderQm, roles: ['OPERATOR'] } })
    view = await mountAt('/app/alerts/5001')
    expect(view.find('[data-testid="alert-actions"]').exists()).toBe(false)
  })

  it('shows a forbidden state for organizations outside the shipment', async () => {
    backend({ overrides: { 'GET /api/v1/alerts/5001': () => problem(403, 'ORG_SCOPE_DENIED') } })
    const view = await mountAt('/app/alerts/5001')
    expect(view.find('[data-testid="alert-forbidden"]').exists()).toBe(true)
  })
})
