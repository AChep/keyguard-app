import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { defineConfig } from "wxt";
import { NOTICES_FILE, thirdPartyNotices } from "./scripts/third-party-notices";

// The add-on ID on Firefox, which is also the origin the app sees.
const GECKO_ID = "browser@keyguard.dev";

// A fixed public key for development builds (`pnpm dev`), so that the unpacked
// Chromium extension always gets the ID ngjnjkolmoemeghbpilkeiimhacfmkdp. Store
// builds must not carry a key. The same builds call the DEV native host, see
// NATIVE_HOST_NAME in src/lib/native/connection.ts.
const DEV_KEY =
  "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxUn9JWfFYECIEfD/UkyAemE4LJpYM7IiywYc5tnFnKW+K6HZiLxGDV5GJkZw7ue7" +
  "DS7bPAkNemVhpF9eircSKidlr+r6qgRAJ96SiH2CNqMhWJCP52F4ATbVKSDgBLfWnMkMo4nllgPGnpQXfDAJklqUzIbEXFfQLawfE0aKxSEz" +
  "rdQruC6jhk7G+MU2g68iL6GpBIS/g8dNz0yO/7bT/nG1ri9WxZ+QZNzvSqBUPPhGLnM7npvjnaGPAxVCFoet2Naaz5Ccx/c43s3tKawylZiH" +
  "xYOVOTUFtwrrvWS782lzKJBUpey2ioVYcUbe6D9V1d2+05Khk993y2Dgz6t4fQIDAQAB";

// The pages the extension works on.
const WEB_PAGES = ["http://*/*", "https://*/*"];

// The Wasm binary of util/crypto, copied next to the background script. The
// loader asks for it by this name, see src/lib/crypto/wasm.ts.
const CRYPTO_WASM_FILE = "keyguard_crypto.wasm";

/**
 * The extension's version is the app's: `appVersionName` of the Gradle version
 * catalog, read at build time so it can't go stale.
 */
function appVersionName(): string {
  const catalog = readFileSync(fileURLToPath(new URL("../gradle/libs.versions.toml", import.meta.url)), "utf8");
  const version = /^appVersionName\s*=\s*"([^"]*)"/m.exec(catalog)?.[1];
  if (version === undefined || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error(`gradle/libs.versions.toml: appVersionName must be major.minor.patch, got ${version}`);
  }
  return version;
}

function cryptoWasmPath(): string {
  const require = createRequire(import.meta.url);
  return join(dirname(require.resolve("keyguard-crypto-wasm")), CRYPTO_WASM_FILE);
}

/**
 * Emscripten's loader names its Wasm as `new URL("keyguard_crypto.wasm",
 * import.meta.url)`. Vite builds the background in library mode, which inlines
 * such an asset as Base64, so the binary would ship twice. The extension
 * always passes `locateFile`, which the loader asks first, so the fallback URL
 * can be a plain string.
 */
function cryptoWasmUrl(): Plugin {
  const fallback = `new URL("${CRYPTO_WASM_FILE}",import.meta.url).href`;
  return {
    name: "keyguard:crypto-wasm-url",
    enforce: "pre",
    // Tests in Node load the Wasm next to the module, without `locateFile`.
    apply: "build",
    transform(code, id) {
      if (!id.endsWith("/keyguard_crypto.mjs")) return null;
      if (!code.includes(fallback)) this.error(`keyguard_crypto.mjs no longer contains ${fallback}`);
      return code.replace(fallback, JSON.stringify(CRYPTO_WASM_FILE));
    },
  };
}

/**
 * The licenses an npm package bundled into the extension may have: the
 * permissive set of the native crypto, which ships in the same extension
 * (.github/native-crypto-deny.toml).
 */
const ALLOWED_LICENSES = new Set([
  "0BSD",
  "Apache-2.0",
  "BSD-1-Clause",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "CC0-1.0",
  "ISC",
  "MIT",
  "MIT-0",
  "NCSA",
  "Unicode-3.0",
  "Unlicense",
  "Zlib",
]);

