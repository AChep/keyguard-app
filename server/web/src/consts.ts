// Site-wide constants. Edit product copy and links here.

export const SITE = {
  name: 'Keyguard',
  tagline: "Secure password manager that's also nice to use",
  // The line under the home page title. `description` is the meta description.
  subtitle: 'A Bitwarden and KeePass client for Android, Linux, macOS, and Windows.',
  description:
    'Keyguard is a client for the Bitwarden platform and KeePass (KDBX) files. It autofills your logins, supports passkeys, works offline, and runs a Watchtower that finds leaked and reused passwords and other issues.',
};

export const NAV = [
  { label: 'Features', href: '/features/' },
  { label: 'Docs', href: '/docs/' },
];

// Docs sidebar sections, in display order. The ids are the `section`
// frontmatter values allowed by the docs collection schema in content.config.ts.
// See CONTRIBUTING.md for what goes where.
export const DOC_SECTION_IDS = [
  'get-started',
  'accounts',
  'vault',
  'autofill',
  'security',
  'developer',
  'backup',
  'platforms',
  'reference',
  'help',
] as const;
export type DocSectionId = (typeof DOC_SECTION_IDS)[number];

const DOC_SECTION_LABELS: Record<DocSectionId, string> = {
  'get-started': 'Get started',
  accounts: 'Accounts and sync',
  vault: 'Vault',
  autofill: 'Autofill and passkeys',
  security: 'Security',
  developer: 'Developer tools',
  backup: 'Backup and transfer',
  platforms: 'Platforms',
  reference: 'Reference',
  help: 'Help',
};

export const DOC_SECTIONS = DOC_SECTION_IDS.map((id) => ({ id, label: DOC_SECTION_LABELS[id] }));

export const DOC_TYPES = ['tutorial', 'feature', 'how-to', 'concept', 'reference', 'troubleshooting'] as const;
export type DocType = (typeof DOC_TYPES)[number];

// Platforms a doc can declare in its `platforms` frontmatter, in display order.
export const DOC_PLATFORM_IDS = ['android', 'wear-os', 'ios', 'linux', 'macos', 'windows'] as const;
export type DocPlatformId = (typeof DOC_PLATFORM_IDS)[number];

const DOC_PLATFORM_LABELS: Record<DocPlatformId, string> = {
  android: 'Android',
  'wear-os': 'Wear OS',
  ios: 'iOS',
  linux: 'Linux',
  macos: 'macOS',
  windows: 'Windows',
};

export const DOC_PLATFORMS = DOC_PLATFORM_IDS.map((id) => ({ id, label: DOC_PLATFORM_LABELS[id] }));

// What `platforms: all` means: every shipped phone and desktop platform. Wear OS
// has a limited feature set, so pages list it explicitly.
export const ALL_PLATFORMS_EXPANSION: readonly DocPlatformId[] = ['android', 'linux', 'macos', 'windows'];

export const DOC_STAGE_LABELS = {
  beta: 'Beta',
  experimental: 'Experimental',
} as const;

// Labels for docs subsections — a subdirectory of src/content/docs becomes a
// nested group in the sidebar, e.g. docs/bitwarden/*.md → "Bitwarden".
export const DOC_SUBSECTIONS: Record<string, string> = {
  bitwarden: 'Bitwarden',
};

export const REPO_URL = 'https://github.com/AChep/keyguard-app';
export const RELEASES_URL = 'https://github.com/AChep/keyguard-app/releases/latest';

// Download options per platform. `stores` render as links; `commands` render as
// copyable terminal blocks; `direct` points at the raw binary on the releases page.
export interface DownloadStore {
  label: string;
  href: string;
}
export interface DownloadCommand {
  label: string;
  code: string;
}
export interface DownloadPlatform {
  name: string;
  note: string;
  stores: DownloadStore[];
  commands: DownloadCommand[];
  direct?: { label: string; href: string };
}

export const PLATFORMS: DownloadPlatform[] = [
  {
    name: 'Android & Wear OS',
    note: 'Phone, tablet & Wear OS',
    stores: [
      {
        label: 'Google Play',
        href: 'https://play.google.com/store/apps/details?id=com.artemchep.keyguard',
      },
      {
        label: 'F-Droid repo',
        href: 'https://gh.artemchep.com/keyguard-repo-fdroid/repo',
      },
    ],
    commands: [],
    direct: { label: 'APK', href: RELEASES_URL },
  },
  {
    name: 'Linux',
    note: 'Flatpak, AppImage & Arch',
    stores: [
      { label: 'Flathub', href: 'https://flathub.org/apps/com.artemchep.keyguard' },
    ],
    commands: [{ label: 'Arch Linux (AUR)', code: 'yay -S keyguard-bin' }],
    direct: { label: 'Flatpak & AppImage', href: RELEASES_URL },
  },
  {
    name: 'macOS',
    note: 'Apple & Intel',
    stores: [],
    commands: [{ label: 'Homebrew', code: 'brew install --cask keyguard' }],
    direct: { label: 'DMG', href: RELEASES_URL },
  },
  {
    name: 'Windows',
    note: 'MSI installer & Microsoft Store',
    stores: [
      { label: 'Microsoft Store', href: 'https://apps.microsoft.com/detail/9NN4SMXJN5SF' },
    ],
    commands: [
      { label: 'WinGet', code: 'winget install --id ArtemChepurnyi.Keyguard' },
      { label: 'Scoop', code: 'scoop bucket add extras\nscoop install keyguard' },
    ],
    direct: { label: 'MSI', href: RELEASES_URL },
  },
];
