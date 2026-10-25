import { initializePaddle, type Paddle, type PaddleEventData } from '@paddle/paddle-js';
import type { PurchaseConfig } from './purchaseConfig';

interface CheckoutSession {
  checkoutId: string;
  claimToken: string;
  transactionId: string;
  expiresAt: string;
  apiUrl: string;
  paymentSubmitted: boolean;
}
interface CheckoutStatus {
  status: 'pending' | 'active' | 'refunded' | 'revoked' | 'invalid';
  licenseKey?: string;
}

const STORAGE_KEY = 'keyguard.paddle.checkout.v1';
const POLL_WINDOW_MS = 180_000;
const REQUEST_TIMEOUT_MS = 15_000;

class ApiError extends Error {
  constructor(readonly status: number) {
    super('Checkout request failed');
  }
}

/** Payment events only prompt a server check. They never grant a license. */
export function mountPurchase(root: HTMLElement): void {
  const config = JSON.parse(root.dataset.config ?? '{}') as PurchaseConfig;
  // Paddle automatically opens payment-link transactions during initialization.
  // Only a checkout whose claim secret is already saved in this tab is payable.
  const pageUrl = new URL(window.location.href);
  const linkedTransactionId = pageUrl.searchParams.get('_ptxn');
  if (pageUrl.searchParams.has('_ptxn')) {
    pageUrl.searchParams.delete('_ptxn');
    history.replaceState(null, '', pageUrl);
  }
  const element = <T extends HTMLElement>(selector: string): T => {
    const value = root.querySelector<T>(selector);
    if (!value) throw new Error(`Missing purchase element: ${selector}`);
    return value;
  };
  const start = element<HTMLButtonElement>('[data-start]');
  const resume = element<HTMLButtonElement>('[data-resume]');
  const retry = element<HTMLButtonElement>('[data-retry]');
  const title = element('[data-status-title]');
  const message = element('[data-status-message]');
  const license = element('[data-license]');
  const licenseKey = element<HTMLTextAreaElement>('[data-license-key]');
  const reference = element('[data-reference]');
  const copyStatus = element('[data-copy-status]');
  const priceStatus = element('[data-price-status]');
  const price = element('[data-price]');
  const priceCurrency = element('[data-price-currency]');
  const priceMessage = element('[data-price-message]');
  const priceRetry = element<HTMLButtonElement>('[data-price-retry]');
  let session: CheckoutSession | undefined;
  let paddlePromise: Promise<Paddle> | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let deadline = 0;
  let attempts = 0;
  let checking = false;
  let terminal = false;
  let canResume = false;

  function status(heading: string, detail: string): void {
    title.textContent = heading;
    message.textContent = detail;
  }

  function stopPolling(): void {
    clearTimeout(timer);
    timer = undefined;
  }

  function saveSession(value: CheckoutSession): void {
    // This must succeed before opening payment. Never send the claim token to Paddle.
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  }

  function setSession(value: CheckoutSession): void {
    session = value;
    start.hidden = true;
    reference.hidden = false;
    reference.textContent = `Payment reference: ${value.transactionId}`;
  }

  function showRecovery(heading: string, detail: string): void {
    terminal = true;
    stopPolling();
    resume.hidden = true;
    retry.hidden = true;
    license.hidden = true;
    licenseKey.value = '';
    status(heading, detail);
  }

  async function post(path: string, body: unknown): Promise<unknown> {
    const response = await fetch(`${config.apiUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!response.ok) throw new ApiError(response.status);
    return response.json();
  }

  function scheduleCheck(): void {
    stopPolling();
    const remaining = deadline - Date.now();
    if (terminal) return;
    if (remaining <= 0) {
      retry.hidden = false;
      status('Payment is still being confirmed', 'Check again in a moment. If you have paid, keep your receipt and do not start another purchase.');
      return;
    }
    const delay = attempts < 6 ? 5_000 : attempts < 12 ? 10_000 : 15_000;
    timer = setTimeout(() => {
      if (Date.now() >= deadline) scheduleCheck();
      else void checkStatus();
    }, Math.min(delay, remaining));
  }

  async function checkStatus(): Promise<CheckoutStatus['status'] | undefined> {
    if (!session || terminal || checking) return undefined;
    if (Date.parse(session.expiresAt) <= Date.now()) {
      showRecovery('This checkout session has expired', 'If you paid, contact support with your Paddle receipt to recover your key.');
      return undefined;
    }
    checking = true;
    retry.disabled = true;
    attempts += 1;
    try {
      const result = await post(
        '/v1/checkout/paddle/status',
        { checkoutId: session.checkoutId, claimToken: session.claimToken },
      ) as CheckoutStatus;
      if (result.status === 'active' && typeof result.licenseKey === 'string' && result.licenseKey.startsWith('KG2A')) {
        terminal = true;
        stopPolling();
        resume.hidden = true;
        retry.hidden = true;
        licenseKey.value = result.licenseKey;
        license.hidden = false;
        status('Your lifetime license is ready', 'Copy or download your key now. It is not sent by email.');
      } else if (result.status === 'pending') {
        resume.hidden = !(canResume && config.enabled && !session.paymentSubmitted);
        scheduleCheck();
      } else if (result.status === 'refunded' || result.status === 'revoked' || result.status === 'invalid') {
        showRecovery('This purchase does not grant Premium', 'Contact support with your Paddle receipt if you need help with this payment.');
      } else {
        throw new Error('Unexpected checkout status');
      }
      return result.status;
    } catch (error) {
      if (error instanceof ApiError && [403, 410].includes(error.status)) {
        showRecovery('This checkout session is no longer available', 'If you paid, contact support with your Paddle receipt to recover your key.');
      } else {
        resume.hidden = true;
        retry.hidden = false;
        status('Could not check your payment', 'Check your connection and try again. Your existing checkout is saved in this tab.');
        scheduleCheck();
      }
      return undefined;
    } finally {
      checking = false;
      retry.disabled = false;
    }
  }

  function beginPolling(): void {
    stopPolling();
    deadline = Date.now() + POLL_WINDOW_MS;
    attempts = 0;
    retry.hidden = true;
    void checkStatus();
  }

  function onPaddleEvent(event: PaddleEventData): void {
    if (!session || terminal) return;
    if (event.name === 'checkout.completed' || event.name === 'checkout.payment.initiated') {
      session.paymentSubmitted = true;
      try { saveSession(session); } catch { /* The session was saved before checkout opened. */ }
      canResume = false;
      resume.hidden = true;
      status('Confirming your payment', 'Keep this tab open while your license is prepared. Payment confirmation can take a few minutes.');
      beginPolling();
    } else if (event.name === 'checkout.closed') {
      canResume = !session.paymentSubmitted;
      status('Checking your payment', 'Your checkout is saved in this tab. Payment is verified before a license key appears.');
      beginPolling();
    } else if (['checkout.error', 'checkout.failed', 'checkout.payment.failed', 'checkout.payment.error'].includes(event.name ?? '')) {
      session.paymentSubmitted = false;
      try { saveSession(session); } catch { /* Keep the original saved checkout. */ }
      canResume = true;
      status('Payment was not completed', 'You can continue the same checkout after its status is checked.');
      beginPolling();
    }
  }

  async function withTimeout<T>(request: Promise<T>): Promise<T> {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        request,
        new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error('Paddle timed out')), REQUEST_TIMEOUT_MS); }),
      ]);
    } finally {
      clearTimeout(timeout);
    }
  }

  function loadPaddle(): Promise<Paddle> {
    if (!paddlePromise) {
      paddlePromise = withTimeout(initializePaddle({
        token: config.clientToken,
        environment: config.environment,
        eventCallback: onPaddleEvent,
      })).then((loaded) => {
        if (!loaded) throw new Error('Paddle did not load');
        return loaded;
      }).catch((error) => {
        paddlePromise = undefined;
        throw error;
      });
    }
    return paddlePromise;
  }

  async function loadPrice(): Promise<void> {
    if (!config.previewEnabled || priceRetry.disabled) return;
    priceRetry.disabled = true;
    priceRetry.hidden = true;
    priceStatus.setAttribute('aria-busy', 'true');
    priceMessage.textContent = 'Loading price…';
    try {
      const sdk = await loadPaddle();
      const preview = await withTimeout(sdk.PricePreview({ items: [{ priceId: config.priceId, quantity: 1 }] }));
      const item = preview.data.details.lineItems.find((item) => item.price.id === config.priceId);
      if (!item?.formattedTotals.total?.trim()) throw new Error('Price unavailable');
      price.textContent = item.formattedTotals.total;
      priceCurrency.textContent = preview.data.currencyCode;
      priceMessage.textContent = 'Estimated total. Final price at checkout.';
    } catch {
      price.textContent = '';
      priceCurrency.textContent = '';
      priceMessage.textContent = 'Price unavailable';
      priceRetry.hidden = false;
    } finally {
      priceStatus.setAttribute('aria-busy', 'false');
      priceRetry.disabled = false;
    }
  }

  priceRetry.addEventListener('click', () => { void loadPrice(); });

  async function openCheckout(): Promise<void> {
    if (!session || terminal) return;
    try {
      const sdk = await loadPaddle();
      canResume = false;
      resume.hidden = true;
      sdk.Checkout.open({
        transactionId: session.transactionId,
        settings: {
          displayMode: 'overlay',
          theme: document.documentElement.classList.contains('dark') ? 'dark' : 'light',
        },
      });
      beginPolling();
    } catch {
      canResume = true;
      resume.hidden = false;
      retry.hidden = false;
      status('Could not open checkout', 'Reload this page and continue the saved checkout. No new purchase is needed.');
    }
  }

  start.addEventListener('click', async () => {
    if (!config.enabled || session || start.disabled) return;
    start.disabled = true;
    status('Opening checkout', 'Keep this tab open to receive your license key after payment.');
    try {
      // Detect blocked storage and an unavailable SDK before creating a transaction.
      sessionStorage.setItem(`${STORAGE_KEY}.check`, '1');
      sessionStorage.removeItem(`${STORAGE_KEY}.check`);
      await loadPaddle();
      const result = await post('/v1/checkout/paddle', {});
      if (!isSession(result)) throw new Error('Invalid checkout session');
      const value = { ...result, apiUrl: config.apiUrl, paymentSubmitted: false };
      saveSession(value);
      setSession(value);
      await openCheckout();
    } catch {
      status('Could not start checkout', 'Check your connection, allow storage for this site, then reload this page to try again.');
      start.disabled = false;
    }
  });

  resume.addEventListener('click', async () => {
    if (!config.enabled || !session || session.paymentSubmitted || terminal || resume.disabled) return;
    resume.disabled = true;
    stopPolling();
    deadline = Date.now() + POLL_WINDOW_MS;
    attempts = 0;
    const current = await checkStatus();
    if (current === 'pending') await openCheckout();
    resume.disabled = false;
  });

  retry.addEventListener('click', () => {
    if (checking) return;
    status('Checking your payment', 'Keep this tab open while your payment status is checked.');
    beginPolling();
  });

  element<HTMLButtonElement>('[data-copy]').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(licenseKey.value);
      copyStatus.textContent = 'Key copied. Save it in a secure place.';
    } catch {
      licenseKey.focus();
      licenseKey.select();
      copyStatus.textContent = 'Select and copy the key from the field above.';
    }
  });

  element<HTMLButtonElement>('[data-download]').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([`${licenseKey.value}\n`], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'keyguard-license.txt';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1_000);
    copyStatus.textContent = 'Keep the downloaded key in a secure place.';
  });

  try {
    const stored: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null');
    if (isSession(stored) && 'apiUrl' in stored && stored.apiUrl === config.apiUrl
      && (!linkedTransactionId || linkedTransactionId === stored.transactionId)) {
      const value = { ...stored, apiUrl: config.apiUrl, paymentSubmitted: 'paymentSubmitted' in stored && stored.paymentSubmitted === true };
      setSession(value);
      canResume = !value.paymentSubmitted;
      status('Checking your payment', 'Your checkout was restored. Payment is verified before a license key appears.');
      beginPolling();
    }
  } catch {
    // A browser that blocks storage still gets the explanatory error on purchase.
  }
  if (linkedTransactionId && !session) {
    start.hidden = true;
    showRecovery('Open your original checkout tab', 'This payment link does not contain your private checkout session. If you already paid, contact support with your Paddle receipt.');
  } else {
    // Previewing a price never creates a transaction or enables checkout.
    void loadPrice();
  }
  start.disabled = !config.enabled;
  window.addEventListener('pagehide', stopPolling);
  // Resume after a back/forward cache restore; pagehide stopped the timer.
  window.addEventListener('pageshow', (event) => {
    if (event.persisted && session && !terminal) beginPolling();
  });
}

function isSession(value: unknown): value is Omit<CheckoutSession, 'apiUrl' | 'paymentSubmitted'> {
  if (!value || typeof value !== 'object') return false;
  const fields = value as Record<string, unknown>;
  return ['checkoutId', 'claimToken', 'transactionId'].every((key) => typeof fields[key] === 'string' && fields[key].length > 0)
    && typeof fields.expiresAt === 'string' && Number.isFinite(Date.parse(fields.expiresAt));
}
