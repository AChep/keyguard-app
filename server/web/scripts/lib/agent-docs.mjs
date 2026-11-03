import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import rehypeParse from 'rehype-parse';
import rehypeRemark from 'rehype-remark';
import remarkGfm from 'remark-gfm';
import remarkStringify from 'remark-stringify';
import { unified } from 'unified';
import { visit } from 'unist-util-visit';
import { stringify } from 'yaml';

export const htmlParser = unified().use(rehypeParse);

export function filesUnder(directory, suffix) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(path, suffix) : entry.name.endsWith(suffix) ? [path] : [];
  }).sort();
}

export function elements(tree, predicate) {
  const matches = [];
  visit(tree, 'element', (node) => {
    if (predicate(node)) matches.push(node);
  });
  return matches;
}

export const nodeText = (node) => node.type === 'text' ? node.value : (node.children ?? []).map(nodeText).join('');

export function readDoc(path) {
  const tree = htmlParser.parse(readFileSync(path, 'utf8'));
  const article = elements(tree, (node) => node.tagName === 'article' && 'dataPagefindBody' in node.properties)[0];
  // The docs index and redirect pages have no documentation article.
  if (!article) return undefined;
  const availability = elements(article, (node) => 'dataDocMeta' in node.properties)[0];
  const title = elements(article, (node) => node.tagName === 'h1')[0];
  const canonical = elements(tree, (node) => node.tagName === 'link' && node.properties.rel?.includes('canonical'))[0];
  const description = elements(tree, (node) => node.tagName === 'meta' && node.properties.name === 'description')[0];
  if (!availability || !title || !canonical || !description) throw new Error(`Incomplete documentation metadata: ${path}`);
  const { dataPlatforms, dataPremium, dataStage } = availability.properties;
  return {
    path,
    tree,
    article,
    metadata: {
      title: nodeText(title).trim(),
      description: description.properties.content,
      url: canonical.properties.href,
      platforms: JSON.parse(dataPlatforms),
      premium: dataPremium === 'true',
      ...(dataStage ? { stage: dataStage } : {}),
    },
  };
}

export function docMarkdown(doc) {
  const article = structuredClone(doc.article);
  visit(article, (node) => {
    if (node.children) {
      node.children = node.children.filter((child) => child.type !== 'comment' &&
        !(child.type === 'element' && ('dataAgentIgnore' in child.properties || 'dataDocMeta' in child.properties)));
    }
    if (node.type !== 'element') return;
    // Preserve warnings as blockquotes and expand collapsed lists into plain text.
    if (node.tagName === 'aside') node.tagName = 'blockquote';
    if (node.tagName === 'details') node.tagName = 'div';
    if (node.tagName === 'summary') {
      node.tagName = 'p';
      node.children = [{ type: 'element', tagName: 'strong', properties: {}, children: node.children }];
    }
    // Shiki stores the fence language on the pre, rather than the code element.
    if (node.tagName === 'pre' && node.properties.dataLanguage) {
      const code = node.children.find((child) => child.tagName === 'code');
      if (code) code.properties.className = [`language-${node.properties.dataLanguage}`];
    }
    for (const property of ['href', 'src']) {
      if (typeof node.properties[property] === 'string') {
        node.properties[property] = new URL(node.properties[property], doc.metadata.url).href;
      }
    }
  });
  const processor = unified().use(rehypeRemark).use(remarkGfm, { tablePipeAlign: false }).use(remarkStringify, {
    bullet: '-',
    fences: true,
    listItemIndent: 'one',
  });
  const markdown = processor.stringify(processor.runSync({ type: 'root', children: [article] }));
  return `---\n${stringify(doc.metadata)}---\n\n${markdown}`;
}
