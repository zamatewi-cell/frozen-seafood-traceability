import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * Phase B PB3 在途持续超温告警页面的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的异常闭环验收见 real-phase-b.spec.ts（不使用任何拦截）。
 */

const qm = {
  userId: 8, username: 'processor_qm', displayName: '加工质量管理员', orgId: 30, orgNo: 'ORG_PRC_01',
  orgName: '东海水产加工有限公司', orgType: 'PROCESSOR', roles: ['QUALITY_MANAGER'], scopes: ['ORG_ONLY']
}
const receiver = { ...qm, userId: 21, username: 'retail_op', displayName: '零售操作员', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER', roles: ['OPERATOR'] }

const meta = { requestId: 'req-e2e-pb3', timestamp: '2026-09-25T08:00:00Z' }

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) })
}

function alertBody(status = 'OPEN', actions: unknown[] = []) {
  return {
    id: 5001, alertNo: 'ALT-20260925011000-123456', alertType: 'TEMP_OVER_UPPER', severity: 'HIGH', status,
    reason: '运输任务 SHP-1 在途温度连续高于上限 -15.00 ℃，已持续 1800 秒，达到判定依据允许的连续越界时长 1800 秒，形成持续超温告警',
    orgId: 30, shipmentId: 601, shipmentNo: 'SHP-1', receiverOrgId: 60, carrierOrgId: 50, stageCode: 'TRANSPORT',
    episodeStartRecordId: 701, sustainedRecordId: 702, episodeStartedAt: '2026-09-25T01:10:00.000000Z',
    sustainedAt: '2026-09-25T01:40:00.000000Z', durationSeconds: 1800,
    rule: { ruleId: 51, name: '冷冻大黄鱼运输规则', versionNo: 1, ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 },
    triggeredAt: '2026-09-25T01:40:01.000000Z', version: actions.length,
    ...(status === 'OPEN' ? {} : { acknowledgedAt: '2026-09-25T02:00:00.000000Z', acknowledgedBy: 8 }),
    batches: [
      { batchId: 21, traceBatchNo: 'TB-B2', transferId: 501, transferNo: 'TRF-1', transferStatus: 'PENDING', riskStatusBefore: 'NORMAL',
        autoFrozen: true, freezeTransitionId: 9001, currentOrgId: 30, currentFlowStatus: 'ACTIVE', currentRiskStatus: 'FROZEN', quantity: 600, unitCode: 'kg' }
    ],
    actions
  }
}

async function installBackend(page: Page, user: typeof qm) {
  const posts: { headers: Record<string, string>; body: Record<string, unknown> }[] = []
  let current = alertBody()
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
  await page.route('**/api/v1/alerts?*', (route) => json(route, 200, { data: [{ ...current, batches: undefined, actions: undefined }], meta }))
  await page.route('**/api/v1/alerts', (route) => json(route, 200, { data: [{ ...current, batches: undefined, actions: undefined }], meta }))
  await page.route('**/api/v1/alerts/5001', (route) => json(route, 200, { data: current, meta }))
  await page.route('**/api/v1/alerts/5001/acknowledge', (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    posts.push({ headers: route.request().headers(), body })
    current = alertBody('ACKNOWLEDGED', [{ id: 1, action: 'ACKNOWLEDGE', orgId: 30, actorUserId: 8, note: body.note, occurredAt: '2026-09-25T02:00:00.000000Z' }])
    return json(route, 200, { data: current, meta })
  })
  await page.goto('/login')
  await page.locator('#login-username').fill(user.username)
  await page.locator('#login-password').fill('any-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/app$/)
  return posts
}

test.describe('PB3 sustained-excursion alerts', () => {
  test('the owning quality manager opens the alert from the list and acknowledges it after a second confirmation', async ({ page }) => {
    const posts = await installBackend(page, qm)
    await page.getByTestId('nav-alerts').click()
    await expect(page).toHaveURL(/\/app\/alerts$/)
    const row = page.getByTestId('alert-row')
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('本组织负责处置')
    await page.getByTestId('open-alert-5001').click()

    await expect(page.getByTestId('alert-status')).toHaveAttribute('data-status', 'OPEN')
    await expect(page.getByTestId('alert-rule')).toContainText('-25.00 ℃ ~ -15.00 ℃')
    await expect(page.getByTestId('alert-disclaimer')).toContainText('模拟质量处置')
    await expect(page.getByTestId('alert-batch-row')).toHaveAttribute('data-risk-status', 'FROZEN')

    await page.getByTestId('alert-ack-open').click()
    await page.getByTestId('field-alert-ack-note').fill('安排复检')
    await page.getByTestId('alert-ack-next').click()
    await expect(page.getByTestId('alert-ack-confirm-panel')).toBeVisible()
    expect(posts).toHaveLength(0)
    await page.getByTestId('alert-ack-confirm').click()

    await expect(page.getByTestId('alert-status')).toHaveAttribute('data-status', 'ACKNOWLEDGED')
    await expect(page.getByTestId('alert-history-row')).toHaveCount(2)
    expect(posts).toHaveLength(1)
    expect(posts[0].headers['x-csrf-token']).toBe('e2e-csrf')
    expect((posts[0].headers['idempotency-key'] || '').length).toBeGreaterThanOrEqual(16)
    expect(posts[0].body).toEqual({ note: '安排复检' })
  })

  test('the receiver sees the alert read-only', async ({ page }) => {
    await installBackend(page, receiver)
    await page.goto('/app/alerts/5001')
    await expect(page.getByTestId('alert-next-step')).toContainText('等待发货方质量管理员确认异常')
    await expect(page.getByTestId('alert-actions')).toHaveCount(0)
  })
})
