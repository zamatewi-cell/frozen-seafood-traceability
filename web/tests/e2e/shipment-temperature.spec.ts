import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * Phase B PB2 运输任务“在途温度记录”面板的浏览器级回归（接口使用受控替身）。
 * 真实 Spring Boot + MySQL 的验收见 real-slice5.spec.ts 中的 PB2-T 检查点（不使用任何拦截）。
 */

const carrier = {
  userId: 11, username: 'carrier_op', displayName: '承运操作员', orgId: 50, orgNo: 'ORG_CAR_01',
  orgName: '极冷冷链物流', orgType: 'CARRIER', roles: ['OPERATOR'], scopes: ['ORG_ONLY']
}
const receiver = { ...carrier, userId: 21, username: 'retail_op', orgId: 60, orgNo: 'ORG_RET_01', orgName: '鲜到家零售', orgType: 'RETAILER' }

const meta = { requestId: 'req-e2e-pb2', timestamp: '2026-09-24T08:00:00Z' }
const RULE = { ruleId: 51, name: '冷冻大黄鱼运输规则', versionNo: 1, ruleStageId: 61, lowerLimit: -25, upperLimit: -15, allowedDurationSeconds: 1800 }

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) })
}

function shipment() {
  const party = (id: number, name: string, orgType: string) => ({ id, orgNo: `ORG-${id}`, name, orgType })
  return {
    id: 601, shipmentNo: 'SHP-PB2-E2E', status: 'IN_TRANSIT', vehicleOrContainerNo: '浙L·冷001', version: 2,
    senderOrg: party(30, '东海水产加工有限公司', 'PROCESSOR'), receiverOrg: party(60, '鲜到家零售', 'RETAILER'), carrierOrg: party(50, '极冷冷链物流', 'CARRIER'),
    originSite: { id: 301, orgId: 30, siteNo: 'PRC-COLD', name: '舟山自有冷库', siteType: 'COLD_STORE', status: 'ACTIVE' },
    destinationSite: { id: 601, orgId: 60, siteNo: 'RET-STORE', name: '鲜到家门店', siteType: 'STORE', status: 'ACTIVE' },
    loadedAt: new Date(Date.now() - 3_600_000).toISOString(),
    transfers: [{ transferId: 501, transferNo: 'TRF-1', batchId: 21, traceBatchNo: 'TB-B2', quantity: 600, unitCode: 'kg', status: 'PENDING', version: 2 }],
    createdAt: '2026-09-24T01:00:00Z', updatedAt: '2026-09-24T01:00:00Z'
  }
}

async function installBackend(page: Page, user: typeof carrier, records: unknown[] = []) {
  const posts: { headers: Record<string, string>; body: Record<string, unknown> }[] = []
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
  await page.route('**/api/v1/shipments/601', (route) => json(route, 200, { data: shipment(), meta }))
  await page.route('**/api/v1/shipments/601/temperature-records', (route) => {
    if (route.request().method() === 'GET') return json(route, 200, { data: records, meta })
    const body = route.request().postDataJSON() as Record<string, unknown>
    posts.push({ headers: route.request().headers(), body })
    const t = body.temperature as number
    const row = {
      id: 700 + posts.length, shipmentId: 601, stageCode: 'TRANSPORT', measuredAt: body.measuredAt, recordedAt: new Date().toISOString(),
      temperature: t, unitCode: 'CELSIUS', dataSource: body.dataSource, evaluation: t > -15 ? 'HIGH' : t < -25 ? 'LOW' : 'NORMAL',
      rule: RULE, orgId: 50, actorUserId: 11
    }
    records.push(row)
    return json(route, 201, { data: row, meta })
  })
  await page.goto('/login')
  await page.locator('#login-username').fill(user.username)
  await page.locator('#login-password').fill('any-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/app$/)
  return posts
}

test.describe('PB2 shipment temperature panel', () => {
  test('the carrier records a single point that is shown only as a single-point evaluation', async ({ page }) => {
    const posts = await installBackend(page, carrier)
    await page.goto('/app/shipments/601')
    const panel = page.getByTestId('temperature-panel')
    await expect(panel).toBeVisible()
    await expect(page.getByTestId('temperature-disclaimer')).toContainText('不等于持续超温')
    await expect(page.getByTestId('temperature-empty')).toBeVisible()

    await page.getByTestId('temperature-open').click()
    await page.getByTestId('field-temperature-value').fill('-12.5')
    await page.getByTestId('field-temperature-source').selectOption('SIMULATED')
    await page.getByTestId('temperature-submit').click()

    const row = page.getByTestId('temperature-row')
    await expect(row).toHaveCount(1)
    await expect(row).toHaveAttribute('data-evaluation', 'HIGH')
    await expect(row.getByTestId('temperature-evaluation')).toHaveText('单点高于上限')
    await expect(row.getByTestId('temperature-value')).toHaveText('-12.50 ℃')
    await expect(page.getByTestId('temperature-summary')).toContainText('单点越界 1 条（单点判定，不等于持续超温）')
    await expect(panel).not.toContainText('告警已触发')
    expect(posts).toHaveLength(1)
    expect(posts[0].headers['x-csrf-token']).toBe('e2e-csrf')
    expect((posts[0].headers['idempotency-key'] || '').length).toBeGreaterThanOrEqual(16)
    expect(Object.keys(posts[0].body).sort()).toEqual(['dataSource', 'measuredAt', 'temperature'])
    expect(posts[0].body).toMatchObject({ temperature: -12.5, dataSource: 'SIMULATED' })
  })

  test('the receiver sees the readings read-only', async ({ page }) => {
    await installBackend(page, receiver, [{
      id: 701, shipmentId: 601, stageCode: 'TRANSPORT', measuredAt: new Date(Date.now() - 1_800_000).toISOString(),
      recordedAt: new Date().toISOString(), temperature: -18.2, unitCode: 'CELSIUS', dataSource: 'MANUAL', evaluation: 'NORMAL',
      rule: RULE, orgId: 50, actorUserId: 11
    }])
    await page.goto('/app/shipments/601')
    await expect(page.getByTestId('temperature-row')).toHaveCount(1)
    await expect(page.getByTestId('temperature-evaluation')).toHaveText('单点在范围内')
    await expect(page.getByTestId('temperature-rule-band')).toContainText('-25.00 ℃ ~ -15.00 ℃')
    await expect(page.getByTestId('temperature-open')).toHaveCount(0)
  })
})
