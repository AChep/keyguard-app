import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const claimToken = 'secret-claim-token-for-browser-test';
const transactionId = 'txn_01examplecheckout';
const key = 'KG2A-TEST-LIFETIME-LICENSE-KEY';
const api = 'https://license.example.test';
const session = () => ({
  checkoutId: 'checkout_test',
  claimToken,
  transactionId,
  expiresAt: new Date(Date.now() + 60 * 60 * 1_000).toISOString(),
});

async function mockCheckout(page: Page, initialStatus = 'pending', preview: {
  total?: string;
  currency?: string;
  mode?: 'success' | 'error' | 'pending' | 'mismatch';
  sdkReady?: Promise<void>;
} = {}) {
  let status = initialStatus;
  let statusCode = 200;
  let creates = 0;
  let reads = 0;
  await page.route('https://cdn.paddle.com/paddle/v2/paddle.js', async (route) => {
    await preview.sdkReady;
    await route.fulfill({
      contentType: 'application/javascript',
      body: `
      let callback;
      window.__paddleOpens = [];
      window.__paddleInitializations = 0;
      window.__pricePreviews = [];
      window.__priceMode = ${JSON.stringify(preview.mode ?? 'success')};
      window.__paddleEmit = (name) => callback({ name });
      window.PaddleBillingV1 = {
        Environment: { set() {} },
        Initialize(options) { window.__paddleInitializations++; callback = options.eventCallback; this.Initialized = true; },
        Update(options) { window.__paddleInitializations++; callback = options.eventCallback; },
        PricePreview(request) {
          window.__pricePreviews.push(request);
          if (window.__priceMode === 'error') return Promise.reject(new Error('Price unavailable'));
          if (window.__priceMode === 'pending') return new Promise(() => {});
          return Promise.resolve({ data: {
            currencyCode: ${JSON.stringify(preview.currency ?? 'USD')},
            details: { lineItems: [{
              price: { id: window.__priceMode === 'mismatch' ? 'pri_other' : request.items[0].priceId },
              formattedTotals: { total: ${JSON.stringify(preview.total ?? '$15.00')} }
            }] }
          } });
        },
        Checkout: {
          open(options) {
            if (!sessionStorage.getItem('keyguard.paddle.checkout.v1')) throw new Error('Session not saved');
            window.__paddleOpens.push(options);
          }
        }
      };
      `,
    });
  });
  await page.route(`${api}/v1/checkout/paddle`, async (route) => {
    creates += 1;
    expect(route.request().postDataJSON()).toEqual({});
    await route.fulfill({ json: session() });
  });
  await page.route(`${api}/v1/checkout/paddle/status`, async (route) => {
    reads += 1;
    expect(route.request().postDataJSON()).toEqual({ checkoutId: 'checkout_test', claimToken });
    expect(route.request().headers().referer).toBeUndefined();
    await route.fulfill({
      status: statusCode,
      json: statusCode === 200 ? { status, ...(status === 'active' ? { licenseKey: key } : {}) } : { error: { code: 'CHECKOUT_EXPIRED', message: 'Expired' } },
    });
  });
  return {
    setStatus(value: string) { status = value; },
    setStatusCode(value: number) { statusCode = value; },
    creates: () => creates,
    reads: () => reads,
  };
}

async function emit(page: Page, name: string) {
  await page.evaluate((eventName) => {
    (window as unknown as { __paddleEmit(name: string): void }).__paddleEmit(eventName);
  }, name);
}

async function openPurchase(page: Page) {
  await page.goto('/purchase/');
  await page.getByRole('button', { name: 'Buy lifetime license', exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __paddleOpens?: unknown[] }).__paddleOpens?.length ?? 0)).toBe(1);
}

