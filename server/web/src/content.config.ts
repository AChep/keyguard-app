import { defineCollection, reference } from 'astro:content';
import { z } from 'astro/zod';
import { glob } from 'astro/loaders';
import { DOC_PLATFORM_IDS, DOC_SECTION_IDS, DOC_TYPES } from './consts';

// The rules behind these fields are in CONTRIBUTING.md.
const docs = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/docs' }),
  schema: z
    .object({
      title: z.string().max(60).regex(/^[^&+→:]+$/, 'No &, +, → or : in titles'),
      description: z.string().min(50).max(160).endsWith('.', 'End the description with a period'),
      section: z.enum(DOC_SECTION_IDS),
      type: z.enum(DOC_TYPES),
      order: z.number().int().nonnegative().multipleOf(10),
      platforms: z.union([z.literal('all'), z.array(z.enum(DOC_PLATFORM_IDS)).min(1)]),
      premium: z.boolean().default(false),
      stage: z.enum(['beta', 'experimental']).optional(),
      related: z.array(reference('docs')).max(5).default([]),
      // A page whose body is generated from other pages' frontmatter.
      generated: z.enum(['platform-support', 'troubleshooting-index']).optional(),
    })
    .superRefine((doc, ctx) => {
      const fail = (message: string) => ctx.addIssue({ code: 'custom', message });
      if (doc.type === 'tutorial' && !doc.title.startsWith('Get started with ')) {
        fail('Tutorial titles start with "Get started with"');
      }
      if (doc.type === 'tutorial' && doc.section !== 'get-started') {
        fail('Tutorials live in the get-started section');
      }
      if (doc.type === 'troubleshooting' && !doc.title.startsWith('Troubleshoot ')) {
        fail('Troubleshooting titles start with "Troubleshoot"');
      }
      if (Array.isArray(doc.platforms) && new Set(doc.platforms).size !== doc.platforms.length) {
        fail('Duplicate platform');
      }
    }),
});

const features = defineCollection({
  loader: glob({ pattern: '**/*.mdx', base: './src/content/features' }),
  schema: z.object({
    title: z.string(),
    summary: z.string().max(160),
    icon: z.string(),
    group: z.string(),
    order: z.number().default(0),
    featured: z.boolean().default(false),
    // The doc that owns this feature's facts. Premium and platforms come from it.
    doc: reference('docs'),
  }),
});

export const collections = { docs, features };
