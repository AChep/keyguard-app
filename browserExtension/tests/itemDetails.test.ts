// The view of a login that the popup's detail pane and the card beside the
// inline menu share (itemViewOf of src/lib/page/messages.ts): the popup maps
// the items it lists, the background those it sends the card.
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import { itemDetailsOf, itemIcon, itemViewOf, type ItemView } from "../src/lib/page/messages";
import { detailLines } from "../src/lib/ui/itemDetails";
import type { SearchItem, SuggestItem } from "../src/lib/protocol/messages";

const SUGGESTED: SuggestItem = {
  handle: "h1",
  name: "Example",
  username: "ann",
  account: "Personal",
  has_password: true,
  has_totp: true,
  reprompt: true,
  matched: false,
  passkeys: [{ handle: "p1", user_name: "ann", user_display_name: "Ann" }],
  uris: Array.from({ length: 7 }, (_, index) => `https://example.com/${index}`),
  uri_count: 9,
  folder: "Work",
  revision_date: "2026-11-20T10:00:00Z",
};

const VIEW: ItemView = {
  name: "Example",
  username: "ann",
  account: "Personal",
  copies: ["copy_username", "copy_password", "copy_totp"],
  reprompt: true,
  matched: false,
  last: true,
  icon: null,
  passkeys: [{ user_name: "ann", user_display_name: "Ann" }],
  details: {
    uris: SUGGESTED.uris!.slice(0, 5),
    more: 4,
    folder: "Work",
    revision_date: "2026-11-20T10:00:00Z",
  },
};

describe("item view", () => {
  it("shows a page's suggestion with its URIs, folder and date, and its passkeys by their names", () => {
    // A suggested login may be for another site: not the page's icon.
    expect(itemViewOf(SUGGESTED, "https://icons.example/1", true)).toEqual(VIEW);
    // A password its collections hide: no copy.
    expect(itemViewOf({ ...SUGGESTED, password_hidden: true }, null, true).copies).toEqual([
      "copy_username",
      "copy_totp",
    ]);
    // An older app's item, matched as parsed, with none of the sections.
    const older: SuggestItem = { ...SUGGESTED, matched: true, uris: undefined, uri_count: undefined };
    delete older.folder;
    delete older.revision_date;
    expect(itemViewOf(older, "https://icons.example/1", false)).toMatchObject({
      matched: true,
      icon: "https://icons.example/1",
      last: false,
      details: { uris: [], more: 0 },
    });
  });

  it("shows a search result without the sections, which `search` doesn't carry", () => {
    const result: SearchItem = {
      handle: "h2",
      name: "Other",
      account: "Work",
      has_password: false,
      has_totp: false,
      reprompt: false,
      matched: true,
    };
    expect(itemViewOf(result, null, false)).toEqual({
      name: "Other",
      account: "Work",
      copies: [],
      reprompt: false,
      matched: true,
      last: false,
      icon: null,
      passkeys: [],
    });
  });

  it("gives the page's icon to matched logins only", () => {
    expect(itemIcon({ matched: true }, "https://icons.example/1")).toBe("https://icons.example/1");
    expect(itemIcon({ matched: false }, "https://icons.example/1")).toBeNull();
    expect(itemIcon({ matched: true }, null)).toBeNull();
  });
});

describe("linked URIs", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** Each message is its key, but the plural's, with its count, in English. */
  function useMessages() {
    vi.spyOn(fakeBrowser.i18n, "getUILanguage").mockReturnValue("en");
    vi.spyOn(fakeBrowser.i18n, "getMessage").mockImplementation(((name: string, substitutions?: string | string[]) => {
      const args = typeof substitutions === "string" ? [substitutions] : (substitutions ?? []);
      if (name.endsWith("__locale")) return "";
      return name.startsWith("skipped_and_more_note__") ? `and ${args[0]} more` : name;
    }) as typeof fakeBrowser.i18n.getMessage);
  }

  it("shows the section of a login whose every URI is past the app's length cap: the app sends none, and counts them", () => {
    useMessages();
    const lines = (uris: string[] | undefined, count: number) =>
      detailLines(itemDetailsOf({ uris, uri_count: count, folder: undefined, revision_date: undefined }));
    expect(lines(undefined, 3)).toMatchObject({ urisHeader: "linked_uris", uris: [], more: "and 3 more" });
    expect(lines([], 1)).toMatchObject({ urisHeader: "linked_uri", uris: [], more: "and 1 more" });
    // One URI it lists, one past the cap: both count.
    expect(lines(["https://example.com"], 2)).toMatchObject({
      urisHeader: "linked_uris",
      uris: ["https://example.com"],
      more: "and 1 more",
    });
    expect(lines(["https://example.com"], 1)).toMatchObject({ urisHeader: "linked_uri", more: null });
    // None at all: no section.
    expect(lines(undefined, 0)).toMatchObject({ urisHeader: null, uris: [], more: null });
  });
});
