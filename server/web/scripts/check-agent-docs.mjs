// Validate coverage and content against both the source collection and rendered
// HTML. This catches missing generated content as well as lossy conversions.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { toString } from 'mdast-util-to-string';
import remarkFrontmatter from 'remark-frontmatter';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import { unified } from 'unified';
import { visit } from 'unist-util-visit';
import { parse } from 'yaml';
import { ALL_PLATFORMS_EXPANSION } from '../src/consts.ts';
import { elements, filesUnder, htmlParser, nodeText, readDoc } from './lib/agent-docs.mjs';

const webRoot = fileURLToPath(new URL('../', import.meta.url));
const dist = join(webRoot, 'dist');
const source = join(webRoot, 'src/content/docs');
const parser = unified().use(remarkParse).use(remarkGfm).use(remarkFrontmatter);
const indexText = readFileSync(join(dist, 'llms.txt'), 'utf8');
const index = parser.parse(indexText);
const links = [];
visit(index, 'link', (node) => { links.push(node); });
const sources = filesUnder(source, '.md');
assert.equal(links.length, sources.length, 'llms.txt must list every doc exactly once');
assert.equal(new Set(links.map((link) => link.url)).size, links.length, 'Duplicate llms.txt URLs');
const normalize = (value) => value.replace(/\s+/g, ' ').trim();
const mdNodes = (tree, type) => {
  const nodes = [];
  visit(tree, type, (node) => { nodes.push(node); });
  return nodes;
};

for (const path of sources) {
  const id = relative(source, path).replace(/\.md$/, '').split('\\').join('/');
  const original = parser.parse(readFileSync(path, 'utf8'));
  const expected = parse(original.children[0].value);
  const doc = readDoc(join(dist, 'docs', id, 'index.html'));
  assert.ok(doc, `${id}: missing rendered article`);
  const markdownPath = join(dirname(doc.path), 'index.md');
  assert.ok(existsSync(markdownPath), `${id}: missing Markdown export`);
  const markdown = readFileSync(markdownPath, 'utf8');
  const tree = parser.parse(markdown);
  assert.equal(tree.children[0].type, 'yaml', `${id}: missing metadata`);
  const metadata = parse(tree.children[0].value);
  const platforms = expected.platforms === 'all' ? [...ALL_PLATFORMS_EXPANSION] : expected.platforms;
  assert.deepEqual(metadata, {
    title: expected.title,
    description: expected.description,
    url: doc.metadata.url,
    platforms,
    premium: expected.premium ?? false,
    ...(expected.stage ? { stage: expected.stage } : {}),
  }, `${id}: metadata differs from the source`);
  const markdownURL = `${metadata.url}index.md`;
  const indexLink = links.find((link) => link.url === markdownURL);
  assert.equal(indexLink && toString(indexLink), expected.title, `${id}: missing llms.txt link`);
  assert.ok(indexText.includes(expected.description), `${id}: missing index description`);
  assert.ok(elements(doc.tree, (node) => node.tagName === 'link' && node.properties.rel?.includes('alternate') &&
    node.properties.type === 'text/markdown' && new URL(node.properties.href, metadata.url).href === markdownURL).length,
  `${id}: missing Markdown discovery link`);
  assert.ok(elements(doc.tree, (node) => node.tagName === 'link' && node.properties.rel?.includes('describedby') &&
    new URL(node.properties.href, metadata.url).pathname === '/llms.txt').length, `${id}: missing llms.txt discovery link`);

  const headings = elements(doc.article, (node) => /^h[1-6]$/.test(node.tagName));
  assert.deepEqual(mdNodes(tree, 'heading').map((node) => [node.depth, normalize(toString(node))]),
    headings.map((node) => [Number(node.tagName[1]), normalize(nodeText(node))]), `${id}: headings changed`);
  const code = elements(doc.article, (node) => node.tagName === 'pre');
  assert.deepEqual(mdNodes(tree, 'code').map((node) => [node.lang, node.value]),
    code.map((node) => [node.properties.dataLanguage ?? null, nodeText(node).replace(/\n+$/, '')]), `${id}: code blocks changed`);
  const rows = elements(doc.article, (node) => node.tagName === 'tr');
  if (expected.generated === 'platform-support') {
    assert.ok(rows.length > 1, `${id}: generated platform matrix is empty`);
  }
  if (expected.generated === 'troubleshooting-index') {
    assert.ok(mdNodes(tree, 'link').some((node) => new URL(node.url).hash), `${id}: generated symptom index is empty`);
  }
  assert.deepEqual(mdNodes(tree, 'tableRow').map((row) => row.children.map((cell) => normalize(toString(cell))).filter(Boolean)),
    rows.map((row) => elements(row, (node) => node.tagName === 'td' || node.tagName === 'th').map((cell) => normalize(nodeText(cell))).filter(Boolean)),
    `${id}: table rows changed`);
  assert.deepEqual(mdNodes(tree, 'blockquote').map((node) => normalize(toString(node))),
    elements(doc.article, (node) => node.tagName === 'aside' || node.tagName === 'blockquote').map((node) => normalize(nodeText(node))),
    `${id}: warnings or quotations changed`);
  for (const summary of elements(doc.article, (node) => node.tagName === 'summary')) {
    assert.ok(normalize(toString(tree)).includes(normalize(nodeText(summary))), `${id}: missing collapsed section summary`);
  }
  assert.deepEqual(mdNodes(tree, 'image').map((node) => [node.url, node.alt]),
    elements(doc.article, (node) => node.tagName === 'img').map((node) => [new URL(node.properties.src, metadata.url).href, node.properties.alt ?? '']),
    `${id}: images changed`);
  assert.equal(mdNodes(tree, 'html').length, 0, `${id}: HTML leaked into the export`);
  assert.ok(!markdown.includes('Copy Markdown'), `${id}: copy controls leaked into the export`);
  visit(tree, (node) => {
    if (node.type !== 'link' && node.type !== 'image') return;
    const url = new URL(node.url); // Relative links and image URLs must have been resolved.
    if (url.origin !== new URL(metadata.url).origin) return;
    const target = join(dist, decodeURIComponent(url.pathname), url.pathname.endsWith('/') ? 'index.html' : '');
    assert.ok(existsSync(target), `${id}: broken exported URL ${node.url}`);
    if (url.hash && target.endsWith('.html')) {
      const html = htmlParser.parse(readFileSync(target, 'utf8'));
      assert.ok(elements(html, (element) => element.properties.id === decodeURIComponent(url.hash.slice(1))).length,
        `${id}: broken exported fragment ${node.url}`);
    }
  });
}
console.log(`Checked ${sources.length} Markdown exports, metadata, generated content, links and discovery tags.`);
