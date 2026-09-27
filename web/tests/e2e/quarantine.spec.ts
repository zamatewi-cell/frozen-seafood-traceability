import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * Phase B PB4 隔离收货、检验证据与告警放行 / 处置结论的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的异常闭环验收见 real-phase-b.spec.ts（不使用任何拦截）。
 */

const receiver = {
  userId: 21, username: 'retail_op', displayName: '零售操作员', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售',
  orgType: 'RETAILER', roles: ['OPERATOR'], scopes: ['ORG_ONLY']
}
const senderQm = { ...receiver, userId: 8, username: 'processor_qm', displayName: '加工质量管理员', orgId: 30, orgNo: 'ORG_PRC_01',
  orgName: '东海水产加工有限公司', orgType: 'PROCESSOR', roles: ['QUALITY_MANAGER'] }
const meta = { requestId: 'req-e2e-pb4', timestamp: '2026-09-25T08:00:00Z' }

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) })
}

function transfer(extra: Record<string, unknown> = {}) {
  return {
    id: 501, transferNo: 'TRF-1', batchId: 21, traceBatchNo: 'TB-B2', shipmentId: 601, shipmentNo: 'SHP-1', shipmentStatus: 'DELIVERED',
    senderOrgId: 30, receiverOrgId: 60, quantity: 600, unitCode: 'kg', status: 'PENDING', batchFlowStatus: 'ACTIVE', batchRiskStatus: 'FROZEN',
    submittedRecordedAt: '2026-09-25T00:00:00Z', version: 3, createdAt: '2026-09-25T00:00:00Z', updatedAt: '2026-09-25T00:00:00Z', ...extra
  }
}

async function login(page: Page, user: typeof receiver) {
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
  await page.goto('/login')
  await page.locator('#login-username').fill(user.username)
  await page.locator('#login-password').fill('any-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/app$/)
}

test.describe('PB4 quarantine receiving and quality decisions', () => {
  test('the receiver cannot accept a frozen batch and quarantines it after a confirmation', async ({ page }) => {
    let current = transfer()
    const posts: { url: string; body: Record<string, unknown> }[] = []
    await page.route('**/api/v1/transfers?*', (route) => {
      const status = new URL(route.request().url()).searchParams.get('status')
      const items = current.status === status ? [current] : []
      return json(route, 200, { data: items, meta: { ...meta, page: { number: 1, size: 100, totalElements: items.length, totalPages: 1 } } })
    })
    await page.route('**/api/v1/organizations/60/sites', (route) => json(route, 200, { data: [
      { id: 701, orgId: 60, siteNo: 'RET-QUAR', name: '门店隔离冷柜', siteType: 'COLD_STORE', status: 'ACTIVE' }
    ], meta }))
    await page.route('**/api/v1/transfers/501/quarantine', (route) => {
      const body = route.request().postDataJSON() as Record<string, unknown>
      posts.push({ url: route.request().url(), body })
      current = transfer({ status: 'QUARANTINED', receivedQuantity: body.receivedQuantity, quarantineSiteId: 701, quarantineReason: body.reason, version: 4 })
      return json(route, 200, { data: current, meta })
    })
    await login(page, receiver)
    await page.getByTestId('nav-inbound').click()
    const card = page.getByTestId('inbound-transfer')
    await expect(card.getByTestId('inbound-frozen-warning')).toContainText('不能直接接受')
    await expect(card.getByTestId('accept-transfer-501')).toHaveCount(0)

    await card.getByTestId('start-quarantine-501').click()
    await card.getByTestId('quarantine-reason-501').fill('到货随附持续超温告警，隔离待检')
    await card.getByTestId('quarantine-next-501').click()
    await expect(card.getByTestId('quarantine-confirm-panel-501')).toContainText('责任组织仍为发送方')
    expect(posts).toHaveLength(0)
    await card.getByTestId('quarantine-confirm-501').click()

    await expect(page.getByTestId('inbound-flash')).toContainText('批次仍由发送方负责')
    await expect(page.getByTestId('inbound-transfer')).toHaveAttribute('data-status', 'QUARANTINED')
    await expect(page.getByTestId('inbound-quarantine-site')).toContainText('门店隔离冷柜')
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toMatchObject({ receivedQuantity: 600, quarantineSiteId: 701, reason: '到货随附持续超温告警，隔离待检', expectedVersion: 3 })
  })

  test('the owning quality manager releases a batch on a PASS and resolves the alert', async ({ page }) => {
    const base = {
      id: 5001, alertNo: 'ALT-1', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status: 'ACKNOWLEDGED', reason: '持续超温',
      orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
      episodeStartRecordId: 1, sustainedRecordId: 2, episodeStartedAt: '2026-09-25T01:10:00Z', sustainedAt: '2026-09-25T01:40:00Z',
      durationSeconds: 1800, rule: { ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 },
      triggeredAt: '2026-09-25T01:40:01Z', acknowledgedAt: '2026-09-25T02:00:00Z', acknowledgedBy: 8, version: 1,
      actions: [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, occurredAt: '2026-09-25T02:00:00Z' }]
    }
    const batch = { batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'QUARANTINED',
      riskStatusBefore: 'NORMAL', autoFrozen: true, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN',
      quantity: 600, unitCode: 'kg', released: false, latestInspectionConclusion: 'PASS', inspectionCount: 1 }
    let current: Record<string, unknown> = { ...base, batches: [batch] }
    const posts: string[] = []
    await page.route('**/api/v1/alerts/5001', (route) => json(route, 200, { data: current, meta }))
    await page.route('**/api/v1/batches/21/inspection-reports', (route) => json(route, 200, { data: [
      { id: 1, batchId: 21, orgId: 60, submitterRole: 'QUARANTINE_RECEIVER', alertId: 5001, reportNo: 'RCV-001', institutionName: '教学演示检测中心',
        inspectedAt: '2026-09-25T02:30:00Z', itemsSummary: '中心温度、感官', conclusion: 'PASS', dataSource: 'SIMULATED', actorUserId: 22,
        recordedAt: '2026-09-25T02:31:00Z' }
    ], meta }))
    await page.route('**/api/v1/alerts/5001/batches/21/release', (route) => {
      posts.push(route.request().url())
      current = { ...base, batches: [{ ...batch, released: true, currentRiskStatus: 'NORMAL' }] }
      return json(route, 200, { data: current, meta })
    })
    await page.route('**/api/v1/alerts/5001/resolve', (route) => {
      posts.push(route.request().url())
      current = { ...(current as object), status: 'RESOLVED', resolution: (route.request().postDataJSON() as { resolution: string }).resolution,
        resolvedAt: '2026-09-25T04:00:00Z', resolvedBy: 8 }
      return json(route, 200, { data: current, meta })
    })
    await login(page, senderQm)
    await page.goto('/app/alerts/5001')
    await expect(page.getByTestId('inspection-row')).toHaveCount(1)
    await page.getByTestId('alert-release-open-21').click()
    await page.getByTestId('alert-release-next').click()
    await page.getByTestId('alert-release-confirm').click()
    await expect(page.getByTestId('alert-batch-disposition')).toHaveText('已依据检验结论放行')
    await page.getByTestId('alert-resolve-open').click()
    await page.getByTestId('field-alert-resolution').fill('复检合格，批次已放行')
    await page.getByTestId('alert-resolve-next').click()
    await page.getByTestId('alert-resolve-confirm').click()
    await expect(page.getByTestId('alert-status')).toHaveAttribute('data-status', 'RESOLVED')
    await expect(page.getByTestId('alert-resolution')).toContainText('复检合格，批次已放行')
    expect(posts).toHaveLength(2)
  })
})