test('only verified payment reveals a key; it can be copied and downloaded', async ({ page, context }, testInfo) => {
  const backend = await mockCheckout(page);
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('/purchase/');
  await expect(page.locator('[data-price]')).toHaveText('$15.00');
  await page.screenshot({ path: testInfo.outputPath('purchase-enabled.png'), fullPage: true });
  await openPurchase(page);
  await emit(page, 'checkout.completed');
  await expect(page.getByText('Confirming your payment', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Your license key')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Buy lifetime license', exact: true })).toBeHidden();
  backend.setStatus('active');
  await page.reload();
  await expect(page.getByLabel('Your license key')).toHaveValue(key);
  await page.screenshot({ path: testInfo.outputPath('purchase-success.png'), fullPage: true });
  await page.getByRole('button', { name: 'Copy key', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(key);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download key', exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('keyguard-license.txt');
  expect(await readFile((await download.path())!, 'utf8')).toBe(`${key}\n`);
  expect(backend.creates()).toBe(1);
  expect(page.url()).not.toContain(claimToken);
  expect(await page.evaluate(() => Object.values(localStorage).join(''))).not.toContain(claimToken);
});

test('cancel resumes the same transaction and refresh never starts another purchase', async ({ page }) => {
  const backend = await mockCheckout(page);
  await openPurchase(page);
  await emit(page, 'checkout.closed');
  await page.getByRole('button', { name: 'Continue checkout', exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __paddleOpens: unknown[] }).__paddleOpens.length)).toBe(2);
  const options = await page.evaluate(() => (window as unknown as { __paddleOpens: unknown[] }).__paddleOpens);
  expect(options).toEqual([
    { transactionId, settings: { displayMode: 'overlay', theme: 'light' } },
    { transactionId, settings: { displayMode: 'overlay', theme: 'light' } },
  ]);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Continue checkout', exact: true })).toBeVisible();
  expect(backend.creates()).toBe(1);
  await expect(page.getByRole('button', { name: 'Buy lifetime license', exact: true })).toBeHidden();
});

test('failed payment remains resumable and never exposes a key', async ({ page }) => {
  await mockCheckout(page);
  await openPurchase(page);
  await emit(page, 'checkout.payment.failed');
  await expect(page.getByRole('heading', { name: 'Payment was not completed' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue checkout', exact: true })).toBeVisible();
  await expect(page.getByLabel('Your license key')).toBeHidden();
});

test('expired and refunded sessions direct the buyer to support without another payment', async ({ page }) => {
  const backend = await mockCheckout(page);
  await openPurchase(page);
  backend.setStatusCode(410);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'This checkout session is no longer available' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue checkout', exact: true })).toBeHidden();
  await expect(page.getByLabel('Your license key')).toBeHidden();
  backend.setStatusCode(200);
  backend.setStatus('refunded');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'This purchase does not grant Premium' })).toBeVisible();
  expect(backend.creates()).toBe(1);
});

test('network errors preserve the claim for a retry', async ({ page }) => {
  const backend = await mockCheckout(page);
  await openPurchase(page);
  backend.setStatusCode(503);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Could not check your payment' })).toBeVisible();
  backend.setStatus('active');
  backend.setStatusCode(200);
  await page.getByRole('button', { name: 'Check payment status', exact: true }).click();
  await expect(page.getByLabel('Your license key')).toHaveValue(key);
  expect(backend.creates()).toBe(1);
});

test('polling stops after three minutes and can be retried without creating a purchase', async ({ page }) => {
  const backend = await mockCheckout(page);
  await page.clock.install();
  await openPurchase(page);
  await emit(page, 'checkout.completed');
  for (let i = 0; i < 37; i += 1) {
    await page.clock.runFor(5_000);
  }
  await expect(page.getByRole('button', { name: 'Check payment status', exact: true })).toBeVisible();
  const reads = backend.reads();
  await page.clock.runFor(60_000);
  expect(backend.reads()).toBe(reads);
  backend.setStatus('active');
  await page.getByRole('button', { name: 'Check payment status', exact: true }).click();
  await expect(page.getByLabel('Your license key')).toHaveValue(key);
  expect(backend.creates()).toBe(1);
});

test('a back/forward cache restore resumes polling', async ({ page }) => {
  const backend = await mockCheckout(page);
  await page.clock.install();
  await openPurchase(page);
  await emit(page, 'checkout.closed');
  await expect(page.getByRole('button', { name: 'Continue checkout', exact: true })).toBeVisible();
  const reads = backend.reads();
  backend.setStatus('active');
  await page.evaluate(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
  });
  await expect.poll(() => backend.reads()).toBeGreaterThan(reads);
  await expect(page.getByLabel('Your license key')).toHaveValue(key);
  expect(backend.creates()).toBe(1);
});

