import { getCollection, getEntry, type CollectionEntry } from 'astro:content';
import {
  ALL_PLATFORMS_EXPANSION,
  DOC_PLATFORMS,
  DOC_SECTIONS,
  DOC_SUBSECTIONS,
  type DocPlatformId,
} from '../consts';

export interface DocSubsection {
  id: string;
  label: string;
  docs: CollectionEntry<'docs'>[];
}

/** A sidebar entry within a section: either a single doc or a subsection. */
export type DocEntry =
  | { kind: 'doc'; doc: CollectionEntry<'docs'> }
  | { kind: 'subsection'; subsection: DocSubsection };

export interface DocGroup {
  id: (typeof DOC_SECTIONS)[number]['id'];
  label: string;
  entries: DocEntry[];
}

const byOrder = (a: CollectionEntry<'docs'>, b: CollectionEntry<'docs'>) =>
  a.data.order - b.data.order || a.data.title.localeCompare(b.data.title);

/**
 * The docs collection grouped by section, in sidebar display order. Docs in
 * a subdirectory (e.g. bitwarden/self-hosted) form a nested subsection that
 * sorts among its siblings by the lowest `order` of its pages. Empty
 * sections are dropped so unfinished sections never render.
 */
export async function getDocGroups(): Promise<DocGroup[]> {
  const docs = (await getCollection('docs')).sort(byOrder);
  return DOC_SECTIONS.map((section) => {
    const inSection = docs.filter((doc) => doc.data.section === section.id);
    assertUniqueOrder(section.id, inSection);
    const subsections = new Map<string, DocSubsection>();
    const entries: { order: number; entry: DocEntry }[] = [];
    for (const doc of inSection) {
      const slash = doc.id.indexOf('/');
      if (slash < 0) {
        entries.push({ order: doc.data.order, entry: { kind: 'doc', doc } });
        continue;
      }
      const dir = doc.id.slice(0, slash);
      let subsection = subsections.get(dir);
      if (!subsection) {
        subsection = { id: dir, label: DOC_SUBSECTIONS[dir] ?? dir, docs: [] };
        subsections.set(dir, subsection);
        entries.push({
          order: doc.data.order,
          entry: { kind: 'subsection', subsection },
        });
      }
      subsection.docs.push(doc);
    }
    entries.sort((a, b) => a.order - b.order);
    return {
      id: section.id,
      label: section.label,
      entries: entries.map((e) => e.entry),
    };
  }).filter((group) => group.entries.length > 0);
}

/** Docs in sidebar order, flattened. */
export async function getDocsInOrder(): Promise<CollectionEntry<'docs'>[]> {
  return (await getDocGroups()).flatMap((group) =>
    group.entries.flatMap((entry) => (entry.kind === 'doc' ? [entry.doc] : entry.subsection.docs)),
  );
}

function assertUniqueOrder(section: string, docs: CollectionEntry<'docs'>[]) {
  const seen = new Map<number, string>();
  for (const doc of docs) {
    const other = seen.get(doc.data.order);
    if (other) {
      throw new Error(`Docs "${other}" and "${doc.id}" share order ${doc.data.order} in section "${section}".`);
    }
    seen.set(doc.data.order, doc.id);
  }
}

/** The platforms a doc declares, with `all` expanded, in display order. */
export function expandPlatforms(platforms: CollectionEntry<'docs'>['data']['platforms']): DocPlatformId[] {
  const ids: readonly DocPlatformId[] = platforms === 'all' ? ALL_PLATFORMS_EXPANSION : platforms;
  return DOC_PLATFORMS.map((p) => p.id).filter((id) => ids.includes(id));
}

export function platformLabel(id: DocPlatformId): string {
  return DOC_PLATFORMS.find((p) => p.id === id)!.label;
}

/** Resolves a docs reference, failing the build when it points nowhere. */
export async function resolveDoc(ref: { id: string }, from: string): Promise<CollectionEntry<'docs'>> {
  const doc = await getEntry('docs', ref.id);
  if (!doc) {
    throw new Error(`"${from}" references the missing doc "${ref.id}".`);
  }
  return doc;
}

/** The doc's `related` pages, failing the build on a missing or self reference. */
export async function resolveRelated(entry: CollectionEntry<'docs'>): Promise<CollectionEntry<'docs'>[]> {
  return Promise.all(
    entry.data.related.map((ref) => {
      if (ref.id === entry.id) {
        throw new Error(`"${entry.id}" lists itself in related.`);
      }
      return resolveDoc(ref, entry.id);
    }),
  );
}
