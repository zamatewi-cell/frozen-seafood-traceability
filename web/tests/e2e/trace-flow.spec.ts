import { test, expect } from '@playwright/test'
import { mockSuccessTrace, mockRecalledTrace, mockEmptyTimelineTrace } from './fixtures'

test.describe('Consumer Trace Flow', () => {
  test('manual entry flow on /trace with keyboard and click submission', async ({ page }) => {
    // 拦截 API 请求并返回 mockSuccessTrace
    await page.route('**/api/public/v1/public/traces/*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: mockSuccessTrace,
          meta: { requestId: 'req-e2e-01', timestamp: '2026-09-10T08:00:00Z' }
        })
      })
    })

    await page.goto('/trace')
    await expect(page.locator('h2:has-text("欢迎使用冷冻海产品溯源服务")')).toBeVisible()

    const input = page.locator('#trace-code-input')
    await expect(input).toBeVisible()

    // 输入小写，应自动转为大写
    await input.fill('wvkj5y2c4p4q6t7xz2m7k3b2ac')
    await expect(input).toHaveValue('WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')

    // 键盘 Enter 提交
    await input.press('Enter')

    // 路由变更并展示详情
    await expect(page).toHaveURL(/\/trace\/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC/)
    await expect(page.locator('h1.product-title')).toHaveText('舟山野生大黄鱼')
    await expect(page.locator('.consumer-hero-card .status-badge')).toContainText('当前记录正常')
    await expect(page.locator('.timeline-item')).toHaveCount(2)
  })

  test('direct route access on /trace/:publicTraceId renders verified product cards', async ({ page }) => {
    let requestCount = 0
    await page.route('**/api/public/v1/public/traces/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC', async (route) => {
      requestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: mockSuccessTrace,
          meta: { requestId: 'req-e2e-02', timestamp: '2026-09-10T08:00:00Z' }
        })
      })
    })

    await page.goto('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    await expect(page.locator('h1.product-title')).toHaveText('舟山野生大黄鱼')
    await expect(page.locator('.attr-row:has-text("公开批次编号")')).toContainText('BAT****001')
    await expect(page.locator('.attr-row:has-text("溯源产地/海域")')).toContainText('东海近海舟山渔场')
    await expect(page.locator('.temp-summary-card')).toContainText('冷链温控履约摘要')
    await expect(page.locator('.temp-disclaimer')).toContainText('不伪造温控合规结论')
    await expect(page.locator('.trace-disclosure-card')).toContainText('查询时间')
    await expect(page.locator('.trace-disclosure-card')).toContainText(mockSuccessTrace.disclosure)
    expect(requestCount).toBe(1)
  })

  test('recalled state renders prominent simulated recall drill banner', async ({ page }) => {
    await page.route('**/api/public/v1/public/traces/RECALL2C4P4Q6T7XZ2M7K3B2AC', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: mockRecalledTrace,
          meta: { requestId: 'req-e2e-03', timestamp: '2026-09-10T08:00:00Z' }
        })
      })
    })

    await page.goto('/trace/RECALL2C4P4Q6T7XZ2M7K3B2AC')
    const recallAlert = page.locator('.recall-alert-card')
    await expect(recallAlert).toBeVisible()
    await expect(recallAlert).toContainText('系统模拟召回演练声明')
    await expect(recallAlert).toContainText('教学演练推演')
    await expect(recallAlert).toContainText('此批次海产品已启动系统模拟召回演练')
  })

  test('empty timeline state renders friendly empty notice instead of broken table', async ({ page }) => {
    await page.route('**/api/public/v1/public/traces/EMPTY23C4P4Q6T7XZ2M7K3B2AC', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: mockEmptyTimelineTrace,
          meta: { requestId: 'req-e2e-04', timestamp: '2026-09-10T08:00:00Z' }
        })
      })
    })

    await page.goto('/trace/EMPTY23C4P4Q6T7XZ2M7K3B2AC')
    await expect(page.locator('.empty-timeline')).toBeVisible()
    await expect(page.locator('.empty-timeline')).toContainText('该批次当前尚未记录生效流转事件')
  })

  test('neutral not-found presentation for 404 unknown code', async ({ page }) => {
    await page.route('**/api/public/v1/public/traces/*', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'https://example.com/problems/not-found',
          title: '未找到公开追溯信息',
          status: 404,
          code: 'PUBLIC_TRACE_NOT_FOUND',
          detail: '未找到该追溯码或该码已失效',
          requestId: 'req-404-e2e'
        })
      })
    })

    await page.goto('/trace/NOTFND2C4P4Q6T7XZ2M7K3B2AC')
    const notFoundCard = page.locator('.neutral-not-found-card')
    await expect(notFoundCard).toBeVisible()
    await expect(notFoundCard).toContainText('未找到追溯信息')
    await expect(notFoundCard).toContainText('未找到该追溯码或该码已失效')
    await expect(notFoundCard.locator('.guidance-box')).toBeVisible()
    await notFoundCard.locator('button').click()
    await expect(page).toHaveURL(/\/trace$/)
    await expect(page.locator('#trace-code-input')).toHaveValue('')
  })

  test('malformed trace ID exhibits identical neutral not-found presentation without hitting backend', async ({ page }) => {
    let apiCalled = false
    await page.route('**/api/public/v1/public/traces/*', async (route) => {
      apiCalled = true
      await route.fulfill({ status: 500 })
    })

    // 格式不满足 ^[A-Z2-7]{26}$
    await page.goto('/trace/SHORTID')
    const notFoundCard = page.locator('.neutral-not-found-card')
    await expect(notFoundCard).toBeVisible()
    await expect(notFoundCard).toContainText('未找到追溯信息')
    expect(apiCalled).toBe(false) // 客户端直接拦截
  })
})
