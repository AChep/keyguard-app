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
files of the GitHub release. The release's jobs submit the Firefox zip to
Firefox Add-ons, and the Chromium zip to the Chrome Web Store and Edge Add-ons.

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

### Chrome Web Store

The release's `deploy-chrome-web-store` job uploads the Chromium zip to the
Chrome Web Store and submits it for review. The store publishes the version
once it's approved. Like the AMO job, it runs once `dist` has published the
GitHub release, when the `chrome-web-store` environment has the service account
and the publisher ID. It skips a version that the store has in review, staged
or published. If it fails, re-run the job.

While an older version is in review or staged, the job skips the version with a
warning. Once the review ends, or after you publish or cancel the staged version
in the Developer Dashboard, re-run the job.

With verified CRX uploads on, the store takes only a CRX signed with your key.
If `CWS_CRX_PRIVATE_KEY` is set, the job signs the zip into a CRX with
`scripts/crx.ts` and uploads that. If not, it uploads the zip. Keep a backup of
the key: without it, you can't upload until Chrome Web Store support replaces
it, which can take a week.

To set it up:

1. Upload the extension's first version in the Developer Dashboard by hand: the
   API can't create an item. The check below fails until the item exists.
2. In a Google Cloud project, enable the Chrome Web Store API, create a service
   account, and create a JSON key for it.
3. In the Developer Dashboard, add the service account's email under Account. A
   publisher can have one service account. Copy the publisher ID from
   Publisher > Settings.
4. For verified CRX uploads, generate an RSA key and print its public half:

   ```sh
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out cws-crx-key.pem
   openssl rsa -in cws-crx-key.pem -pubout
   ```

   Paste the public key in the item's Package > Verified CRX Uploads.

5. In the repository's settings, create the environment `chrome-web-store` with
   the variable `CWS_PUBLISHER_ID` and the secrets:
   - `CWS_SERVICE_ACCOUNT_EMAIL`: `client_email` of the JSON key;
   - `CWS_SERVICE_ACCOUNT_PRIVATE_KEY`: `private_key` of the JSON key. Its `\n`
     escapes can stay;
   - `CWS_CRX_PRIVATE_KEY`, for verified CRX uploads: the content of
     `cws-crx-key.pem`.

   Any job in the environment can sign with the CRX key: limit its deployment
   branches and tags to the tags `r*` and the branch `master`, and run the
   check from `master`. Required reviewers are optional.

6. Run the "Check Chrome Web Store credentials" workflow,
   `.github/workflows/check_chrome_web_store_credentials.yml`. It looks up the
   next version with the service account and signs an empty zip with the CRX
   key. It uploads nothing. Compare the public key it prints with the one in
   Package > Verified CRX Uploads.

### Edge Add-ons

The release's `deploy-edge-add-ons` job uploads the Chromium zip to Microsoft
Edge Add-ons and submits it for certification, with the testers' notes in
`edge-certification-notes.txt`. It runs once `dist` has published the GitHub
release, when the `edge-web-store` environment has the API key and the product
ID. The API can't read versions, so the job asks the Edge Add-ons website, which
shows the published version. It skips a version that the website shows. If the
website doesn't answer, the job warns and submits anyway. If it fails, re-run
the job.

While a submission is in review, Edge Add-ons takes no new one. The job then
leaves the zip as the draft in Partner Center and ends with a warning. The API
doesn't say which version is in review. If it's the same version, there's
nothing to do. If not, re-run the job once that review ends.

The API key expires after 72 days. Submissions then fail with HTTP 401, in this
job only. To rotate the key, create a new one in Partner Center > Microsoft
Edge > Publish API, replace `EDGE_API_KEY`, and run the check below.

To set it up:

1. Submit the extension's first version in Partner Center by hand: the API
   can't create a product.
2. In Partner Center, open Microsoft Edge > Publish API and create API
   credentials. Copy the Client ID and the API key.
3. Copy the product ID from the extension's Extension overview page.
4. In the repository's settings, create the environment `edge-web-store` with
   the secrets `EDGE_CLIENT_ID` (the Client ID) and `EDGE_API_KEY` (the API
   key), and the variable `EDGE_PRODUCT_ID` (the product ID). As for `amo`,
   you can add required reviewers. If the environment limits branches and
   tags, allow the tags `r*` and the branch you run the check from.
5. Run the "Check Edge Add-ons credentials" workflow,
   `.github/workflows/check_edge_add_ons_credentials.yml`. It checks the key
   without uploading anything, and looks up the next version on the website.
   It doesn't check the product ID: the first submission does. Run it again
   after each new key.

## Icons

The toolbar and store icons, `public/icon/<size>.png`, are checked in. They
and `../artwork/browser-extension-icon.svg` come from the logo in
`src/lib/ui/logo.ts`. After you change the logo, render them again:

```sh
pnpm exec playwright install --only-shell chromium
pnpm render:icons
```

To use another Chrome or Chromium, set `CHROME_PATH` to its executable.

## Screenshots

The screenshots of the extension are checked in: the Chrome Web Store's, with
a headline, in `../screenshots/chrome-web-store/`, and the website's, the
browser window alone, as `browser-extension-*.png` and, for the home page's
device carousel, `browser.png` in `../server/web/src/assets/screenshots/`.
They show the built pages with sample
data, in the scenes of `scripts/screenshots/`. After you change those pages,
build and render them again on macOS, whose system font and shortcut keys they
show:

```sh
pnpm render:screenshots
```

`CHROME_PATH` works as for the icons.

## Checks

```sh
pnpm typecheck
pnpm lint
pnpm format:check
pnpm test
pnpm build:firefox && pnpm lint:firefox
```

Run `pnpm format` to apply Prettier formatting to the extension source.

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
