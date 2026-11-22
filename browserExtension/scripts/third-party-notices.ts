// The third-party notices of the extension, THIRD_PARTY_NOTICES.txt in both
// builds (wxt.config.ts): the license texts of the bundled npm packages, of the
// Material icons (src/lib/ui/icons.ts) and of the Rust crates in the Wasm
// crypto.
//
// `licenses/` holds the texts that don't come with the code: those of npm
// packages that publish none, at licenses/npm/<package>/, and of the icons.
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

export const NOTICES_FILE = "THIRD_PARTY_NOTICES.txt";

/** Written by `pnpm generate:licenses`, see scripts/rust-licenses.ts. */
export const RUST_LICENSES_FILE = "src/generated/rust-licenses.json";

/** One license text of the Rust crates in the Wasm crypto. */
export interface RustLicense {
  /** The SPDX ID. */
  id: string;
  name: string;
  text: string;
  /** `<name> <version>` of the crates under this text. */
  crates: string[];
}

const LICENSE_FILE = /^(licen[cs]e|copying|notice)/i;

const RULE = "=".repeat(80);

const compare = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** Line endings of a Windows checkout, and blank lines around the text, don't change the file. */
function normalize(text: string): string {
  return text
    .replace(/\r\n?/g, "\n")
    .replace(/^\s*\n/, "")
    .trimEnd();
}

/** The license files of a directory, by name, or `undefined` if it has none. */
function licenseTexts(directory: string): string[] | undefined {
  let names: string[];
  try {
    names = readdirSync(directory, { withFileTypes: true })
      .filter((it) => it.isFile() && LICENSE_FILE.test(it.name))
      .map((it) => it.name)
      .sort(compare);
  } catch {
    return undefined;
  }
  if (names.length === 0) return undefined;
  return names.map((name) => normalize(readFileSync(path.join(directory, name), "utf8")));
}

function section(heading: string[], texts: string[]): string {
  return [RULE, ...heading, RULE, "", texts.join("\n\n")].join("\n");
}

function rustLicenses(root: string): RustLicense[] {
  try {
    return JSON.parse(readFileSync(path.join(root, RUST_LICENSES_FILE), "utf8")) as RustLicense[];
  } catch (error) {
    throw new Error(`Can't read ${RUST_LICENSES_FILE}, run \`pnpm generate\``, { cause: error });
  }
}

/**
 * The notices of the npm packages in [packageDirectories], the icons and the
 * Rust crates, for the extension at [root]. Fails when a package has no
 * license text.
 */
export function thirdPartyNotices(root: string, packageDirectories: Iterable<string>): string {
  const packages = [...new Set(packageDirectories)]
    .map((directory) => {
      const { name, version, license } = JSON.parse(readFileSync(path.join(directory, "package.json"), "utf8")) as {
        name: string;
        version: string;
        license?: unknown;
      };
      return { directory, name, version, license: String(license) };
    })
    .sort((a, b) => compare(a.name, b.name) || compare(a.version, b.version));
  const sections = packages.map(({ directory, name, version, license }) => {
    const texts = licenseTexts(directory) ?? licenseTexts(path.join(root, "licenses/npm", name));
    if (texts === undefined) {
      throw new Error(`${name} publishes no license file: add its license texts to licenses/npm/${name}/`);
    }
    return section([`${name} ${version}`, `License: ${license}`], texts);
  });
  sections.push(
    section(
      ["Material Icons, Copyright Google LLC", "License: Apache-2.0"],
      [normalize(readFileSync(path.join(root, "licenses/material-icons/LICENSE"), "utf8"))],
    ),
  );
  for (const license of rustLicenses(root)) {
    sections.push(
      section(
        [`Rust crates: ${license.crates.join(", ")}`, `License: ${license.name} (${license.id})`],
        [normalize(license.text)],
      ),
    );
  }
  const header = "The Keyguard browser extension includes the third-party software below, under these licenses.";
  return [header, ...sections].join("\n\n") + "\n";
}