test('script failure prevents transaction creation', async ({ page }) => {
  const backend = await mockCheckout(page);
  await page.route('https://cdn.paddle.com/paddle/v2/paddle.js', (route) => route.abort());
  await page.goto('/purchase/');
  await page.getByRole('button', { name: 'Buy lifetime license', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Could not start checkout' })).toBeVisible();
  expect(backend.creates()).toBe(0);
});

test('payment links cannot open a transaction without its saved private claim', async ({ page }) => {
  const backend = await mockCheckout(page);
  await page.goto(`/purchase/?_ptxn=${transactionId}`);
  await expect(page.getByRole('heading', { name: 'Open your original checkout tab' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Buy lifetime license', exact: true })).toBeHidden();
  expect(page.url()).not.toContain('_ptxn');
  expect(await page.evaluate(() => Boolean(window.PaddleBillingV1))).toBe(false);
  expect(backend.creates()).toBe(0);
});

test('purchase page stays within the viewport in light and dark themes', async ({ page }) => {
  await mockCheckout(page);
  await page.goto('/purchase/');
  await expect(page.locator('[data-price]')).toHaveText('$15.00');
  for (const width of [320, 412, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.evaluate((value) => document.documentElement.classList.toggle('dark', value === 'dark'), theme);
      await expect(page.getByRole('heading', { name: 'Lifetime license', exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    }
  }
});

test('localized price preview works with checkout disabled and creates no purchase', async ({ page }) => {
  const backend = await mockCheckout(page, 'pending', { total: '¥1,200', currency: 'JPY' });
  await page.route('**/purchase/', async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, body: (await response.text()).replace('</body>', `
      <script>
        const purchaseRoot = document.querySelector('[data-purchase]');
        purchaseRoot.dataset.config = JSON.stringify({ ...JSON.parse(purchaseRoot.dataset.config), enabled: false });
      </script></body>
    `) });
  });
  await page.goto('/purchase/');
  await expect(page.locator('[data-price]')).toHaveText('¥1,200');
  await expect(page.locator('[data-price-currency]')).toHaveText('JPY');
  await expect(page.getByRole('button', { name: 'Buy lifetime license', exact: true })).toBeDisabled();
  const requests = await page.evaluate(() => (window as unknown as { __pricePreviews: unknown[] }).__pricePreviews);
  expect(requests).toEqual([{ items: [{ priceId: 'pri_01m5hxzxpvqr0v4d454hpq6cvs', quantity: 1 }] }]);
  expect(await page.evaluate(() => (window as unknown as { __paddleOpens: unknown[] }).__paddleOpens)).toEqual([]);
  expect(backend.creates()).toBe(0);
});

for (const mode of ['error', 'mismatch'] as const) {
  test(`a price ${mode} can be retried without affecting checkout`, async ({ page }) => {
    const backend = await mockCheckout(page, 'pending', { mode });
    await page.goto('/purchase/');
    await expect(page.getByRole('button', { name: 'Retry price', exact: true })).toBeVisible();
    await expect(page.locator('[data-price-message]')).toHaveText('Price unavailable');
    await expect(page.locator('[data-price]')).toBeEmpty();
    await expect(page.getByRole('button', { name: 'Buy lifetime license', exact: true })).toBeEnabled();
    await page.evaluate(() => { (window as unknown as { __priceMode: string }).__priceMode = 'success'; });
    await page.getByRole('button', { name: 'Retry price', exact: true }).click();
    await expect(page.locator('[data-price]')).toHaveText('$15.00');
    await expect(page.getByRole('button', { name: 'Retry price', exact: true })).toBeHidden();
    expect(backend.creates()).toBe(0);
  });
}

test('a price request that never responds times out and can be retried', async ({ page }) => {
  const backend = await mockCheckout(page, 'pending', { mode: 'pending' });
  await page.clock.install();
  await page.goto('/purchase/');
  await expect.poll(() => page.evaluate(() => (window as unknown as { __pricePreviews?: unknown[] }).__pricePreviews?.length ?? 0)).toBe(1);
  await expect(page.locator('[data-price-message]')).toHaveText('Loading price…');
  await page.clock.runFor(15_001);
  await expect(page.getByRole('button', { name: 'Retry price', exact: true })).toBeVisible();
  await expect(page.locator('[data-price-status]')).toHaveAttribute('aria-busy', 'false');
  await page.evaluate(() => { (window as unknown as { __priceMode: string }).__priceMode = 'success'; });
  await page.getByRole('button', { name: 'Retry price', exact: true }).click();
  await expect(page.locator('[data-price]')).toHaveText('$15.00');
  expect(backend.creates()).toBe(0);
});

test('price loading and checkout share one Paddle initialization', async ({ page }) => {
  let releaseSdk!: () => void;
  const sdkReady = new Promise<void>((resolve) => { releaseSdk = resolve; });
  const backend = await mockCheckout(page, 'pending', { sdkReady });
  await page.goto('/purchase/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('[data-price-message]')).toHaveText('Loading price…');
  await page.getByRole('button', { name: 'Buy lifetime license', exact: true }).click();
  releaseSdk();
  await expect(page.locator('[data-price]')).toHaveText('$15.00');
  await expect.poll(() => page.evaluate(() => (window as unknown as { __paddleOpens?: unknown[] }).__paddleOpens?.length ?? 0)).toBe(1);
  expect(await page.evaluate(() => (window as unknown as { __paddleInitializations: number }).__paddleInitializations)).toBe(1);
  expect(backend.creates()).toBe(1);
});
