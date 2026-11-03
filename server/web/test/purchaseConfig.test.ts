import assert from 'node:assert/strict';
import test from 'node:test';
import { readPurchaseConfig } from '../src/lib/purchaseConfig.ts';

const sandbox = {
  PUBLIC_PURCHASE_ENABLED: 'true',
  PUBLIC_PADDLE_ENVIRONMENT: 'sandbox',
  PUBLIC_PADDLE_CLIENT_TOKEN: 'test_public-token',
  PUBLIC_PADDLE_PRICE_ID: 'pri_01m5hxzxpvqr0v4d454hpq6cvs',
  PUBLIC_LICENSE_API_URL: 'http://localhost:5001',
};
const live = {
  ...sandbox,
  PUBLIC_PADDLE_ENVIRONMENT: 'production',
  PUBLIC_PADDLE_CLIENT_TOKEN: 'live_public-token',
  PUBLIC_PADDLE_PRICE_ID: 'pri_01m5s3vbbsx21j1x0w01bc0j7z',
  PUBLIC_LICENSE_API_URL: 'https://license.keyguard.dev',
  PUBLIC_PURCHASE_TERMS_URL: 'https://keyguard.dev/terms/',
  PUBLIC_PURCHASE_PRIVACY_URL: 'https://keyguard.dev/privacy/',
  PUBLIC_PURCHASE_REFUND_URL: 'https://keyguard.dev/refunds/',
};

test('checkout defaults to unavailable and requires explicit configuration', () => {
  assert.equal(readPurchaseConfig({}).enabled, false);
  assert.equal(readPurchaseConfig(sandbox).enabled, true);
  assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PURCHASE_ENABLED: 'false' }).enabled, false);
  assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PADDLE_ENVIRONMENT: '' }).enabled, false);
  assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PADDLE_CLIENT_TOKEN: 'live_wrong-environment' }).enabled, false);
});

test('live purchases require approved HTTPS policy locations and a matching public token', () => {
  assert.equal(readPurchaseConfig(live).enabled, true);
  for (const field of ['PUBLIC_PURCHASE_TERMS_URL', 'PUBLIC_PURCHASE_PRIVACY_URL', 'PUBLIC_PURCHASE_REFUND_URL']) {
    assert.equal(readPurchaseConfig({ ...live, [field]: '' }).enabled, false);
    assert.equal(readPurchaseConfig({ ...live, [field]: 'javascript:alert(1)' }).enabled, false);
  }
  assert.equal(readPurchaseConfig({ ...live, PUBLIC_PADDLE_CLIENT_TOKEN: 'test_wrong-environment' }).enabled, false);
});

test('API URL rejects credentials and insecure non-local origins', () => {
  for (const url of ['https://user:secret@example.com', 'http://example.com', 'https://example.com/?token=secret']) {
    assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_LICENSE_API_URL: url }).enabled, false);
  }
  assert.equal(readPurchaseConfig({ ...live, PUBLIC_LICENSE_API_URL: 'http://localhost:5001' }).enabled, false);
  assert.equal(readPurchaseConfig({ ...live, PUBLIC_LICENSE_API_URL: 'https://license.keyguard.dev/' }).apiUrl, 'https://license.keyguard.dev');
});

test('price preview works without checkout, backend access, or purchase policies', () => {
  const config = readPurchaseConfig({
    PUBLIC_PADDLE_ENVIRONMENT: 'production',
    PUBLIC_PADDLE_CLIENT_TOKEN: 'live_public-token',
    PUBLIC_PADDLE_PRICE_ID: 'pri_01m5s3vbbsx21j1x0w01bc0j7z',
  });
  assert.equal(config.enabled, false);
  assert.equal(config.previewEnabled, true);
  assert.equal(config.priceId, 'pri_01m5s3vbbsx21j1x0w01bc0j7z');
});

test('price preview requires a valid price ID and matching environment credentials', () => {
  assert.equal(readPurchaseConfig({}).previewEnabled, false);
  for (const priceId of ['', 'pro_01m5s3vbbsx21j1x0w01bc0j7z', 'pri_invalid']) {
    assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PADDLE_PRICE_ID: priceId }).previewEnabled, false);
  }
  assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PADDLE_ENVIRONMENT: '' }).previewEnabled, false);
  assert.equal(readPurchaseConfig({ ...sandbox, PUBLIC_PADDLE_CLIENT_TOKEN: 'live_wrong-environment' }).previewEnabled, false);
});
