// Chromium extension versions, which both the Chrome Web Store and Edge
// Add-ons use: scripts/cws-version.ts and scripts/edge-version.ts compare the
// store's version with the release's.

/** Whether a string is a Chrome extension version: 1 to 4 dot-separated integers. */
export function isVersion(value: string): boolean {
  return /^\d+(\.\d+){0,3}$/.test(value);
}

/** Compares two extension versions as Chrome does, with missing parts as 0: 1.2 = 1.2.0 < 1.10. */
export function compareVersions(a: string, b: string): number {
  for (const version of [a, b]) {
    if (!isVersion(version)) throw new Error(`Not an extension version: ${version}`);
  }
  const x = a.split(".").map(Number);
  const y = b.split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const difference = (x[i] ?? 0) - (y[i] ?? 0);
    if (difference !== 0) return Math.sign(difference);
  }
  return 0;
}
