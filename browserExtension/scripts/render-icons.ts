// Renders the toolbar and store icons, public/icon/<size>.png, and their source,
// ../artwork/browser-extension-icon.svg, from the logo of src/lib/ui/logo.ts.
// See BUILDING.md: `pnpm render:icons`.
//
// Chromium draws them, through Playwright: its own build (`pnpm exec playwright
// install --only-shell chromium`), or the Chrome or Chromium at CHROME_PATH.
import { writeFile } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";
import { LOGO_GRADIENT_STOPS, LOGO_PATH_COLORS, LOGO_PATHS, LOGO_TILE, LOGO_TILE_RADIUS } from "../src/lib/ui/logo.ts";

const root = path.join(import.meta.dirname, "..");

/**
 * The icons and their tiles, in pixels. The rounded tile fills the small ones;
 * the store's 128 px icon has 96 px of art in 16 px of transparent padding.
 */
const ICONS = [
  { size: 16, tile: 16 },
  { size: 32, tile: 32 },
  { size: 48, tile: 48 },
  { size: 128, tile: 96 },
] as const;

/** The logo on its tile, with [tile] of [size] pixels for the tile. */
function svg(size: number, tile: number): string {
  const { x, y, size: units } = LOGO_TILE;
  const margin = ((size - tile) / 2) * (units / tile);
  const viewBox = [x - margin, y - margin, units + 2 * margin, units + 2 * margin].join(" ");
  const stops = LOGO_GRADIENT_STOPS.map(([offset, color]) => `<stop offset="${offset}%" stop-color="${color}"/>`);
  const paths = LOGO_PATHS.map((d, index) => `<path fill="${LOGO_PATH_COLORS[index]}" fill-rule="evenodd" d="${d}"/>`);
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${viewBox}">`,
    `<defs><linearGradient id="tile" x1="0" y1="0" x2="0" y2="1">${stops.join("")}</linearGradient></defs>`,
    `<rect x="${x}" y="${y}" width="${units}" height="${units}" rx="${units * LOGO_TILE_RADIUS}" fill="url(#tile)"/>`,
    ...paths,
    "</svg>",
    "",
  ].join("\n");
}

await writeFile(path.join(root, "../artwork/browser-extension-icon.svg"), svg(128, 128));

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  for (const { size, tile } of ICONS) {
    await page.setContent(`<body style="margin: 0">${svg(size, tile)}</body>`);
    const file = path.join(root, `public/icon/${size}.png`);
    await page.locator("svg").screenshot({ path: file, omitBackground: true });
    console.log(path.relative(root, file));
  }
} finally {
  await browser.close();
}