/**
 * The directories of the npm packages that one `wxt build` bundles, for the
 * third-party notices. WXT builds the entrypoints in several Vite builds.
 */
const bundledPackages = new Set<string>();

/** The directory of the npm package of a module ID, or `null` for the extension's own code. */
function packageDirectory(id: string): string | null {
  const path = id.split("?")[0]!;
  const index = path.lastIndexOf("/node_modules/");
  if (path.startsWith("\0") || index < 0) return null;
  const segments = path.slice(index + "/node_modules/".length).split("/");
  const name = segments[0]!.startsWith("@") ? segments.slice(0, 2).join("/") : segments[0]!;
  return path.slice(0, index + "/node_modules/".length + name.length);
}

/**
 * Fails the build when it bundles an npm package under a license outside
 * ALLOWED_LICENSES, as licensee does for the app's Gradle dependencies.
 * Every license of an SPDX expression must be allowed, `OR` or not. The Wasm
 * crypto is Keyguard's own; its Rust dependencies are checked by cargo-deny.
 * It also collects the packages for the third-party notices.
 */
function licensePolicy(): Plugin {
  return {
    name: "keyguard:license-policy",
    apply: "build",
    generateBundle(_options, bundle) {
      const directories = new Set<string>();
      for (const output of Object.values(bundle)) {
        if (output.type !== "chunk") continue;
        for (const id of output.moduleIds) {
          const directory = packageDirectory(id);
          if (directory !== null) directories.add(directory);
        }
      }
      for (const directory of directories) {
        const { name, license } = JSON.parse(readFileSync(join(directory, "package.json"), "utf8")) as {
          name: string;
          license?: unknown;
        };
        const ids = (typeof license === "string" ? license.split(/[\s()]+/) : []).filter(
          (it) => it.length > 0 && it !== "AND" && it !== "OR",
        );
        if (ids.length === 0 || !ids.every((it) => ALLOWED_LICENSES.has(it))) {
          this.error(`${name} is bundled under ${JSON.stringify(license)}, which the license policy doesn't allow`);
        }
        bundledPackages.add(directory);
      }
    },
  };
}

/**
 * The CSP of the extension's pages and background: nothing but the
 * extension's own scripts, Wasm, styles and images. The crypto runs as
 * WebAssembly, fetched from the extension. No `frame-ancestors`: pages embed
 * the inline menu and the save bar.
 *
 * Site icons (src/lib/background/siteIcon.ts): Chromium serves them from the
 * extension's origin; on Firefox they're the tabs' `https:` or `data:` favicons.
 *
 * `wxt` (the dev server) adds its origin to `script-src`; its client also
 * opens a WebSocket and injects styles.
 */
