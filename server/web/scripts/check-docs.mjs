// Structure checks for the docs, the rules in CONTRIBUTING.md that the
// frontmatter schema can't express. Prints `file:line: message` and exits
// with 1 on any error.
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';
import { toString } from 'mdast-util-to-string';
import remarkDirective from 'remark-directive';
import remarkFrontmatter from 'remark-frontmatter';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import { unified } from 'unified';
import { visit } from 'unist-util-visit';
import { parse as parseYaml } from 'yaml';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const docsRoot = join(webRoot, 'src/content/docs');

const WORD_LIMITS = { feature: 1200, 'how-to': 600, concept: 800 };
const MAX_CALLOUTS = 2;
const BANNED_HEADINGS = ['see also', 'related', 'further reading', 'next steps'];

const processor = unified().use(remarkParse).use(remarkGfm).use(remarkFrontmatter).use(remarkDirective);

function listDocs(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return listDocs(path);
    return entry.name.endsWith('.md') ? [path] : [];
  });
}

const docs = listDocs(docsRoot).map((path) => {
  const source = readFileSync(path, 'utf8');
  const tree = processor.parse(source);
  const yamlNode = tree.children.find((node) => node.type === 'yaml');
  const data = yamlNode ? parseYaml(yamlNode.value) ?? {} : {};
  const slug = relative(docsRoot, path).replace(/\.md$/, '');
  // Heading ids as Astro generates them (github-slugger over the heading text).
  const slugger = new GithubSlugger();
  const ids = new Map();
  visit(tree, 'heading', (node) => {
    const text = toString(node);
    ids.set(slugger.slug(text), text);
  });
  return { path, slug, source, tree, data, ids };
});
const bySlug = new Map(docs.map((doc) => [doc.slug, doc]));

const normalizeText = (text) => String(text).toLowerCase().replace(/[‘’]/g, "'").trim();

const errors = [];
const report = (doc, node, message) => {
  const line = node?.position?.start.line ?? 1;
  errors.push(`${relative(webRoot, doc.path)}:${line}: ${message}`);
};

