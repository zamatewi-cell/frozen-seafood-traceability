import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * Phase B PB5 模拟召回（从告警检验不合格发起、范围展示、关闭）的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的异常闭环验收见 real-phase-b.spec.ts（不使用任何拦截）。
 */

const ownerQm = {
  userId: 8, username: 'processor_qm', displayName: '加工质量管理员', orgId: 30, orgNo: 'ORG_PRC_01', orgName: '东海水产加工有限公司',
  orgType: 'PROCESSOR', roles: ['QUALITY_MANAGER'], scopes: ['ORG_ONLY']
}
const meta = { requestId: 'req-e2e-pb5', timestamp: '2026-09-26T08:00:00Z' }

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) })
}

async function login(page: Page, user: typeof ownerQm) {
  let loggedIn = false
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, 200, { data: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'e2e-csrf' }, meta }))
  await page.route('**/api/v1/me', (route) => (loggedIn
    ? json(route, 200, { data: user, meta })
    : json(route, 401, { status: 401, code: 'AUTH_REQUIRED', title: '需要登录' })))
  await page.route('**/api/v1/auth/login', (route) => {
    loggedIn = true
    return json(route, 200, { data: user, meta })
  })
  await page.route('**/api/v1/organizations/30', (route) =>
    json(route, 200, { data: { id: 30, orgNo: 'ORG_PRC_01', name: '东海水产加工有限公司', orgType: 'PROCESSOR', status: 'ACTIVE' }, meta }))
  await page.route('**/api/v1/organizations/60', (route) =>
    json(route, 200, { data: { id: 60, orgNo: 'ORG_RET_01', name: '鲜到家零售', orgType: 'RETAILER', status: 'ACTIVE' }, meta }))
  await page.goto('/login')
  await page.locator('#login-username').fill(user.username)
  await page.locator('#login-password').fill('any-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/app$/)
}

