import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './test/browser',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4322',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], defaultBrowserType: 'chromium' } },
  ],
  webServer: {
    // Skips pagefind: /purchase/ doesn't load search, and CI runs the full build later.
    command: 'pnpm exec astro build && pnpm preview --host 127.0.0.1 --port 4322',
    url: 'http://127.0.0.1:4322/purchase/',
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      PUBLIC_PURCHASE_ENABLED: 'true',
      PUBLIC_PADDLE_CLIENT_TOKEN: 'test_browser-checkout',
      PUBLIC_PADDLE_PRICE_ID: 'pri_01m5hxzxpvqr0v4d454hpq6cvs',
      PUBLIC_PADDLE_ENVIRONMENT: 'sandbox',
      PUBLIC_LICENSE_API_URL: 'https://license.example.test',
      PUBLIC_PURCHASE_TERMS_URL: '',
      PUBLIC_PURCHASE_PRIVACY_URL: '',
      PUBLIC_PURCHASE_REFUND_URL: '',
    },
  },
});
