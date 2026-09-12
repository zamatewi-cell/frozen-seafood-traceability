import { defineConfig, devices } from '@playwright/test'

const isCI = !!process.env.CI
const channel = isCI ? undefined : 'chrome'

export default defineConfig({
  testDir: './tests/e2e',
  testIgnore: process.env.REAL_SMOKE === 'true' ? undefined : '**/real-smoke.spec.ts',
  timeout: 30000,
  expect: {
    timeout: 5000
  },
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: isCI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    channel
  },
  projects: [
    {
      name: 'mobile-360',
      use: {
        viewport: { width: 360, height: 740 },
        userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
      }
    },
    {
      name: 'mobile-390',
      use: {
        viewport: { width: 390, height: 844 },
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
      }
    },
    {
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 800 }
      }
    }
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173/trace',
    reuseExistingServer: !isCI,
    timeout: 120000
  }
})
