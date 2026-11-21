# Building the Keyguard browser extension

This directory is part of the [Keyguard repository](https://github.com/AChep/keyguard-app).
The build also reads, from outside it:

- `../util/crypto`: the Rust crypto, compiled to WebAssembly with Emscripten.
  The extension uses its Noise-only build. The `keyguard-crypto-wasm`
  dependency links to its output, `../util/crypto/build/wasm/npm-noise`.
- `../gradle/libs.versions.toml`: the extension's version, `appVersionName`.
- The app's strings in `../common`, from which `pnpm generate` makes `_locales`.

So build from a full checkout or a tag's source archive.

## Prerequisites

- A JDK, 17 or newer, to start Gradle. Gradle downloads the JetBrains Runtime 21
  it runs on, see `gradle/gradle-daemon-jvm.properties`.
- Rust through rustup: `util/crypto/rust-toolchain.toml` selects the toolchain.
- libclang, for the bindings of AWS-LC: `libclang-dev` on Ubuntu.
- A C compiler for the Rust build scripts: `build-essential` on Ubuntu.
- Python 3.10 or newer, for Emscripten. Gradle installs Emscripten 4.0.23 into
  `~/.gradle/keyguard/emsdk/` on the first build.
- Node.js and pnpm: the versions in `engines` and `packageManager` of
  `package.json`. `.nvmrc` pins the Node.js version of CI.
- cargo-about 0.9.1, which lists the licenses of the Rust crates for
  `THIRD_PARTY_NOTICES.txt`:
  `cargo install --locked cargo-about --version 0.9.1 --features cli`.

No Android SDK, `local.properties` or `google-services.json`: Gradle configures
the Android modules without them, and the tasks below don't use them.

CI (`.github/workflows/check_browser_extension.yml`) runs the steps below on Ubuntu
24.04.

## Build

From the root of a clean checkout:

```sh
(cd util/crypto && rustup target add wasm32-unknown-emscripten)
./gradlew :util:crypto:buildNativeCryptoWasmNoise

cd browserExtension
pnpm install --frozen-lockfile
pnpm generate
pnpm zip          # .output/keyguard-<version>-chrome.zip
pnpm zip:firefox  # .output/keyguard-<version>-firefox.zip
```

`pnpm build` and `pnpm build:firefox` write the unpacked extension to
`.output/chrome-mv3/` and `.output/firefox-mv3/`.

## Release

The extension's version is the app's `appVersionName`, and the stores refuse a
version they already have. Several release tags can share one
`appVersionName`: bump it before you tag a release that changes the extension.

A release tag builds both zips in `.github/workflows/new_tag_release.yaml`. They
are artifacts of the workflow run, `app-browser-extension`, for 90 days, and not
files of the GitHub release. Upload the Chromium zip to the Chrome Web Store and
Edge Add-ons by hand.

The release's `deploy-firefox-add-ons` job submits the Firefox zip and the tag's
source archive to Firefox Add-ons (AMO) for review, with the reviewer notes in
`amo-metadata.json`. Like the other stores' jobs, it runs once `dist` has
published the GitHub release, when the `amo` environment has the API key. It
skips a version that AMO already has, listed or not. If it fails, re-run the
job. The job needs the add-on's listing on AMO: create it with the first
version by hand.

The job attaches the source archive after it creates the version. If that step
fails, a re-run skips the version, which AMO then has: attach the tag's source
archive to that version by hand, in the Developer Hub.

`version` in `amo-metadata.json` holds fields of the new version, see
[Version Create](https://mozilla.github.io/addons-server/topics/api/addons.html#version-create).
Other top-level fields edit the add-on's listing on every submission.

To set it up:

1. On AMO, open Developer Hub > Tools > Manage API Keys
   (<https://addons.mozilla.org/developers/addon/api/key/>) and generate
   credentials.
2. In the repository's settings, create the environment `amo` with the secrets
   `AMO_JWT_ISSUER` (the JWT issuer) and `AMO_JWT_SECRET` (the JWT secret).
   Optionally, add required reviewers to approve each submission. If the
   environment limits deployment branches and tags, allow the tags `r*`, and
   the branch you run the check from.
3. Create the add-on's listing on AMO and upload its first version by hand,
   with the source archive. The check below fails until the listing exists.
4. Run the "Check AMO credentials" workflow,
   `.github/workflows/check_amo_credentials.yml`, from the Actions tab. It
   looks up the next version with the key and dry-runs the submission. It
   uploads nothing.

### Source code for Firefox Add-ons

AMO asks for the source of the bundled code: the source archive of the
release's tag, the GitHub release's "Source code (zip)".
`deploy-firefox-add-ons` makes it with:

```sh
git archive --format=zip -o keyguard-<tag>-source.zip <tag>
```

AMO's reviewers build on Ubuntu 24.04 for ARM64, with Node.js 24.14 and npm.
The release zips are built on Ubuntu 24.04 for x86-64: build there. The build
also needs:

- Node.js 24.16 or newer, for example `nvm install 24.16.0`;
- pnpm 12.18: `corepack enable`, or `npm install -g pnpm@12.18.0`;
- `sudo apt-get install build-essential openjdk-21-jdk-headless python3 libclang-dev`;
- Rust through [rustup](https://rustup.rs);
- cargo-about: `cargo install --locked cargo-about --version 0.9.1 --features cli`.

From the root of the archive:

```sh
(cd util/crypto && rustup target add wasm32-unknown-emscripten)
./gradlew :util:crypto:buildNativeCryptoWasmNoise

cd browserExtension
pnpm install --frozen-lockfile
pnpm generate
pnpm zip:firefox  # .output/keyguard-<version>-firefox.zip
```

## Icons

The toolbar and store icons, `public/icon/<size>.png`, are checked in. They
and `../artwork/browser-extension-icon.svg` come from the logo in
`src/lib/ui/logo.ts`. After you change the logo, render them again:

```sh
pnpm exec playwright install --only-shell chromium
pnpm render:icons
```

To use another Chrome or Chromium, set `CHROME_PATH` to its executable.

## Checks

```sh
pnpm typecheck
pnpm lint
pnpm test
pnpm build:firefox && pnpm lint:firefox
```

The build fails when it bundles an npm package under a license outside the
allowlist in `wxt.config.ts`.

Both builds include `THIRD_PARTY_NOTICES.txt`, which the options page links: the
license texts of the bundled npm packages, the Material icons and the Rust
crates. A package that publishes no license file fails the build; add its
texts to `licenses/npm/<package>/`.

`pnpm lint:firefox` runs `web-ext lint`, which reports three expected warnings:

- `BACKGROUND_SERVICE_WORKER_IGNORED`: the Firefox manifest names the
  background script as both `service_worker` and `scripts`. Firefox runs
  `scripts`. Chromium manifests name only `service_worker`, as Edge Add-ons
  refuses `scripts` in Manifest V3.
- `KEY_FIREFOX_ANDROID_UNSUPPORTED_BY_MIN_VERSION`:
  `data_collection_permissions` needs Firefox for Android 142. The extension
  doesn't support Firefox for Android, which has no native messaging.
- `UNSAFE_VAR_ASSIGNMENT` in the chunk with the Svelte runtime, which Vite
  names after one of its modules (now `chunks/Logo-*.js`): the runtime sets
  `innerHTML` to templates compiled into the extension, never to page or user
  data.