for (const doc of docs) {
  const { tree, data } = doc;
  const body = tree.children.filter((node) => node.type !== 'yaml');

  // Headings
  const h2s = [];
  const headings = [];
  visit(tree, 'heading', (node) => {
    headings.push(node);
    const text = toString(node).trim();
    if (node.depth === 1) report(doc, node, 'H1 in the body; the title is the H1');
    if (node.depth >= 4) report(doc, node, `H${node.depth}; use H2 and H3 only`);
    if (/^\d+[.)]?\s/.test(text)) report(doc, node, `Numbered heading "${text}"`);
    if (text.includes('&')) report(doc, node, `"&" in heading "${text}"; write "and"`);
    if (node.children.some((child) => child.type === 'link')) report(doc, node, `Link in heading "${text}"`);
    if (BANNED_HEADINGS.includes(text.toLowerCase())) {
      report(doc, node, `Hand-written "${text}" section; use the related frontmatter`);
    }
    if (data.title && text.toLowerCase() === String(data.title).toLowerCase()) {
      report(doc, node, `Heading repeats the page title`);
    }
    if (node.depth === 2) h2s.push({ node, text });
  });
  // "Before you begin" is a smaller H3 right after the lead, before the first H2.
  const beforeIndex = headings.findIndex((node) => toString(node).trim() === 'Before you begin');
  if (beforeIndex >= 0) {
    const before = headings[beforeIndex];
    if (before.depth !== 3) report(doc, before, '"Before you begin" must be an H3 (###)');
    if (beforeIndex > 0) report(doc, before, '"Before you begin" must be the first heading, right after the lead');
  }
  const troubleIndex = h2s.findIndex((h) => h.text === 'Troubleshooting');
  if (troubleIndex >= 0 && troubleIndex !== h2s.length - 1) {
    report(doc, h2s[troubleIndex].node, '"Troubleshooting" must be the last H2');
  }

  // Callouts
  const isCallout = (node) => node?.type === 'containerDirective' && node.name !== 'details';
  const callouts = body.filter(isCallout);
  if (callouts.length > MAX_CALLOUTS) {
    report(doc, callouts[MAX_CALLOUTS], `${callouts.length} callouts; at most ${MAX_CALLOUTS} per page`);
  }
  body.forEach((node, i) => {
    if (isCallout(node) && isCallout(body[i + 1])) {
      report(doc, body[i + 1], 'Adjacent callouts');
    }
  });
  visit(tree, 'containerDirective', (container) => {
    visit(container, 'list', (list) => {
      if (list.ordered) report(doc, list, `Steps inside ":::${container.name}"`);
    });
  });
  visit(tree, 'blockquote', (node) => {
    if (/^\s*(\*\*|_|\*)?(note|tip|warning|caution|important|example)\b/i.test(toString(node))) {
      report(doc, node, 'Note-style blockquote; use :::note or :::caution');
    }
  });

  // Concept pages explain; they hold no steps.
  if (data.type === 'concept') {
    visit(tree, 'list', (node) => {
      if (node.ordered) report(doc, node, 'Numbered list on a concept page');
    });
  }

  // Length, counted on prose only.
  const limit = WORD_LIMITS[data.type];
  if (limit && !data.generated) {
    const words = body
      .filter((node) => node.type !== 'code' && node.type !== 'html')
      .map((node) => {
        const copy = structuredClone(node);
        visit(copy, 'code', (code) => {
          code.value = '';
        });
        return toString(copy);
      })
      .join(' ')
      .split(/\s+/)
      .filter((word) => /\w/.test(word)).length;
    if (words > limit) report(doc, body[0], `${words} words; ${data.type} pages have at most ${limit}`);
  }

  // Text outside code
  visit(tree, 'text', (node) => {
    if (node.value.includes('→')) report(doc, node, 'Use ">" in menu paths, not "→"');
  });

  // Links
  visit(tree, 'link', (node) => {
    const url = node.url;
    if (/^(https:\/\/keyguard\.dev)?\/features\//.test(url)) {
      report(doc, node, `Link to a marketing page (${url}); link the doc instead`);
      return;
    }
    if (/^https:\/\/keyguard\.dev\/docs\//.test(url)) {
      report(doc, node, `Absolute keyguard.dev link (${url}); use /docs/…/`);
      return;
    }
    if (/^here$/i.test(toString(node).trim())) report(doc, node, 'Link text "here"');
    let target;
    let anchor;
    if (url.startsWith('#')) {
      target = doc;
      anchor = url.slice(1);
    } else if (url.startsWith('/docs/')) {
      const [path, hash] = url.split('#');
      if (!path.endsWith('/')) {
        report(doc, node, `Docs link without a trailing slash (${url})`);
        return;
      }
      const slug = path.slice('/docs/'.length, -1);
      if (slug === '') return;
      target = bySlug.get(slug);
      if (!target) {
        report(doc, node, `Link to a missing doc (${url})`);
        return;
      }
      anchor = hash;
    }
    if (!target) return;
    const heading = anchor && target.ids.get(decodeURIComponent(anchor));
    if (anchor && !heading) {
      report(doc, node, `Link to a missing heading (${url})`);
      return;
    }
    // Link text names the target: its title, or the heading it points to.
    const text = normalizeText(toString(node));
    const names = [target.data.title, heading].filter(Boolean).map(normalizeText);
    if (!names.includes(text)) {
      report(doc, node, `Link text "${toString(node)}" doesn't match "${heading || target.data.title}" (${url})`);
    }
  });
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  console.error(`\n${errors.length} problem(s). The rules are in CONTRIBUTING.md.`);
  process.exit(1);
}
console.log(`✓ ${docs.length} docs follow the structure rules.`);