function extensionPagesCsp(browser: string, devServer: boolean): string {
  const dev = (...sources: string[]) => (devServer ? sources.map((it) => ` ${it}`).join("") : "");
  return [
    "default-src 'none'",
    "script-src 'self' 'wasm-unsafe-eval'",
    `connect-src 'self'${dev("http://localhost:*", "ws://localhost:*")}`,
    `style-src 'self'${dev("'unsafe-inline'")}`,
    `img-src 'self'${browser === "firefox" ? " https: data:" : ""}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ]
    .join("; ")
    .concat(";");
}

export default defineConfig({
  modules: ["@wxt-dev/module-svelte"],
  // Explicit imports keep the sources readable outside WXT (tests, lint).
  imports: false,
  manifestVersion: 3,
  zip: {
    // The extension's version is the app's, not package.json's.
    artifactTemplate: "keyguard-{{version}}-{{browser}}.zip",
    // This directory alone doesn't build: AMO gets the source archive of the
    // release's tag instead, see BUILDING.md.
    zipSources: false,
  },
  vite: () => ({
    plugins: [cryptoWasmUrl(), licensePolicy()],
  }),
  manifest: ({ browser, mode, command }) => ({
    name: "__MSG_browser_extension_name__",
    description: "__MSG_browser_extension_description__",
    default_locale: "en",
    version: appVersionName(),
    permissions: [
      "nativeMessaging",
      "storage",
      // On a site the user withheld access to, the popup's fill and the
      // shortcut still read the tab's URL and inject the content script.
      "activeTab",
      "scripting",
      // The background copies values after an approval, see src/lib/background/clipboard.ts.
      "clipboardWrite",
      ...(browser === "firefox" ? [] : ["offscreen"]),
      // Chromium's favicons of the pages, for the site icons of logins. Firefox
      // has the tabs' `favIconUrl`. See src/lib/background/siteIcon.ts.
      ...(browser === "firefox" ? [] : ["favicon"]),
      // Firefox: the background reads the theme's colors for the popup, the
      // inline menu and the save bar, see src/lib/background/theme.ts. No
      // install warning.
      ...(browser === "firefox" ? ["theme"] : []),
    ],
    // The options page asks for it only to turn off the browser's own password
    // saving, see src/lib/options/browserSaving.ts.
    optional_permissions: ["privacy"],
    // Web pages only, as the content scripts: not file: or other schemes.
    host_permissions: WEB_PAGES,
    commands: {
      "fill-login": {
        suggested_key: { default: "Ctrl+Shift+L", mac: "Command+Shift+L" },
        description: "__MSG_browser_extension_command_fill_login__",
      },
    },
    // The inline menu and the save bar, which content scripts embed. Chromium
    // serves them under a per-session ID, so pages can't probe for them.
    web_accessible_resources: [
      {
        resources: ["inline-menu.html", "save-bar.html"],
        matches: WEB_PAGES,
        ...(browser === "firefox" ? {} : { use_dynamic_url: true }),
      },
    ],
    content_security_policy: {
      extension_pages: extensionPagesCsp(browser, command === "serve"),
    },
    action: {
      default_title: "__MSG_browser_extension_name__",
    },
    ...(browser === "firefox"
      ? {
          browser_specific_settings: {
            gecko: {
              id: GECKO_ID,
              strict_min_version: "140.0",
              // Mozilla counts data that leaves the browser, and the app is
              // outside it: pages' URLs go to the app to find their logins,
              // and submitted logins go to it to be saved.
              data_collection_permissions: { required: ["browsingActivity", "authenticationInfo"] },
            },
          },
        }
      : {}),
    ...(browser === "firefox"
      ? {}
      : {
          // `use_dynamic_url` (130; older versions ignore it, so pages could
          // probe for the inline menu and the save bar), `action.openPopup()`
          // outside of policy installs (127) for "Open Keyguard" and the fill
          // shortcut, and popovers (114), which put the inline UI in the top
          // layer over the page; 111 has the main-world content script,
          // color-mix() and the syntax of Vite's build target. The rest is
          // older or optional.
          minimum_chrome_version: "130",
        }),
    ...(browser !== "firefox" && mode === "development" ? { key: DEV_KEY } : {}),
  }),
  hooks: {
    "build:before": () => bundledPackages.clear(),
    "build:publicAssets": (wxt, files) => {
      files.push({ absoluteSrc: cryptoWasmPath(), relativeDest: CRYPTO_WASM_FILE });
      // WXT copies the public files after it builds every entrypoint, so the
      // notices list every bundled package.
      if (wxt.config.command === "build") {
        files.push({ relativeDest: NOTICES_FILE, contents: thirdPartyNotices(wxt.config.root, bundledPackages) });
      }
    },
    "build:manifestGenerated": (wxt, manifest) => {
      // Firefox runs the event page of `scripts` and ignores the service
      // worker; its manifest names both. Chromium manifests keep only the
      // worker: Edge Add-ons refuses `scripts` in Manifest V3.
      if (wxt.config.browser !== "firefox") return;
      const background = manifest.background as { service_worker?: string; scripts?: string[] } | undefined;
      const script = background?.service_worker ?? background?.scripts?.[0];
      if (background !== undefined && script !== undefined) {
        background.service_worker = script;
        background.scripts = [script];
      }
    },
  },
});
