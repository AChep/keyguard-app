import type { APIRoute } from 'astro';
import { SITE } from '../consts';
import { docMarkdownPath, getDocGroups } from '../lib/docs';

export const GET: APIRoute = async ({ site }) => {
  const groups = await getDocGroups();
  const sections = groups.map((group) => {
    const docs = group.entries.flatMap((entry) => entry.kind === 'doc' ? [entry.doc] : entry.subsection.docs);
    return [
      `## ${group.label}`,
      '',
      ...docs.map((doc) => `- [${doc.data.title}](${new URL(docMarkdownPath(doc.id), site).href}): ${doc.data.description}`),
    ].join('\n');
  });
  const text = [
    `# ${SITE.name}`,
    '',
    `> ${SITE.description}`,
    '',
    'These documentation pages describe setup, feature availability, limitations, and troubleshooting.',
    'Each Markdown page includes its canonical website URL, supported platforms, Premium requirement, and release stage when applicable.',
    '',
    ...sections.map((section) => `${section}\n`),
  ].join('\n');

  return new Response(text, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
};
