// Export rendered articles so generated tables, callouts and optimized image
// URLs stay identical to the website. Run after astro build, before Pagefind.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { docMarkdown, filesUnder, readDoc } from './lib/agent-docs.mjs';

const dist = fileURLToPath(new URL('../dist/', import.meta.url));
const docs = filesUnder(join(dist, 'docs'), '.html').map(readDoc).filter(Boolean);
if (docs.length === 0) throw new Error('No built documentation articles found.');
for (const doc of docs) writeFileSync(join(dirname(doc.path), 'index.md'), docMarkdown(doc));
console.log(`Exported ${docs.length} documentation pages as Markdown.`);
