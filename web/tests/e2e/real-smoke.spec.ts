import { test, expect } from '@playwright/test'

test('queries a fixture through the real Vue-to-Spring-to-MySQL path', async ({ page }) => {
  const publicTraceId = process.env.SMOKE_PUBLIC_TRACE_ID
  const productName = process.env.SMOKE_PRODUCT_NAME

  expect(process.env.REAL_SMOKE).toBe('true')
  expect(publicTraceId).toMatch(/^[A-Z2-7]{26}$/)
  expect(productName).toBeTruthy()

  const apiResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/api/public/v1/public/traces/${publicTraceId}`)
  )

  await page.goto(`/trace/${publicTraceId}`)

  const response = await apiResponse
  expect(response.status()).toBe(200)
  await expect(page.locator('h1.product-title')).toHaveText(productName!)
  await expect(page.locator('.timeline-item')).toHaveCount(1)
  await expect(page.locator('.timeline-item')).toContainText('仿真推演')
  await expect(page.locator('.temp-summary-card')).toContainText('暂无实时时序采集')
  await expect(page.locator('.trace-disclosure-card')).toContainText('查询时间')
})
