// The popup's keyboard (src/lib/ui/popupKeys.ts): the Quick search keys of
// the app, their labels, and the selection's movement.
import { describe, expect, it } from "vitest";
import {
  ariaKeyShortcuts,
  isMacPlatform,
  moveSelection,
  popupActions,
  popupKey,
  SHORTCUTS,
  shortcutKeys,
  shortcutLabel,
  type KeyContext,
  type KeyInput,
} from "../src/lib/ui/popupKeys";

function key(init: Partial<KeyInput> & { key: string }): KeyInput {
  const code = init.code ?? (/^[a-z]$/i.test(init.key) ? `Key${init.key.toUpperCase()}` : init.key);
  return { ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...init, code };
}

const MAC: KeyContext = { mac: true, inField: true, hasSelection: false };
const OTHER: KeyContext = { mac: false, inField: true, hasSelection: false };
const action = (name: string) => ({ kind: "action", action: name });

describe("popup keys", () => {
  it("copies with ⌘ on macOS", () => {
    expect(popupKey(key({ key: "c", metaKey: true }), MAC)).toEqual(action("copy_username"));
    expect(popupKey(key({ key: "C", metaKey: true, shiftKey: true }), MAC)).toEqual(action("copy_password"));
    // ⌥ makes the key "ç": the physical key counts.
    expect(popupKey(key({ key: "ç", code: "KeyC", metaKey: true, altKey: true }), MAC)).toEqual(action("copy_totp"));
    // Ctrl is no shortcut modifier there, alone or with ⌘.
    expect(popupKey(key({ key: "c", ctrlKey: true }), MAC)).toBeNull();
    expect(popupKey(key({ key: "c", ctrlKey: true, metaKey: true }), MAC)).toBeNull();
  });

  it("copies with Ctrl elsewhere", () => {
    expect(popupKey(key({ key: "c", ctrlKey: true }), OTHER)).toEqual(action("copy_username"));
    expect(popupKey(key({ key: "C", ctrlKey: true, shiftKey: true }), OTHER)).toEqual(action("copy_password"));
    expect(popupKey(key({ key: "c", ctrlKey: true, altKey: true }), OTHER)).toEqual(action("copy_totp"));
    expect(popupKey(key({ key: "c", metaKey: true }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "C", ctrlKey: true, shiftKey: true, altKey: true }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "v", ctrlKey: true }), OTHER)).toBeNull();
  });

  it("goes by the layout's letter, or the physical key for a non-Latin one", () => {
    // Dvorak: the "c" is elsewhere.
    expect(popupKey(key({ key: "c", code: "KeyI", ctrlKey: true }), OTHER)).toEqual(action("copy_username"));
    expect(popupKey(key({ key: "j", code: "KeyC", ctrlKey: true }), OTHER)).toBeNull();
    // Cyrillic "с" on the C key.
    expect(popupKey(key({ key: "с", code: "KeyC", ctrlKey: true }), OTHER)).toEqual(action("copy_username"));
  });

  it("leaves AltGr, which is Ctrl+Alt on Windows, to typing", () => {
    // Polish: AltGr+C types "ć".
    expect(popupKey(key({ key: "ć", code: "KeyC", ctrlKey: true, altKey: true }), OTHER)).toBeNull();
    const altGraph = key({ key: "c", ctrlKey: true, altKey: true, getModifierState: (it) => it === "AltGraph" });
    expect(popupKey(altGraph, OTHER)).toBeNull();
  });

  it("leaves Mod+C to the native copy of selected text", () => {
    const selection = { ...MAC, hasSelection: true };
    expect(popupKey(key({ key: "c", metaKey: true }), selection)).toBeNull();
    expect(popupKey(key({ key: "C", metaKey: true, shiftKey: true }), selection)).toEqual(action("copy_password"));
    expect(popupKey(key({ key: "c", metaKey: true }), { ...selection, inField: false })).toBeNull();
  });

  it("copies outside the field too", () => {
    const outside = { ...OTHER, inField: false };
    expect(popupKey(key({ key: "c", ctrlKey: true }), outside)).toEqual(action("copy_username"));
  });

  it("moves and fills from the field only, without modifiers", () => {
    expect(popupKey(key({ key: "ArrowDown" }), OTHER)).toEqual({ kind: "move", step: 1 });
    expect(popupKey(key({ key: "ArrowUp" }), MAC)).toEqual({ kind: "move", step: -1 });
    expect(popupKey(key({ key: "Enter" }), OTHER)).toEqual(action("fill"));
    // A button's own Enter, and text selection with Shift.
    expect(popupKey(key({ key: "Enter" }), { ...OTHER, inField: false })).toBeNull();
    expect(popupKey(key({ key: "ArrowDown" }), { ...OTHER, inField: false })).toBeNull();
    expect(popupKey(key({ key: "ArrowDown", shiftKey: true }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "Enter", altKey: true }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "Escape" }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "a" }), OTHER)).toBeNull();
  });

  it("leaves a key of an IME composition to it", () => {
    expect(popupKey(key({ key: "Enter", isComposing: true }), OTHER)).toBeNull();
    expect(popupKey(key({ key: "ArrowDown", isComposing: true }), OTHER)).toBeNull();
  });
});

