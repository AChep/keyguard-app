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

// Download options per platform, in the docs platform order. Each option is one
// row in the platform's dialog. `description` says what sets it apart, in one
// short line, and may hold Markdown links: [text](/docs/…/).
export interface DownloadOption {
  name: string;
  description?: string;
  href?: string;
  // The label of the `href` button.
  action?: string;
  command?: string;
  // Not the app itself, so it's left out of the card summary.
  addon?: boolean;
}
export interface DownloadPlatform {
  // Also the dialog id, so `/download/#linux` opens it.
  id: Exclude<DocPlatformId, 'ios'>;
  name: string;
  // Requirements or architectures, under the name in the dialog.
  subtitle: string;
  // Spans half of the row instead of a third.
  wide?: boolean;
  options: DownloadOption[];
}

const GOOGLE_PLAY_URL = 'https://play.google.com/store/apps/details?id=com.artemchep.keyguard';

export const PLATFORMS: DownloadPlatform[] = [
  {
    id: 'android',
    name: 'Android',
    subtitle: 'Android 8.0 or later',
    wide: true,
    options: [
      {
        name: 'Google Play',
        href: GOOGLE_PLAY_URL,
      },
      {
        name: 'F-Droid',
        description: "Extra repository; add it to your F-Droid client.",
        href: 'https://gh.artemchep.com/keyguard-repo-fdroid/repo',
      },
      {
        name: 'APK',
        href: RELEASES_URL,
      },
    ],
  },
  {
    id: 'wear-os',
    name: 'Wear OS',
    subtitle: 'Wear OS 3 or later',
    wide: true,
    options: [
      {
        name: 'Google Play',
        href: GOOGLE_PLAY_URL,
      },
      {
        name: 'APK',
        href: RELEASES_URL,
      },
    ],
  },
  {
    id: 'linux',
    name: 'Linux',
    subtitle: 'x86_64 and aarch64',
    options: [
      {
        name: 'Flathub',
        description:
          'Sandboxed. No [FIDO2 Web Authentication](/docs/bitwarden/two-factor-login/#limitations) for two-factor login.',
        href: 'https://flathub.org/apps/com.artemchep.keyguard',
      },
      {
        name: 'AUR',
        command: 'yay -S keyguard-bin',
      },
      {
        name: 'AppImage',
        href: RELEASES_URL,
      },
      {
        name: 'tar.gz',
        href: RELEASES_URL,
      },
    ],
  },
  {
    id: 'macos',
    name: 'macOS',
    subtitle: 'Apple silicon and Intel',
    options: [
      {
        name: 'Homebrew',
        command: 'brew install --cask keyguard',
      },
      {
        name: 'DMG',
        href: RELEASES_URL,
      },
    ],
  },
  {
    id: 'windows',
    name: 'Windows',
    subtitle: 'x64 and arm64',
    options: [
      {
        name: 'Microsoft Store',
        description: 'Can be the [Windows passkey provider](/docs/passkey-provider/).',
        href: 'https://apps.microsoft.com/detail/9NN4SMXJN5SF',
      },
      {
        name: 'WinGet',
        command: 'winget install --id ArtemChepurnyi.Keyguard',
      },
      {
        name: 'Scoop',
        command: 'scoop bucket add keyguard https://github.com/AChep/keyguard-repo-scoop\nscoop install keyguard/keyguard',
      },
      {
        name: 'MSI',
        href: RELEASES_URL,
      },
    ],
  },
];