test.describe('PB5 simulated recall', () => {
  test('the owner QM recalls a FAIL-inspected alert batch after a confirmation and closes the recall with a controlled disposition', async ({ page }) => {
    const alert = {
      id: 5001, alertNo: 'ALT-1', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status: 'ACKNOWLEDGED', reason: '持续超温',
      orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
      episodeStartRecordId: 1, sustainedRecordId: 2, episodeStartedAt: '2026-09-26T01:10:00Z', sustainedAt: '2026-09-26T01:40:00Z',
      durationSeconds: 1800, rule: { ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 },
      triggeredAt: '2026-09-26T01:40:01Z', acknowledgedAt: '2026-09-26T02:00:00Z', acknowledgedBy: 8, version: 1,
      actions: [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, occurredAt: '2026-09-26T02:00:00Z' }],
      batches: [{ batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'QUARANTINED',
        riskStatusBefore: 'NORMAL', autoFrozen: true, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN',
        quantity: 600, unitCode: 'kg', released: false, latestInspectionConclusion: 'FAIL', inspectionCount: 1 }]
    }
    let recall: Record<string, unknown> = {
      id: 7001, recallNo: 'RCL-20260926030000-123456', ownerOrgId: 30, sourceAlertId: 5001, reason: '隔离检验不合格，启动模拟召回',
      status: 'IN_PROGRESS', startedAt: '2026-09-26T03:00:00Z', startedBy: 8, version: 0,
      summary: { seedCount: 1, descendantCount: 0, ancestorCount: 2, recalledCount: 1, notifiedCount: 0, openTransferCount: 1,
        publicCodeCount: 1, remainingQuantity: 600, soldQuantity: 0 },
      scope: [
        { batchId: 21, traceBatchNo: 'TB-B2', productName: '冷冻大黄鱼段', scopeRole: 'SEED', depth: 0, holderOrgId: 30, flowStatus: 'ACTIVE',
          riskStatusBefore: 'FROZEN', currentFlowStatus: 'ACTIVE', currentRiskStatus: 'RECALLED', action: 'RECALLED', riskTransitionId: 910,
          declaredQuantity: 600, remainingQuantity: 600, soldQuantity: 0, unitCode: 'kg', openTransferNo: 'TRF-1', publicCodeActive: true },
        { batchId: 20, traceBatchNo: 'TB-B1', productName: '冷冻大黄鱼', scopeRole: 'ANCESTOR', depth: -1, holderOrgId: 30, flowStatus: 'CLOSED',
          riskStatusBefore: 'NORMAL', currentFlowStatus: 'CLOSED', currentRiskStatus: 'NORMAL', action: 'TRACE_ONLY',
          declaredQuantity: 960, remainingQuantity: 0, soldQuantity: 0, unitCode: 'kg', publicCodeActive: false }
      ]
    }
    const posts: { url: string; body: Record<string, unknown>; key: string | null }[] = []
    await page.route('**/api/v1/alerts/5001', (route) => json(route, 200, { data: alert, meta }))
    await page.route('**/api/v1/batches/21/inspection-reports', (route) => json(route, 200, { data: [
      { id: 1, batchId: 21, orgId: 60, submitterRole: 'QUARANTINE_RECEIVER', alertId: 5001, reportNo: 'RCV-002', institutionName: '教学演示检测中心',
        inspectedAt: '2026-09-26T02:30:00Z', itemsSummary: '中心温度、感官', conclusion: 'FAIL', dataSource: 'SIMULATED', actorUserId: 22,
        recordedAt: '2026-09-26T02:31:00Z' }
    ], meta }))
    await page.route('**/api/v1/recalls', (route) => {
      posts.push({ url: route.request().url(), body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] ?? null })
      return json(route, 201, { data: recall, meta })
    })
    await page.route('**/api/v1/recalls/7001', (route) => json(route, 200, { data: recall, meta }))
    await page.route('**/api/v1/recalls/7001/close', (route) => {
      const body = route.request().postDataJSON() as Record<string, unknown>
      posts.push({ url: route.request().url(), body, key: route.request().headers()['idempotency-key'] ?? null })
      recall = { ...recall, status: 'CLOSED', publicDisposition: body.publicDisposition, resultSummary: body.resultSummary,
        closedAt: '2026-09-26T05:00:00Z', closedBy: 8, version: 1 }
      return json(route, 200, { data: recall, meta })
    })

    await login(page, ownerQm)
    await page.goto('/app/alerts/5001')
    await expect(page.getByTestId('alert-release-open-21')).toHaveCount(0)
    await page.getByTestId('recall-start-open').click()
    await page.getByTestId('field-recall-reason').fill('隔离检验不合格，启动模拟召回')
    await page.getByTestId('recall-start-next').click()
    await expect(page.getByTestId('recall-start-confirm-panel')).toContainText('不代表真实法定召回')
    expect(posts).toHaveLength(0)
    await page.getByTestId('recall-start-confirm').click()

    await expect(page).toHaveURL(/\/app\/recalls\/7001$/)
    expect(posts[0].body).toEqual({ batchIds: [21], reason: '隔离检验不合格，启动模拟召回', alertId: 5001 })
    expect(posts[0].key?.length ?? 0).toBeGreaterThanOrEqual(16)
    await expect(page.getByTestId('recall-status')).toHaveAttribute('data-status', 'IN_PROGRESS')
    await expect(page.getByTestId('recall-disclaimer')).toContainText('不代表真实法定召回')
    await expect(page.locator('[data-testid="recall-scope-SEED"] [data-testid="recall-scope-row"]')).toHaveAttribute('data-action', 'RECALLED')
    await expect(page.locator('[data-testid="recall-scope-ANCESTOR"] [data-testid="recall-scope-row"]')).toHaveAttribute('data-action', 'TRACE_ONLY')

    await page.getByTestId('recall-close-open').click()
    await page.getByTestId('field-recall-disposition').selectOption('RETURNED')
    await page.getByTestId('field-recall-summary').fill('在途 600 kg 已按演练流程退回')
    await page.getByTestId('recall-close-next').click()
    await page.getByTestId('recall-close-confirm').click()
    await expect(page.getByTestId('recall-status')).toHaveAttribute('data-status', 'CLOSED')
    await expect(page.getByTestId('recall-disposition')).toContainText('退回')
    await expect(page.getByTestId('recall-close-open')).toHaveCount(0)
    expect(posts).toHaveLength(2)
    expect(posts[1].body).toEqual({ publicDisposition: 'RETURNED', resultSummary: '在途 600 kg 已按演练流程退回' })
  })
})
