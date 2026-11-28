// Chromium extension versions (scripts/version.ts).
import { describe, expect, it } from "vitest";
import { compareVersions } from "../scripts/version";

describe("compareVersions", () => {
  it("compares dot-separated integers, with missing parts as 0", () => {
    expect(compareVersions("3.10.0", "3.9.0")).toBe(1);
    expect(compareVersions("3.9.0", "3.10.0")).toBe(-1);
    expect(compareVersions("3.10", "3.10.0")).toBe(0);
    expect(compareVersions("3.10.0.1", "3.10.0")).toBe(1);
    expect(compareVersions("4", "3.99.99.99")).toBe(1);
  });

  it("refuses what isn't a version", () => {
    expect(() => compareVersions("3.10.0-beta", "3.10.0")).toThrow("Not an extension version: 3.10.0-beta");
    expect(() => compareVersions("3.10.0", "1.2.3.4.5")).toThrow("Not an extension version: 1.2.3.4.5");
  });
});
