// Per-feature monochrome icon + colored icon-chip, keyed by feature slug (file id).
// Class strings are written as full literals so Tailwind's scanner keeps them.
export interface FeatureVisual {
  icon: string;
  chip: string;
  ink: string;
}

export const FEATURE_VISUALS: Record<string, FeatureVisual> = {
  'powerful-search': { icon: 'search', chip: 'bg-sky-500/15', ink: 'text-sky-600 dark:text-sky-400' },
  'multi-account': { icon: 'users', chip: 'bg-blue-500/15', ink: 'text-blue-600 dark:text-blue-400' },
  'offline-access': { icon: 'cloud-off', chip: 'bg-teal-500/15', ink: 'text-teal-600 dark:text-teal-400' },
  attachments: { icon: 'paperclip', chip: 'bg-amber-500/15', ink: 'text-amber-600 dark:text-amber-400' },
  'bulk-actions': { icon: 'list-checks', chip: 'bg-red-500/15', ink: 'text-red-600 dark:text-red-400' },
  'smart-conflict-resolution': { icon: 'merge', chip: 'bg-violet-500/15', ink: 'text-violet-600 dark:text-violet-400' },
  watchtower: { icon: 'shield', chip: 'bg-emerald-500/15', ink: 'text-emerald-600 dark:text-emerald-400' },
  passkeys: { icon: 'key', chip: 'bg-cyan-500/15', ink: 'text-cyan-600 dark:text-cyan-400' },
  'flexible-unlock': { icon: 'unlock', chip: 'bg-orange-500/15', ink: 'text-orange-600 dark:text-orange-400' },
  'ssh-agent': { icon: 'terminal', chip: 'bg-purple-500/15', ink: 'text-purple-600 dark:text-purple-400' },
  autofill: { icon: 'pen-line', chip: 'bg-green-500/15', ink: 'text-green-600 dark:text-green-400' },
  generator: { icon: 'wand', chip: 'bg-pink-500/15', ink: 'text-pink-600 dark:text-pink-400' },
  'shortcuts-placeholders': { icon: 'zap', chip: 'bg-yellow-500/15', ink: 'text-yellow-600 dark:text-yellow-400' },
  'data-export': { icon: 'upload', chip: 'bg-lime-500/15', ink: 'text-lime-600 dark:text-lime-400' },
  'automatic-backup': { icon: 'database', chip: 'bg-rose-500/15', ink: 'text-rose-600 dark:text-rose-400' },
};

export const DEFAULT_VISUAL: FeatureVisual = {
  icon: 'sparkles',
  chip: 'bg-primary-container',
  ink: 'text-on-primary-container',
};
