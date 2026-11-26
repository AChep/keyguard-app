// The texts of call errors (src/lib/ui/statusText.ts): each refusal of a
// page has its own.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import { FILL_REFUSALS, parseDocumentResult } from "../src/lib/page/messages";
import { errorText } from "../src/lib/ui/statusText";

beforeEach(() => {
  // The key as the message: the texts tell which key they are.
  vi.spyOn(fakeBrowser.i18n, "getMessage").mockImplementation(((name: string) => name) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("error texts", () => {
  it("has a text of its own for every page refusal, which a document's answer may carry", () => {
    for (const refusal of FILL_REFUSALS) {
      expect(errorText(refusal), refusal).not.toBe("error_failed_unknown");
      expect(parseDocumentResult({ ok: false, reason: refusal }, "no_form"), refusal).toEqual({
        ok: false,
        reason: refusal,
      });
    }
    // A parent to check that can't be read fails closed.
    expect(parseDocumentResult({ ok: true, parent: { frame: "top", x: 1, y: 1 } }, "origin")).toEqual({
      ok: false,
      reason: "frame_unchecked",
    });
    expect(errorText("something_else")).toBe("error_failed_unknown");
  });
});
