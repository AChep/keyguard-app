// The monograms of avatars (src/lib/ui/avatar.ts), against the app's
// FNV.kt, AttachmentIcon.kt and VaultItemIcon.kt.
import { describe, expect, it } from "vitest";
import { fnv1, hsvToRgb, initials, monogramAccent, monogramHue } from "../src/lib/ui/avatar";

describe("fnv1", () => {
  it("is FNV-1 32", () => {
    expect(fnv1("")).toBe(0x811c9dc5);
    expect(fnv1("a")).toBe(0x050c5d7e);
    expect(fnv1("foobar")).toBe(0x31f0b262);
  });

  it("hashes UTF-16 code units, not UTF-8 bytes", () => {
    expect(fnv1("é")).toBe(0x050c5df6);
    expect(fnv1("é")).not.toBe(0xce77c1fd);
    // A surrogate pair is two units.
    expect(fnv1("😀")).toBe(0x14b54a86);
  });

  it("is unsigned", () => {
    expect(fnv1("Google")).toBe(0xef7f4f70);
    expect(fnv1("github.com")).toBe(0xc49c601d);
  });
});

describe("monogramHue", () => {
  it("is the hash modulo 60, times 6", () => {
    expect(monogramHue("")).toBe(6);
    expect(monogramHue("a")).toBe(156);
    expect(monogramHue("foobar")).toBe(60);
    expect(monogramHue("Google")).toBe(192);
    expect(monogramHue("github.com")).toBe(342);
  });

  it("is a step of 6 from 0 to 354", () => {
    for (let i = 0; i < 500; i++) {
      const hue = monogramHue(`item ${i}`);
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThanOrEqual(354);
      expect(hue % 6).toBe(0);
    }
  });
});

describe("hsvToRgb", () => {
  it("is Compose's Color.hsv", () => {
    expect(hsvToRgb(0, 0, 1)).toEqual([255, 255, 255]);
    expect(hsvToRgb(120, 1, 1)).toEqual([0, 255, 0]);
    expect(hsvToRgb(240, 1, 0.5)).toEqual([0, 0, 128]);
    // The app's light error color.
    expect(hsvToRgb(0, 0.96, 0.8)).toEqual([204, 8, 8]);
  });

  it("colors monograms at saturation and value 0.8", () => {
    expect(monogramAccent("a")).toBe(`rgb(${hsvToRgb(156, 0.8, 0.8).join(" ")})`);
    expect(monogramAccent("foobar")).toBe("rgb(204 204 41)");
  });
});

describe("initials", () => {
  it("takes the first symbols of the first two words", () => {
    expect(initials("github account")).toBe("ga");
    expect(initials("My Bank Account")).toBe("MB");
    expect(initials("😀 Smile")).toBe("😀S");
  });

  it("takes the first two symbols of a single word", () => {
    expect(initials("Google")).toBe("Go");
    expect(initials("a")).toBe("a");
    expect(initials("a.b")).toBe("a.");
    expect(initials("😀😃x")).toBe("😀😃");
  });

  it("splits at spaces only", () => {
    expect(initials("foo\tbar")).toBe("fo");
    expect(initials("foo-bar")).toBe("fo");
  });

  it("keeps empty words, as Kotlin's split", () => {
    expect(initials("")).toBe("");
    expect(initials(" leading")).toBe("l");
    expect(initials("a  b")).toBe("a");
    expect(initials(" ")).toBe("");
  });

  it("keeps joined emoji whole", () => {
    expect(initials("❤️ love")).toBe("❤️l");
    expect(initials("👨‍👩‍👧 family")).toBe("👨‍👩‍👧f");
    expect(initials("👨‍👩‍👧")).toBe("👨‍👩‍👧");
  });
});
