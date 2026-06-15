import { getCollection, type CollectionEntry } from 'astro:content';
import { DOC_CATEGORIES, DOC_SUBSECTIONS } from '../consts';

export interface DocSubsection {
  id: string;
  label: string;
  docs: CollectionEntry<'docs'>[];
}

/** A sidebar entry within a category: either a single doc or a subsection. */
export type DocEntry =
  | { kind: 'doc'; doc: CollectionEntry<'docs'> }
  | { kind: 'subsection'; subsection: DocSubsection };

export interface DocGroup {
  id: (typeof DOC_CATEGORIES)[number]['id'];
  label: string;
  entries: DocEntry[];
}

const byOrder = (a: CollectionEntry<'docs'>, b: CollectionEntry<'docs'>) =>
  (a.data.order ?? 0) - (b.data.order ?? 0) ||
  a.data.title.localeCompare(b.data.title);

/**
 * The docs collection grouped by category, in sidebar display order. Docs in
 * a subdirectory (e.g. bitwarden/self-hosted) form a nested subsection that
 * sorts among its siblings by the lowest `order` of its pages. Empty
 * categories are dropped so unfinished sections never render.
 */
export async function getDocGroups(): Promise<DocGroup[]> {
  const docs = (await getCollection('docs')).sort(byOrder);
  return DOC_CATEGORIES.map((category) => {
    const inCategory = docs.filter((doc) => doc.data.category === category.id);
    const subsections = new Map<string, DocSubsection>();
    const entries: { order: number; entry: DocEntry }[] = [];
    for (const doc of inCategory) {
      const slash = doc.id.indexOf('/');
      if (slash < 0) {
        entries.push({ order: doc.data.order ?? 0, entry: { kind: 'doc', doc } });
        continue;
      }
      const dir = doc.id.slice(0, slash);
      let subsection = subsections.get(dir);
      if (!subsection) {
        subsection = { id: dir, label: DOC_SUBSECTIONS[dir] ?? dir, docs: [] };
        subsections.set(dir, subsection);
        entries.push({
          order: doc.data.order ?? 0,
          entry: { kind: 'subsection', subsection },
        });
      }
      subsection.docs.push(doc);
    }
    entries.sort((a, b) => a.order - b.order);
    return {
      id: category.id,
      label: category.label,
      entries: entries.map((e) => e.entry),
    };
  }).filter((group) => group.entries.length > 0);
}
