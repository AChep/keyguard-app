import type { DownloadPlatform } from './consts';

// Per-platform accent colors on the Download page, in the style of FEATURE_VISUALS.
// Class strings are written as full literals so Tailwind's scanner keeps them.
export interface PlatformVisual {
  // Logo chip background.
  chip: string;
  // Logo color.
  ink: string;
  // Open buttons in the dialog.
  button: string;
}

export const PLATFORM_VISUALS: Record<DownloadPlatform['id'], PlatformVisual> = {
  android: {
    chip: 'bg-green-500/15',
    ink: 'text-green-600 dark:text-green-400',
    button: 'bg-green-500/15 text-green-800 hover:bg-green-500/25 dark:text-green-300',
  },
  'wear-os': {
    chip: 'bg-violet-500/15',
    ink: 'text-violet-600 dark:text-violet-400',
    button: 'bg-violet-500/15 text-violet-800 hover:bg-violet-500/25 dark:text-violet-300',
  },
  linux: {
    chip: 'bg-amber-500/15',
    ink: 'text-amber-600 dark:text-amber-400',
    button: 'bg-amber-500/15 text-amber-800 hover:bg-amber-500/25 dark:text-amber-300',
  },
  macos: {
    chip: 'bg-rose-500/15',
    ink: 'text-rose-600 dark:text-rose-400',
    button: 'bg-rose-500/15 text-rose-800 hover:bg-rose-500/25 dark:text-rose-300',
  },
  windows: {
    chip: 'bg-blue-500/15',
    ink: 'text-blue-600 dark:text-blue-400',
    button: 'bg-blue-500/15 text-blue-800 hover:bg-blue-500/25 dark:text-blue-300',
  },
};
