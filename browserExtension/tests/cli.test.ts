// The error text of the release scripts' CLIs (scripts/cli.ts).
import { describe, expect, it } from "vitest";
import { errorText } from "../scripts/cli";

describe("errorText", () => {
  it("is the error's message", () => {
    expect(errorText(new Error("The upload failed"))).toBe("The upload failed");
    // A cause that isn't an Error has no message to add.
    expect(errorText(new Error("The upload failed", { cause: 404 }))).toBe("The upload failed");
  });

  it("adds the message of the error's cause", () => {
    const cause = new Error("getaddrinfo ENOTFOUND api.addons.microsoftedge.microsoft.com");
    expect(errorText(new TypeError("fetch failed", { cause }))).toBe(
      "fetch failed: getaddrinfo ENOTFOUND api.addons.microsoftedge.microsoft.com",
    );
  });

  it("is the text of a thrown value that isn't an Error", () => {
    expect(errorText("The upload failed")).toBe("The upload failed");
    expect(errorText(undefined)).toBe("undefined");
  });
});
