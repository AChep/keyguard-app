// Uses only Node built-ins so the deployment job needs no package installation.
import assert from 'node:assert/strict';
import { setTimeout } from 'node:timers/promises';

const site = process.argv[2] ?? process.env.SITE_URL;
if (!site) throw new Error('Pass the deployed site URL as an argument or SITE_URL.');

async function fetchText(path, types) {
  const url = new URL(path, site);
  // Avoid a cached response from the previous Pages deployment.
  url.searchParams.set('agent-check', process.env.GITHUB_SHA ?? String(Date.now()));
  const response = await fetch(url, { signal: AbortSignal.timeout(15_000) });
  assert.equal(response.status, 200, `${url.pathname}: HTTP ${response.status}`);
  const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
  assert.ok(types.includes(type), `${url.pathname}: unexpected Content-Type ${type}`);
  return response.text();
}

async function check() {
  const index = await fetchText('/llms.txt', ['text/plain']);
  assert.match(index, /^# Keyguard\n/, 'Missing llms.txt title');
  const paths = [...index.matchAll(/^- \[[^\]]+\]\((https?:\/\/[^\s)]+\/index\.md)\): /gm)]
    .map((match) => new URL(match[1]).pathname);
  assert.ok(paths.length > 0, 'llms.txt has no Markdown links');
  // A normal page, a nested route, and both generated pages exercise distinct paths.
  const samples = new Set([
    paths[0],
    paths.find((path) => path.split('/').length > 4),
    '/docs/platform-support/index.md',
    '/docs/troubleshooting/index.md',
  ].filter(Boolean));
  for (const path of samples) {
    assert.ok(paths.includes(path), `${path}: not listed in llms.txt`);
    // Static hosts may serve Markdown as text/plain. Neither HTML nor a download
    // content type is useful for a text-fetching agent.
    const markdown = await fetchText(path, ['text/markdown', 'text/plain']);
    assert.match(markdown, /^---\n[\s\S]+?\n---\n\n# /, `${path}: not a Markdown article`);
    assert.match(markdown, /\nurl: https?:\/\//, `${path}: missing canonical URL`);
    if (path === '/docs/platform-support/index.md') assert.match(markdown, /\| Page\s*\|/, 'Missing platform matrix');
    if (path === '/docs/troubleshooting/index.md') assert.match(markdown, /\]\(https?:\/\/[^)]+#[^)]+\)/, 'Missing symptom links');
  }
  console.log(`Agent access works: llms.txt and ${samples.size} Markdown pages have readable content and MIME types.`);
}

// GitHub Pages and its CDN can take a short time to serve a new deployment.
for (let attempt = 1; attempt <= 5; attempt++) {
  try {
    await check();
    break;
  } catch (error) {
    if (attempt === 5) throw error;
    console.log(`Agent access check ${attempt}/5 failed: ${error.message}. Retrying in 10 seconds.`);
    await setTimeout(10_000);
  }
}
