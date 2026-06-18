import type { APIRoute } from 'astro';

// Generated at build time so it always points at the configured `site` and the
// sitemap emitted by @astrojs/sitemap. Edit astro.config.mjs `site` to change.
const getRobotsTxt = (sitemapURL: URL) => `User-agent: *
Allow: /

Sitemap: ${sitemapURL.href}
`;

export const GET: APIRoute = ({ site }) => {
  const sitemapURL = new URL('sitemap-index.xml', site);
  return new Response(getRobotsTxt(sitemapURL), {
    headers: { 'Content-Type': 'text/plain' },
  });
};
