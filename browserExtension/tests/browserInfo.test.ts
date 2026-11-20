import { describe, expect, it } from "vitest";
import { browserName, extensionOrigin } from "../src/lib/util/browserInfo";

describe("extensionOrigin", () => {
  it("is chrome-extension://<id>/ on Chromium browsers", () => {
    const id = "ngjnjkolmoemeghbpilkeiimhacfmkdp";
    expect(extensionOrigin({ id, getURL: () => `chrome-extension://${id}/` })).toBe(`chrome-extension://${id}/`);
  });

  it("is the add-on ID on Firefox", () => {
    const runtime = { id: "browser@keyguard.dev", getURL: () => "moz-extension://0b5a3e3c-5d2e-4bfa-9c1e-1b2c3d4e5f60/" };
    expect(extensionOrigin(runtime)).toBe("browser@keyguard.dev");
  });
});

describe("browserName", () => {
  it.each([
    ["Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0", undefined, "Firefox"],
    ["Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0", undefined, "Microsoft Edge"],
    ["Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36", ["Brave", "Chromium"], "Brave"],
    ["Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36", ["Google Chrome", "Chromium"], "Google Chrome"],
    ["Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36", ["Chromium"], "Chromium"],
  ])("names %s", (userAgent, brands, name) => {
    const userAgentData = brands === undefined ? undefined : { brands: brands.map((brand) => ({ brand })) };
    expect(browserName({ userAgent, ...(userAgentData ? { userAgentData } : {}) })).toBe(name);
  });
});
