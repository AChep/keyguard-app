// Checks that every keyguard.dev docs and features URL used outside the
// website (app sources, README) points at a built page and an existing
// heading. Released app builds keep their URLs forever, so a renamed page or
// heading must never break them. Run after `pnpm build`.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(webRoot, 'dist');
const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: webRoot, encoding: 'utf8' }).trim();

if (!existsSync(dist)) {
  console.error('dist/ is missing. Run `pnpm build` first.');
  process.exit(1);
}

let output = '';
try {
  output = execFileSync(
    'git',
    [
      'grep', '--untracked', '-n', '-o', '-E',
      // A POSIX bracket expression: `]` first, then `[`, both literal.
      'https://keyguard\\.dev/(docs|features)[^]["\'<>(){}` \t]*',
      '--', '.', ':!server/web',
    ],
    { cwd: repoRoot, encoding: 'utf8' },
  );
} catch (e) {
  // git grep exits with 1 when nothing matches.
  if (e.status !== 1) throw e;
}

const errors = [];
const urls = new Map();
for (const line of output.split('\n').filter(Boolean)) {
  const match = line.match(/^(.+?):(\d+):(.+)$/);
  if (!match) continue;
  const [, file, lineNo, raw] = match;
  const url = raw.replace(/[.,;:]+$/, '');
  if (!urls.has(url)) urls.set(url, []);
  urls.get(url).push(`${file}:${lineNo}`);
}

for (const [url, refs] of urls) {
  const { pathname, hash } = new URL(url);
  const where = refs.join(', ');
  if (!pathname.endsWith('/')) {
    errors.push(`${url} has no trailing slash (${where})`);
    continue;
  }
  const page = join(dist, pathname, 'index.html');
  if (!existsSync(page)) {
    errors.push(`${url} has no page (${where})`);
    continue;
  }
  const html = readFileSync(page, 'utf8');
  if (/<meta\s+http-equiv="refresh"/i.test(html)) {
    errors.push(`${url} is a redirect; link the target page instead (${where})`);
    continue;
  }
  const anchor = decodeURIComponent(hash.slice(1));
  if (anchor && !html.includes(`id="${anchor}"`)) {
    errors.push(`${url} has no heading with id "${anchor}" (${where})`);
  }
}

if (errors.length > 0) {
  console.error(errors.map((e) => `✗ ${e}`).join('\n'));
  process.exit(1);
}
console.log(`✓ ${urls.size} keyguard.dev URLs resolve.`);
