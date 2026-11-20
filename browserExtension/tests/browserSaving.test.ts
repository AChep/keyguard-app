// The option that turns off the browser's own password saving, behind the
// optional `privacy` permission.
import { describe, expect, it } from "vitest";
import {
  browserSavingNote,
  browserSavingState,
  restoreBrowserSaving,
  turnOffBrowserSaving,
  type LevelOfControl,
  type PrivacyBrowser,
} from "../src/lib/options/browserSaving";

/**
 * `browser.permissions` and `browser.privacy`. As in browsers, `privacy`
 * exists only while the permission is granted.
 */
function fakePrivacy({
  grant = true,
  granted = false,
  level = "controllable_by_this_extension",
}: { grant?: boolean; granted?: boolean; level?: LevelOfControl } = {}) {
  const calls: string[] = [];
  const setting = {
    get: async () => ({ levelOfControl: level }),
    set: async ({ value }: { value: boolean }) => {
      calls.push(`set ${value}`);
      level = "controlled_by_this_extension";
    },
    clear: async () => {
      calls.push("clear");
      level = "controllable_by_this_extension";
    },
  };
  const api: PrivacyBrowser = {
    permissions: {
      request: async ({ permissions }) => {
        calls.push(`request ${permissions.join()}`);
        granted ||= grant;
        return granted;
      },
      remove: async ({ permissions }) => {
        calls.push(`remove ${permissions.join()}`);
        granted = false;
        return true;
      },
    },
    get privacy() {
      return granted ? { services: { passwordSavingEnabled: setting } } : undefined;
    },
  };
  return { api, calls };
}

describe("browser password saving", () => {
  it("turning on requests the permission, then sets the setting to false", async () => {
    const { api, calls } = fakePrivacy();
    const result = turnOffBrowserSaving(api);
    // Before anything is awaited, while the click's user gesture lasts.
    expect(calls).toEqual(["request privacy"]);
    expect(await result).toBe("on");
    expect(calls).toEqual(["request privacy", "set false"]);
    expect(await browserSavingState(api)).toBe("on");
  });

  it("stays off and sets nothing when the user denies the permission", async () => {
    const { api, calls } = fakePrivacy({ grant: false });
    expect(await turnOffBrowserSaving(api)).toBe("off");
    expect(calls).toEqual(["request privacy"]);
    expect(await browserSavingState(api)).toBe("off");
  });

  it("turning off clears the setting, then removes the permission", async () => {
    const { api, calls } = fakePrivacy({ granted: true, level: "controlled_by_this_extension" });
    expect(await restoreBrowserSaving(api)).toBe("off");
    expect(calls).toEqual(["clear", "remove privacy"]);
    expect(await browserSavingState(api)).toBe("off");
  });

  it.each([
    ["controlled_by_this_extension", "on", undefined],
    ["controllable_by_this_extension", "off", undefined],
    ["controlled_by_other_extensions", "extension", "browser_extension_options_browser_saving_other_extension"],
    ["not_controllable", "browser", "browser_extension_options_browser_saving_not_controllable"],
  ] as const)("shows %s as %s", async (level, state, note) => {
    const { api } = fakePrivacy({ granted: true, level });
    expect(await browserSavingState(api)).toBe(state);
    expect(browserSavingNote(state)).toBe(note);
  });

  it("shows off without the permission", async () => {
    const { api } = fakePrivacy({ level: "controlled_by_other_extensions" });
    expect(await browserSavingState(api)).toBe("off");
  });

  it.each([
    ["controlled_by_other_extensions", "extension"],
    ["not_controllable", "browser"],
  ] as const)("turning on sets nothing while %s, and gives the permission back", async (level, state) => {
    const { api, calls } = fakePrivacy({ level });
    expect(await turnOffBrowserSaving(api)).toBe(state);
    expect(calls).toEqual(["request privacy", "remove privacy"]);
  });
});
