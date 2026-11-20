// Writes src/generated/rust-licenses.json, the licenses of the Rust crates in
// the Wasm crypto, for the third-party notices (scripts/third-party-notices.ts).
// `pnpm generate:licenses`; needs cargo-about, see BUILDING.md.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { RUST_LICENSES_FILE, type RustLicense } from "./third-party-notices.ts";

const root = path.join(import.meta.dirname, "..");
const workspace = path.join(root, "../util/crypto/rust");

interface AboutLicense {
  id: string;
  name: string;
  text: string;
  used_by: { crate: { name: string; version: string } }[];
}

// Offline, cargo-about reads the licenses from the crates alone, so the output
// depends on Cargo.lock only. Its `cargo metadata` needs every crate of the
// workspace downloaded.
execFileSync("cargo", ["fetch", "--locked"], { cwd: workspace, stdio: "inherit" });
// The crates of `buildNativeCryptoWasmNoise`: keyguard-crypto-c without default
// features (util/crypto/build.gradle.kts). The config is that of the app's
// notices, .github/workflows/check_native.yml.
const output = execFileSync(
  "cargo",
  [
    "about",
    "generate",
    "--format=json",
    "--manifest-path=crates/keyguard-crypto-c/Cargo.toml",
    "--config=about.toml",
    "--no-default-features",
    "--target=wasm32-unknown-emscripten",
    "--locked",
    "--offline",
    "--fail",
  ],
  { cwd: workspace, encoding: "utf8", maxBuffer: 256 * 1024 * 1024, stdio: ["ignore", "pipe", "inherit"] },
);
const licenses: RustLicense[] = (JSON.parse(output) as { licenses: AboutLicense[] }).licenses.map((license) => ({
  id: license.id,
  name: license.name,
  text: license.text,
  crates: license.used_by.map(({ crate }) => `${crate.name} ${crate.version}`),
}));
const file = path.join(root, RUST_LICENSES_FILE);
mkdirSync(path.dirname(file), { recursive: true });
writeFileSync(file, JSON.stringify(licenses, null, 2) + "\n");
console.log(`Wrote the licenses of the Rust crates to ${RUST_LICENSES_FILE}.`);