describe("popup shortcut labels", () => {
  it("labels as the app does on macOS", () => {
    expect(shortcutLabel(SHORTCUTS.fill, true)).toBe("⏎");
    expect(shortcutLabel(SHORTCUTS.copy_username, true)).toBe("⌘+C");
    expect(shortcutLabel(SHORTCUTS.copy_password, true)).toBe("⌘+⇧+C");
    expect(shortcutLabel(SHORTCUTS.copy_totp, true)).toBe("⌘+⌥+C");
  });

  it("labels as the app does elsewhere", () => {
    expect(shortcutLabel(SHORTCUTS.fill, false)).toBe("Enter");
    expect(shortcutLabel(SHORTCUTS.copy_username, false)).toBe("Ctrl+C");
    expect(shortcutLabel(SHORTCUTS.copy_password, false)).toBe("Ctrl+Shift+C");
    expect(shortcutKeys(SHORTCUTS.copy_totp, false)).toEqual(["Ctrl", "Alt", "C"]);
  });

  it("names the keys for aria-keyshortcuts", () => {
    expect(ariaKeyShortcuts(SHORTCUTS.copy_password, true)).toBe("Meta+Shift+C");
    expect(ariaKeyShortcuts(SHORTCUTS.copy_totp, false)).toBe("Control+Alt+C");
    expect(ariaKeyShortcuts(SHORTCUTS.fill, true)).toBe("Enter");
  });

  it("tells macOS by its platform names", () => {
    expect(isMacPlatform("macOS")).toBe(true);
    expect(isMacPlatform("MacIntel")).toBe(true);
    expect(isMacPlatform("Windows")).toBe(false);
    expect(isMacPlatform("Linux x86_64")).toBe(false);
    expect(isMacPlatform("")).toBe(false);
    expect(isMacPlatform(undefined)).toBe(false);
  });
});

describe("popup selection", () => {
  it("moves within the ends of the list", () => {
    expect(moveSelection(0, 3, 1)).toBe(1);
    expect(moveSelection(2, 3, 1)).toBe(2);
    expect(moveSelection(0, 3, -1)).toBe(0);
    expect(moveSelection(-1, 3, 1)).toBe(0);
    expect(moveSelection(-1, 3, -1)).toBe(2);
    // A selection past a list that shrank.
    expect(moveSelection(5, 3, 1)).toBe(0);
    expect(moveSelection(0, 0, 1)).toBeNull();
  });

  it("offers the actions an item has, in order", () => {
    expect(popupActions({ username: "ann", has_password: true, has_totp: true })).toEqual([
      "fill",
      "copy_username",
      "copy_password",
      "copy_totp",
    ]);
    expect(popupActions({ username: "", has_password: true, has_totp: false })).toEqual(["fill", "copy_password"]);
    expect(popupActions({ username: "ann", has_password: false, has_totp: false })).toEqual(["fill", "copy_username"]);
    expect(popupActions({ has_password: false, has_totp: true })).toEqual(["copy_totp"]);
    // Its collections hide the password: it still fills.
    expect(popupActions({ username: "ann", has_password: true, has_totp: false, password_hidden: true })).toEqual([
      "fill",
      "copy_username",
    ]);
  });
});
