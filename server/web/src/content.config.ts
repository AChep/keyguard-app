import { defineCollection } from 'astro:content';
import { z } from 'astro/zod';
import { glob } from 'astro/loaders';

const docs = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/docs' }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    category: z.enum(['get-started', 'accounts', 'guides', 'reference', 'help']),
    order: z.number().default(0),
  }),
});

const features = defineCollection({
  loader: glob({ pattern: '**/*.mdx', base: './src/content/features' }),
  schema: z.object({
    title: z.string(),
    summary: z.string(),
    icon: z.string(),
    group: z.string(),
    order: z.number().default(0),
    featured: z.boolean().default(false),
    premium: z.boolean().default(false),
  }),
});

export const collections = { docs, features };
