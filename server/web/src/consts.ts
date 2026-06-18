// Site-wide constants. Edit product copy and links here.

export const SITE = {
  name: 'Keyguard',
  tagline: "Secure password manager that's also nice to use",
  description:
    'Keyguard is a third-party client for the Bitwarden platform and KeePass (KDBX) files. It autofills your logins, supports passkeys, works offline, and runs a Watchtower that finds leaked and reused passwords and other issues.',
};

export const NAV = [
  { label: 'Features', href: '/features/' },
  { label: 'Docs', href: '/docs/' },
];

// Docs sidebar groups, in display order. Ids match the `category` frontmatter
// values allowed by the docs collection schema in content.config.ts.
export const DOC_CATEGORIES = [
  { id: 'get-started', label: 'Get started' },
  { id: 'accounts', label: 'Accounts & sync' },
  { id: 'guides', label: 'Guides' },
  { id: 'reference', label: 'Reference' },
  { id: 'help', label: 'Help' },
] as const;

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
    note: 'Flatpak & Arch',
    stores: [
      { label: 'Flathub', href: 'https://flathub.org/apps/com.artemchep.keyguard' },
    ],
    commands: [{ label: 'Arch Linux (AUR)', code: 'yay -S keyguard-bin' }],
    direct: { label: 'Flatpak', href: RELEASES_URL },
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
    note: 'MSI installer',
    stores: [],
    commands: [
      { label: 'WinGet', code: 'winget install --id ArtemChepurnyi.Keyguard' },
      { label: 'Scoop', code: 'scoop bucket add extras\nscoop install keyguard' },
    ],
    direct: { label: 'MSI', href: RELEASES_URL },
  },
];
