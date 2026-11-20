// The third-party notices of the builds (scripts/third-party-notices.ts).
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { RUST_LICENSES_FILE, thirdPartyNotices, type RustLicense } from "../scripts/third-party-notices";

let root: string;

function write(file: string, text: string) {
  mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  writeFileSync(path.join(root, file), text);
}

/** An npm package at node_modules/<name> with [files], and its directory. */
function npmPackage(name: string, version: string, files: Record<string, string>): string {
  write(`node_modules/${name}/package.json`, JSON.stringify({ name, version, license: "MIT" }));
  for (const [file, text] of Object.entries(files)) write(`node_modules/${name}/${file}`, text);
  return path.join(root, "node_modules", name);
}

const RUST: RustLicense[] = [
  { id: "MIT", name: "MIT License", text: "\nMIT text\n", crates: ["bytes 1.0.0", "spin 0.9.0"] },
];

beforeEach(() => {
  root = mkdtempSync(path.join(tmpdir(), "notices-"));
  write("licenses/material-icons/LICENSE", "Apache text\n");
  write(RUST_LICENSES_FILE, JSON.stringify(RUST));
});

afterEach(() => rmSync(root, { recursive: true, force: true }));

describe("thirdPartyNotices", () => {
  it("has every license file of the packages, then the icons and the Rust crates", () => {
    const zeta = npmPackage("zeta", "2.0.0", {
      "LICENSE.md": "Zeta license\r\n",
      NOTICE: "Zeta notice",
      "README.md": "Not a license",
    });
    const alpha = npmPackage("@scope/alpha", "1.0.0", { license: "Alpha license" });
    const notices = thirdPartyNotices(root, [zeta, alpha, zeta]);

    const sections = notices.split(/^={80}$/m).map((it) => it.trim());
    expect(sections.slice(1)).toEqual([
      "@scope/alpha 1.0.0\nLicense: MIT",
      "Alpha license",
      "zeta 2.0.0\nLicense: MIT",
      "Zeta license\n\nZeta notice",
      "Material Icons, Copyright Google LLC\nLicense: Apache-2.0",
      "Apache text",
      "Rust crates: bytes 1.0.0, spin 0.9.0\nLicense: MIT License (MIT)",
      "MIT text",
    ]);
    expect(notices).not.toContain("\r");
    expect(notices.endsWith("MIT text\n")).toBe(true);
  });

  it("takes the texts of a package that publishes none from licenses/npm", () => {
    const protobuf = npmPackage("@bufbuild/protobuf", "2.0.0", {});
    write("licenses/npm/@bufbuild/protobuf/LICENSE", "Buf license");
    const notices = thirdPartyNotices(root, [protobuf]);
    expect(notices).toContain(`@bufbuild/protobuf 2.0.0\nLicense: MIT\n${"=".repeat(80)}\n\nBuf license`);
  });

  it("fails on a package without license texts", () => {
    const bare = npmPackage("bare", "1.0.0", {});
    expect(() => thirdPartyNotices(root, [bare])).toThrow("bare publishes no license file");
  });

  it("fails without the licenses of the Rust crates", () => {
    rmSync(path.join(root, RUST_LICENSES_FILE));
    expect(() => thirdPartyNotices(root, [])).toThrow("run `pnpm generate`");
  });
});
