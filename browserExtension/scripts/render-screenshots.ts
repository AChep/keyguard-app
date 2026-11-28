// Renders the screenshots of the extension from the scenes in ./screenshots/:
// the built pages of .output/chrome-mv3 with sample data, in a drawn browser
// window. Each scene makes the Chrome Web Store's, with a headline, in
// ../screenshots/chrome-web-store/, and the website's, the window alone, in
// ../server/web/src/assets/screenshots/; one makes the website's device
// carousel's too. See BUILDING.md: `pnpm render:screenshots`.
//
// Chromium draws them, through Playwright, as for ./render-icons.ts. The pages
// use the system font and the platform's shortcut keys: render them on macOS.
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import http from "node:http";
import type { AddressInfo } from "node:net";
import path from "node:path";
import { chromium, type Page } from "@playwright/test";
import {
  LOGO_GRADIENT_CSS,
  LOGO_PATH_COLORS,
  LOGO_PATHS,
  LOGO_TILE_RADIUS,
  LOGO_VIEW_BOX,
} from "../src/lib/ui/logo.ts";

const root = path.join(import.meta.dirname, "..");
const extension = path.join(root, ".output/chrome-mv3");
const scenes = path.join(import.meta.dirname, "screenshots");

interface Scene {
  page: string;
  /** The website's file name. */
  web: string;
  /** The file name of the website's device carousel, for the scene there. */
  carousel?: string;
  colorScheme: "light" | "dark";
  /** Before the screenshot, once the page loaded. */
  prepare?: (page: Page) => Promise<void>;
}

const SCENES: Scene[] = [
  {
    page: "1-autofill.html",
    web: "browser-extension-inline-menu.png",
    carousel: "browser.png",
    colorScheme: "light",
    // The first row selected, as by the keyboard, for the card beside the menu.
    prepare: async (page) => {
      const menu = page.frames().find((frame) => new URL(frame.url()).pathname === "/inline-menu.html");
      if (menu === undefined) throw new Error("No inline menu");
      await menu.focus(".row");
    },
  },
  { page: "2-popup.html", web: "browser-extension-popup.png", colorScheme: "dark" },
  { page: "3-save.html", web: "browser-extension-save-bar.png", colorScheme: "light" },
];

interface Variant {
  /** The scene's query: `?plain` and `?window` are the window alone, see ./screenshots/frame.css. */
  query: string;
  viewport: { width: number; height: number };
  deviceScaleFactor: number;
  /** Whether the height fits the scene's content, [FIT_MARGIN] below its lowest box, in place of the viewport's. */
  fit: boolean;
  /** A transparent page, in place of a 24-bit PNG, which the store takes. */
  transparent: boolean;
  /** `null` for a scene without this variant. */
  file: (scene: Scene, index: number) => string | null;
}

const VARIANTS: Variant[] = [
  // The store's size, in 24-bit PNGs.
  {
    query: "",
    viewport: { width: 1280, height: 800 },
    deviceScaleFactor: 1,
    fit: false,
    transparent: false,
    file: (_scene, index) => path.join(root, `../screenshots/chrome-web-store/keyguard-chrome-${index + 1}.png`),
  },
  // As wide as the store's window, nothing cut off; sharp on dense screens.
  {
    query: "?plain",
    viewport: { width: 1120, height: 1200 },
    deviceScaleFactor: 2,
    fit: true,
    transparent: false,
    file: (scene) => path.join(root, "../server/web/src/assets/screenshots", scene.web),
  },
  // The carousel's laptop screen shows it at most 960px wide: about as wide for its height as the desktop screenshot.
  {
    query: "?window",
    viewport: { width: 1296, height: 1200 },
    deviceScaleFactor: 1,
    fit: true,
    transparent: true,
    file: (scene) =>
      scene.carousel === undefined ? null : path.join(root, "../server/web/src/assets/screenshots", scene.carousel),
  },
];

const FIT_MARGIN = 32;

/** For ./screenshots/frame.js: the logo of the inline icon. */
const LOGO_SCRIPT = `window.LOGO = ${JSON.stringify({
  viewBox: LOGO_VIEW_BOX,
  paths: LOGO_PATHS,
  colors: LOGO_PATH_COLORS,
  gradient: LOGO_GRADIENT_CSS,
  radius: LOGO_TILE_RADIUS,
})};\n`;

const TYPES: Record<string, string> = {
  ".css": "text/css",
  ".html": "text/html",
  ".js": "text/javascript",
  ".json": "application/json",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".wasm": "application/wasm",
};

/** The color type of a PNG's IHDR chunk: 2 for 8-bit RGB, without alpha. */
const PNG_COLOR_TYPE_OFFSET = 25;
const PNG_RGB = 2;

try {
  await access(path.join(extension, "manifest.json"));
} catch {
  throw new Error("No .output/chrome-mv3: run `pnpm build` first");
}

// The extension at /, the scenes at /shots/: the pages load their scripts by absolute paths.
const server = http.createServer((request, response) => {
  void (async () => {
    const { pathname } = new URL(request.url ?? "/", "http://localhost");
    try {
      const body =
        pathname === "/shots/logo.js"
          ? LOGO_SCRIPT
          : await readFile(
              pathname.startsWith("/shots/")
                ? path.join(scenes, pathname.slice("/shots/".length))
                : path.join(extension, pathname),
            );
      response.writeHead(200, { "content-type": TYPES[path.extname(pathname)] ?? "application/octet-stream" });
      response.end(body);
    } catch {
      response.writeHead(404);
      response.end();
    }
  })();
});
await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
try {
  for (const [index, scene] of SCENES.entries()) {
    for (const variant of VARIANTS) {
      const file = variant.file(scene, index);
      if (file === null) continue;
      const context = await browser.newContext({
        viewport: variant.viewport,
        deviceScaleFactor: variant.deviceScaleFactor,
        colorScheme: scene.colorScheme,
        reducedMotion: "reduce",
        locale: "en-US",
      });
      await context.addInitScript({ path: path.join(scenes, "stub-api.js") });
      const page = await context.newPage();
      const errors: string[] = [];
      page.on("pageerror", (error) => errors.push(error.message));
      page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
      });

      await page.goto(`${origin}/shots/${scene.page}${variant.query}`, { waitUntil: "networkidle" });
      await scene.prepare?.(page);
      // The pages' entry animations end.
      await page.waitForTimeout(700);
      if (errors.length > 0) throw new Error(`${scene.page}:\n${errors.join("\n")}`);

      if (variant.fit) {
        // The site's boxes and the extension's: not the site itself, which fills the viewport.
        const bottom = await page.evaluate(() =>
          Math.max(
            ...[...document.querySelectorAll(".site > *, .popup-frame, .extension-popup")].map(
              (element) => element.getBoundingClientRect().bottom,
            ),
          ),
        );
        await page.setViewportSize({ width: variant.viewport.width, height: Math.ceil(bottom) + FIT_MARGIN });
      }

      const png = await page.screenshot({ omitBackground: variant.transparent });
      if (!variant.transparent && png[PNG_COLOR_TYPE_OFFSET] !== PNG_RGB) {
        throw new Error(`${scene.page}: not an RGB PNG`);
      }
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, png);
      console.log(path.relative(root, file));
      await context.close();
    }
  }
} finally {
  await browser.close();
  server.close();
}
