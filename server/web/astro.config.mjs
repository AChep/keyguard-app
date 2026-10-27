// @ts-check
import { defineConfig, fontProviders } from 'astro/config';
import mdx from '@astrojs/mdx';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';
import { unified } from '@astrojs/markdown-remark';
import remarkDirective from 'remark-directive';
import remarkAsides from './src/lib/remark-asides.mjs';

export default defineConfig({
  site: 'https://keyguard.dev',
  // GitHub Pages serves directory-style output and redirects `/page` -> `/page/`.
  // Pin both so dev, the sitemap, canonical URLs, and internal links all agree.
  trailingSlash: 'always',
  build: {
    format: 'directory',
  },
  integrations: [mdx(), sitemap()],
  // Merged or renamed docs. Released apps and old links keep these URLs, so
  // never remove an entry.
  redirects: {
    '/docs/qr-codes/': '/docs/items/#qr-codes-and-large-type',
    '/docs/large-type/': '/docs/items/#qr-codes-and-large-type',
  },
  markdown: {
    // `:::note` and `:::caution` callouts, see CONTRIBUTING.md.
    processor: unified({ remarkPlugins: [remarkDirective, remarkAsides] }),
  },
  image: {
    layout: 'constrained',
    responsiveStyles: true,
  },
  fonts: [
    {
      // Google, not Fontsource: only Google serves the opsz axis, which gives
      // large headings the display cut instead of the small-text one.
      provider: fontProviders.google(),
      name: 'DM Sans',
      cssVariable: '--font-dm-sans',
      // DM Sans is a variable font; request its full wght axis as a single file.
      weights: ['100 1000'],
      styles: ['normal'],
      subsets: ['latin', 'latin-ext'],
      options: { experimental: { variableAxis: { opsz: [['9', '40']] } } },
    },
    {
      provider: fontProviders.google(),
      name: 'Inter',
      cssVariable: '--font-inter',
      weights: ['100 900'],
      styles: ['normal'],
      subsets: ['latin', 'latin-ext'],
    },
  ],
  vite: {
    plugins: [tailwindcss()],
  },
});
