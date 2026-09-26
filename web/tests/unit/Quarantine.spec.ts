import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { resetSessionForTests } from '@/stores/session'
import {
  canAcceptTransfer,
  canQuarantineTransfer,
  canReleaseAlertBatch,
  canResolveAlert,
  canSubmitInspection
} from '@/utils/permissions'
import { formatInspectionConclusion, formatTransferStatus } from '@/utils/formatters'
import type { Alert, AlertAffectedBatch, CurrentUser, Transfer } from '@/types/enterprise'
import { CSRF_ROUTE, envelope, installFakeFetch, problem, sampleUser, type FakeResponse, type RecordedCall } from './helpers/fakeFetch'

/**
 * Phase B PB4：隔离收货（待接收交接）、检验证据与告警质量结论（放行、处置结论）。
 * 冻结批次不能直接接受，只能隔离收货或拒收；隔离后等待发货方依据检验结论放行；报告只是证据；
 * 放行与处置结论都需要二次确认并带 CSRF + Idempotency-Key。
 */

const originalFetch = globalThis.fetch

const receiverOp = { ...sampleUser, userId: 21, username: 'retail_op', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER' }
const receiverQm = { ...receiverOp, userId: 22, username: 'retail_qm', roles: ['QUALITY_MANAGER'] }
const senderQm = { ...sampleUser, userId: 8, username: 'processor_qm', roles: ['QUALITY_MANAGER'] }

type Json = Record<string, unknown>
type Overrides = Record<string, (call: RecordedCall) => FakeResponse>
const page = (items: Json[]) => envelope(items, { number: 1, size: 100, totalElements: items.length, totalPages: 1 })

function transfer(extra: Json = {}): Json {
  return {
    id: 501, transferNo: 'TRF-1', batchId: 21, traceBatchNo: 'TB-B2', shipmentId: 601, shipmentNo: 'SHP-1', shipmentStatus: 'DELIVERED',
    senderOrgId: 30, receiverOrgId: 60, quantity: 600, unitCode: 'kg', status: 'PENDING', batchFlowStatus: 'ACTIVE', batchRiskStatus: 'FROZEN',
    submittedRecordedAt: '2026-09-25T00:00:00Z', version: 3, createdAt: '2026-09-25T00:00:00Z', updatedAt: '2026-09-25T00:00:00Z', ...extra
  }
}

function inboundBackend(user: Json, transfers: Json[], overrides: Overrides = {}) {
  const state = { transfers: [...transfers] }
  return {
    state,
    ...installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
      'GET /api/v1/transfers': (call) => ({ status: 200, body: page(state.transfers.filter((t) => t.status === call.search.get('status'))) }),
      'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'ORG_PROC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
      'GET /api/v1/organizations/60/sites': () => ({ status: 200, body: envelope([
        { id: 701, orgId: 60, siteNo: 'RET-QUAR', name: '门店隔离冷柜', siteType: 'COLD_STORE', status: 'ACTIVE' },
        { id: 702, orgId: 60, siteNo: 'RET-OFF', name: '停用旧店', siteType: 'STORE', status: 'INACTIVE' }
      ]) }),
      'POST /api/v1/transfers/501/quarantine': (call) => {
        const body = call.body as Json
        state.transfers = [transfer({ status: 'QUARANTINED', receivedQuantity: body.receivedQuantity, quarantineSiteId: body.quarantineSiteId,
          quarantineReason: body.reason, version: 4 })]
        return { status: 200, body: envelope(state.transfers[0]) }
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

describe('PB4 permissions and wording', () => {
  const op = receiverOp as CurrentUser
  const qm = senderQm as CurrentUser
  const alert = {
    id: 5001, orgId: 30, status: 'ACKNOWLEDGED', receiverOrgId: 60,
    batches: [{ batchId: 21, currentOrgId: 30, currentRiskStatus: 'FROZEN', released: false, latestInspectionConclusion: 'PASS' }]
  } as unknown as Alert
  const batch = alert.batches![0] as AlertAffectedBatch

  it('only NORMAL batches can be accepted; only PENDING transfers can be quarantined', () => {
    expect(canAcceptTransfer(op, transfer() as unknown as Transfer)).toBe(false)
    expect(canAcceptTransfer(op, transfer({ batchRiskStatus: 'NORMAL' }) as unknown as Transfer)).toBe(true)
    expect(canAcceptTransfer(op, transfer({ status: 'QUARANTINED', batchRiskStatus: 'NORMAL' }) as unknown as Transfer)).toBe(true)
    expect(canQuarantineTransfer(op, transfer() as unknown as Transfer)).toBe(true)
    expect(canQuarantineTransfer(op, transfer({ status: 'QUARANTINED' }) as unknown as Transfer)).toBe(false)
    expect(canQuarantineTransfer({ ...op, orgId: 99 }, transfer() as unknown as Transfer)).toBe(false)
    expect(formatTransferStatus('QUARANTINED').label).toBe('隔离收货')
  })

  it('release needs an acknowledged alert, a frozen unreleased batch of the own organization and a latest PASS', () => {
    expect(canReleaseAlertBatch(qm, alert, batch)).toBe(true)
    expect(canReleaseAlertBatch(qm, { ...alert, status: 'OPEN' }, batch)).toBe(false)
    expect(canReleaseAlertBatch(qm, alert, { ...batch, latestInspectionConclusion: 'FAIL' })).toBe(false)
    expect(canReleaseAlertBatch(qm, alert, { ...batch, latestInspectionConclusion: undefined })).toBe(false)
    expect(canReleaseAlertBatch(qm, alert, { ...batch, released: true })).toBe(false)
    expect(canReleaseAlertBatch(receiverQm as CurrentUser, alert, batch)).toBe(false)
    expect(canResolveAlert(qm, alert)).toBe(false)
    expect(canResolveAlert(qm, { ...alert, batches: [{ ...batch, released: true }] })).toBe(true)
    expect(canResolveAlert(qm, { ...alert, batches: [{ ...batch, currentRiskStatus: 'RECALLED' }] })).toBe(true)
  })

  it('inspection evidence: QM of the current organization or of the quarantine receiver', () => {
    expect(canSubmitInspection(qm, 30)).toBe(true)
    expect(canSubmitInspection(receiverQm as CurrentUser, 30, 60)).toBe(true)
    expect(canSubmitInspection(receiverQm as CurrentUser, 30, null)).toBe(false)
    expect(canSubmitInspection(op, 60)).toBe(false)
    expect(canSubmitInspection({ ...qm, scopes: ['PLATFORM'] }, 30)).toBe(false)
    expect(formatInspectionConclusion('PASS').label).toBe('检验合格')
    expect(formatInspectionConclusion(undefined).label).toBe('暂无检验结论')
  })
})

describe('InboundTransferListView (PB4)', () => {
  it('blocks accepting a frozen batch and quarantines it after a second confirmation with a reason and an own site', async () => {
    const { calls } = inboundBackend(receiverOp, [transfer()])
    const view = await mountAt('/app/transfers/inbound')
    const card = view.get('[data-testid="inbound-transfer"]')
    expect(card.get('[data-testid="inbound-frozen-warning"]').text()).toContain('不能直接接受')
    expect(card.find('[data-testid="accept-transfer-501"]').exists()).toBe(false)
    expect(card.get('[data-testid="inbound-batch-risk"]').attributes('data-status')).toBe('FROZEN')

    await card.get('[data-testid="start-quarantine-501"]').trigger('click')
    await flushPromises()
    const siteOptions = card.findAll('[data-testid="quarantine-site-501"] option').map((o) => o.text())
    expect(siteOptions).toContain('门店隔离冷柜（冷库）')
    expect(siteOptions.join()).not.toContain('停用旧店')
    await card.get('[data-testid="quarantine-next-501"]').trigger('click')
    expect(card.get('[data-testid="inbound-error-501"]').text()).toBe('请填写隔离原因')
    await card.get('[data-testid="quarantine-reason-501"]').setValue('  到货随附持续超温告警，隔离待检  ')
    await card.get('[data-testid="quarantine-next-501"]').trigger('click')
    expect(card.get('[data-testid="quarantine-confirm-panel-501"]').text()).toContain('责任组织仍为发送方')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    await card.get('[data-testid="quarantine-confirm-501"]').trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.path).toBe('/api/v1/transfers/501/quarantine')
    expect(post.headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect((post.headers['Idempotency-Key'] || '').length).toBeGreaterThanOrEqual(16)
    expect(Object.keys(post.body as Json).sort()).toEqual(['expectedVersion', 'occurredAt', 'quarantineSiteId', 'reason', 'receivedQuantity', 'unitCode'])
    expect(post.body).toMatchObject({ receivedQuantity: 600, quarantineSiteId: 701, reason: '到货随附持续超温告警，隔离待检', expectedVersion: 3 })
    expect(view.get('[data-testid="inbound-flash"]').text()).toContain('批次仍由发送方负责')
    const quarantined = view.get('[data-testid="inbound-transfer"]')
    expect(quarantined.attributes('data-status')).toBe('QUARANTINED')
    expect(quarantined.get('[data-testid="inbound-quarantine-waiting"]').text()).toContain('提交检验证据')
    expect(quarantined.find('[data-testid="accept-transfer-501"]').exists()).toBe(false)
    expect(quarantined.find('[data-testid="start-reject-501"]').exists()).toBe(true)
    expect(quarantined.get('[data-testid="inbound-quarantine-site"]').text()).toContain('门店隔离冷柜')
  })

  it('accepts a released quarantined transfer with the recorded received quantity', async () => {
    const { calls } = inboundBackend(receiverOp, [transfer({ status: 'QUARANTINED', batchRiskStatus: 'NORMAL', receivedQuantity: 598,
      differenceReason: '解冻滴水', quarantineSiteId: 701, quarantineReason: '隔离待检' })], {
      'POST /api/v1/transfers/501/accept': () => ({ status: 200, body: envelope(transfer({ status: 'ACCEPTED' })) }),
      'GET /api/v1/batches/21': () => problem(404, 'RESOURCE_NOT_FOUND')
    })
    const view = await mountAt('/app/transfers/inbound')
    expect(view.get('[data-testid="inbound-quarantine-ready"]').text()).toContain('批次风险已恢复正常')
    expect(view.get('[data-testid="inbound-received-quantity"]').text()).toContain('解冻滴水')
    const accept = view.get('[data-testid="accept-transfer-501"]')
    expect(accept.text()).toBe('接受（沿用隔离实收数量）')
    await accept.trigger('click')
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.path).toBe('/api/v1/transfers/501/accept')
    expect(post.body).toMatchObject({ receivedQuantity: 598, unitCode: 'kg', expectedVersion: 3 })
    expect((post.body as Json).differenceReason).toBeUndefined()
  })
})

describe('AlertDetailView quality decisions (PB4)', () => {
  function alertBody(extra: Json = {}, batchExtra: Json = {}): Json {
    return {
      id: 5001, alertNo: 'ALT-1', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status: 'ACKNOWLEDGED', reason: '持续超温',
      orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
      episodeStartRecordId: 1, sustainedRecordId: 2, episodeStartedAt: '2026-09-25T01:10:00Z', sustainedAt: '2026-09-25T01:40:00Z',
      durationSeconds: 1800, rule: { ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 },
      triggeredAt: '2026-09-25T01:40:01Z', acknowledgedAt: '2026-09-25T02:00:00Z', acknowledgedBy: 8, version: 1,
      batches: [{ batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'QUARANTINED',
        riskStatusBefore: 'NORMAL', autoFrozen: true, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN',
        quantity: 600, unitCode: 'kg', released: false, inspectionCount: 0, ...batchExtra }],
      actions: [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, occurredAt: '2026-09-25T02:00:00Z' }],
      ...extra
    }
  }

  function alertBackend(user: Json, reports: Json[], overrides: Overrides = {}) {
    const state = { alert: alertBody({}, reports.length ? { latestInspectionConclusion: reports[reports.length - 1].conclusion, inspectionCount: reports.length } : {}), reports: [...reports] }
    return {
      state,
      ...installFakeFetch({
        ...CSRF_ROUTE,
        'GET /api/v1/me': () => ({ status: 200, body: envelope(user) }),
        'GET /api/v1/alerts/5001': () => ({ status: 200, body: envelope(state.alert) }),
        'GET /api/v1/organizations/30': () => ({ status: 200, body: envelope({ id: 30, orgNo: 'O', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }) }),
        'GET /api/v1/batches/21/inspection-reports': () => ({ status: 200, body: envelope([...state.reports]) }),
        'POST /api/v1/batches/21/inspection-reports': (call) => {
          const body = call.body as Json
          const row = { id: state.reports.length + 1, batchId: 21, orgId: (user.orgId as number), submitterRole: user.orgId === 60 ? 'QUARANTINE_RECEIVER' : 'CURRENT_ORG',
            ...body, actorUserId: user.userId, recordedAt: '2026-09-25T03:00:00Z' }
          state.reports.push(row)
          state.alert = alertBody({}, { latestInspectionConclusion: body.conclusion, inspectionCount: state.reports.length })
          return { status: 201, body: envelope(row) }
        },
        ...overrides
      })
    }
  }

  it('lets the quarantine receiver QM submit evidence linked to the alert, but not release', async () => {
    const { calls } = alertBackend(receiverQm, [])
    const view = await mountAt('/app/alerts/5001')
    const block = view.get('[data-testid="alert-quality-batch"]')
    expect(block.find('[data-testid="inspection-empty"]').exists()).toBe(true)
    expect(block.find('[data-testid="alert-release-open-21"]').exists()).toBe(false)
    expect(view.get('[data-testid="alert-batch-disposition"]').text()).toBe('待提交检验证据')

    await block.get('[data-testid="inspection-open"]').trigger('click')
    await block.get('[data-testid="field-inspection-no"]').setValue('RCV-001')
    await block.get('[data-testid="field-inspection-institution"]').setValue('教学演示检测中心')
    await block.get('[data-testid="field-inspection-items"]').setValue('中心温度、感官')
    await block.get('[data-testid="inspection-form"]').trigger('submit')
    expect(block.get('[data-testid="inspection-submit-error"]').text()).toBe('请选择检验结论')
    await block.get('[data-testid="field-inspection-conclusion"]').setValue('PASS')
    await block.get('[data-testid="inspection-form"]').trigger('submit')
    expect(block.get('[data-testid="inspection-confirm-panel"]').text()).toContain('放行的依据')
    await block.get('[data-testid="inspection-confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.path).toBe('/api/v1/batches/21/inspection-reports')
    expect(post.body).toMatchObject({ reportNo: 'RCV-001', conclusion: 'PASS', dataSource: 'SIMULATED', alertId: 5001 })
    expect(Object.keys(post.body as Json).sort()).toEqual(['alertId', 'conclusion', 'dataSource', 'inspectedAt', 'institutionName', 'itemsSummary', 'reportNo'])
    expect(view.findAll('[data-testid="inspection-row"]')).toHaveLength(1)
    expect(view.get('[data-testid="alert-batch-inspection"]').text()).toBe('检验合格')
    expect(view.find('[data-testid="alert-release-open-21"]').exists()).toBe(false)
  })

  it('releases on a latest PASS after a second confirmation, then resolves when every batch is disposed', async () => {
    const pass = { id: 1, batchId: 21, orgId: 60, submitterRole: 'QUARANTINE_RECEIVER', alertId: 5001, reportNo: 'RCV-001', institutionName: '中心',
      inspectedAt: '2026-09-25T02:30:00Z', itemsSummary: '感官', conclusion: 'PASS', dataSource: 'SIMULATED', actorUserId: 22, recordedAt: '2026-09-25T02:31:00Z' }
    const { calls, state } = alertBackend(senderQm, [pass], {
      'POST /api/v1/alerts/5001/batches/21/release': () => {
        state.alert = alertBody({ version: 1 }, { released: true, currentRiskStatus: 'NORMAL', latestInspectionConclusion: 'PASS', inspectionCount: 1 })
        return { status: 200, body: envelope(state.alert) }
      },
      'POST /api/v1/alerts/5001/resolve': (call) => {
        state.alert = { ...state.alert, status: 'RESOLVED', resolution: (call.body as Json).resolution, resolvedAt: '2026-09-25T04:00:00Z', resolvedBy: 8 }
        return { status: 200, body: envelope(state.alert) }
      }
    })
    const view = await mountAt('/app/alerts/5001')
    expect(view.get('[data-testid="alert-batch-disposition"]').text()).toBe('最新检验合格：可放行')
    expect(view.find('[data-testid="alert-resolve"]').exists()).toBe(false)
    await view.get('[data-testid="alert-release-open-21"]').trigger('click')
    await view.get('[data-testid="field-alert-release-note"]').setValue('复检合格')
    await view.get('[data-testid="alert-release-next"]').trigger('click')
    expect(view.get('[data-testid="alert-release-confirm-panel"]').text()).toContain('数量、责任组织与流转状态不变')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await view.get('[data-testid="alert-release-confirm"]').trigger('click')
    await flushPromises()
    const release = calls.find((c) => c.method === 'POST')!
    expect(release.path).toBe('/api/v1/alerts/5001/batches/21/release')
    expect(release.body).toEqual({ note: '复检合格' })
    expect(view.get('[data-testid="alert-flash"]').text()).toContain('已依据检验结论放行')
    expect(view.get('[data-testid="alert-batch-disposition"]').text()).toBe('已依据检验结论放行')

    await view.get('[data-testid="alert-resolve-open"]').trigger('click')
    await view.get('[data-testid="alert-resolve-form"]').trigger('submit')
    await view.get('[data-testid="alert-resolve-confirm"]').trigger('click')
    await flushPromises()
    expect(view.get('[data-testid="alert-action-error"]').text()).toBe('请填写处置结论')
    await view.get('[data-testid="field-alert-resolution"]').setValue('复检合格，批次已放行')
    await view.get('[data-testid="alert-resolve-form"]').trigger('submit')
    await view.get('[data-testid="alert-resolve-confirm"]').trigger('click')
    await flushPromises()
    const resolve = calls.filter((c) => c.method === 'POST').pop()!
    expect(resolve.path).toBe('/api/v1/alerts/5001/resolve')
    expect(resolve.body).toEqual({ resolution: '复检合格，批次已放行' })
    expect(view.get('[data-testid="alert-status"]').attributes('data-status')).toBe('RESOLVED')
    expect(view.get('[data-testid="alert-resolution"]').text()).toContain('复检合格，批次已放行')
    expect(view.find('[data-testid="inspection-open"]').exists()).toBe(false)
  })

  it('keeps a latest FAIL out of release and refreshes on a 409', async () => {
    const fail = { id: 1, batchId: 21, orgId: 30, submitterRole: 'CURRENT_ORG', alertId: 5001, reportNo: 'SND-1', institutionName: '中心',
      inspectedAt: '2026-09-25T02:30:00Z', itemsSummary: '感官', conclusion: 'FAIL', dataSource: 'SIMULATED', actorUserId: 8, recordedAt: '2026-09-25T02:31:00Z' }
    alertBackend(senderQm, [fail])
    const view = await mountAt('/app/alerts/5001')
    expect(view.get('[data-testid="alert-batch-disposition"]').text()).toContain('可拒收或发起模拟召回')
    expect(view.find('[data-testid="alert-release-open-21"]').exists()).toBe(false)
  })
})
