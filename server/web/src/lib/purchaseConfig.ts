export interface PurchaseConfig {
  enabled: boolean;
  previewEnabled: boolean;
  environment: 'sandbox' | 'production';
  clientToken: string;
  priceId: string;
  apiUrl: string;
  legalLinks: { label: string; href: string }[];
}

/** Only public browser configuration belongs here; checkout prices are still chosen by the API. */
export function readPurchaseConfig(env: Record<string, string | undefined>): PurchaseConfig {
  const environment = env.PUBLIC_PADDLE_ENVIRONMENT === 'production' ? 'production' : 'sandbox';
  const clientToken = env.PUBLIC_PADDLE_CLIENT_TOKEN?.trim() ?? '';
  const priceId = env.PUBLIC_PADDLE_PRICE_ID?.trim() ?? '';
  const apiUrl = validUrl(env.PUBLIC_LICENSE_API_URL, environment === 'sandbox')?.replace(/\/$/, '') ?? '';
  const legalLinks = [
    { label: 'Terms', href: validUrl(env.PUBLIC_PURCHASE_TERMS_URL) },
    { label: 'Privacy', href: validUrl(env.PUBLIC_PURCHASE_PRIVACY_URL) },
    { label: 'Refund policy', href: validUrl(env.PUBLIC_PURCHASE_REFUND_URL) },
  ].filter((link): link is { label: string; href: string } => Boolean(link.href));
  const environmentValid = ['sandbox', 'production'].includes(env.PUBLIC_PADDLE_ENVIRONMENT ?? '');
  const tokenValid = clientToken.startsWith(environment === 'production' ? 'live_' : 'test_');

  return {
    enabled: env.PUBLIC_PURCHASE_ENABLED === 'true' && environmentValid && tokenValid && Boolean(apiUrl)
      && (environment === 'sandbox' || legalLinks.length === 3),
    previewEnabled: environmentValid && tokenValid && /^pri_[a-z0-9]{26}$/.test(priceId),
    environment,
    clientToken,
    priceId,
    apiUrl,
    legalLinks,
  };
}

function validUrl(value: string | undefined, allowLocalhost = false): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    const secure = url.protocol === 'https:';
    const local = allowLocalhost && url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    if ((!secure && !local) || url.username || url.password || url.hash || url.search) return undefined;
    return url.href;
  } catch {
    return undefined;
  }
}
