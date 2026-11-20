// The browser's own password saving, whose "Save password?" bubble would show
// next to the save bar. Only the options page turns it off, behind the
// optional `privacy` permission, which the extension holds only while it does.
import type { I18nKey } from "../i18n";

const PRIVACY = { permissions: ["privacy"] };

/** Who controls `privacy.services.passwordSavingEnabled`, as the browser reports it. */
export type LevelOfControl =
  | "not_controllable"
  | "controlled_by_other_extensions"
  | "controllable_by_this_extension"
  | "controlled_by_this_extension";

/** `privacy.services.passwordSavingEnabled`, a `types.BrowserSetting<boolean>`. */
export interface PasswordSavingSetting {
  get(details: object): Promise<{ levelOfControl: LevelOfControl }>;
  set(details: { value: boolean }): Promise<unknown>;
  clear(details: object): Promise<unknown>;
}

/** The part of `browser` this needs. `privacy` exists only with the permission. */
export interface PrivacyBrowser {
  permissions: {
    request(permissions: { permissions: string[] }): Promise<boolean>;
    remove(permissions: { permissions: string[] }): Promise<boolean>;
  };
  privacy?: { services?: { passwordSavingEnabled?: PasswordSavingSetting } };
}

/**
 * The switch: `on` while the extension keeps the browser's saving off,
 * `extension` and `browser` while another extension or the browser (a policy)
 * controls it, and `off` otherwise.
 */
export type BrowserSavingState = "off" | "on" | "extension" | "browser";

const NOTES: Partial<Record<BrowserSavingState, I18nKey>> = {
  extension: "browser_extension_options_browser_saving_other_extension",
  browser: "browser_extension_options_browser_saving_not_controllable",
};

/** Why the user can't change the setting here; the switch is disabled while there's one. */
export function browserSavingNote(state: BrowserSavingState): I18nKey | undefined {
  return NOTES[state];
}

function setting(api: PrivacyBrowser): PasswordSavingSetting | undefined {
  // Read on each call: the browser adds `privacy` once the user grants it.
  return api.privacy?.services?.passwordSavingEnabled;
}

export async function browserSavingState(api: PrivacyBrowser): Promise<BrowserSavingState> {
  const target = setting(api);
  if (target === undefined) return "off";
  switch ((await target.get({})).levelOfControl) {
    case "controlled_by_this_extension":
      return "on";
    case "controlled_by_other_extensions":
      return "extension";
    case "not_controllable":
      return "browser";
    default:
      return "off";
  }
}

/**
 * Turns the browser's saving off. Call it from the click handler itself, with
 * nothing awaited first: the permission request needs the user's gesture.
 */
export async function turnOffBrowserSaving(api: PrivacyBrowser): Promise<BrowserSavingState> {
  if (!(await api.permissions.request(PRIVACY))) return "off";
  const state = await browserSavingState(api);
  if (state === "off") {
    await setting(api)?.set({ value: false });
    return browserSavingState(api);
  }
  // Someone else controls it: nothing to set, so give the permission back.
  if (state !== "on") await api.permissions.remove(PRIVACY);
  return state;
}

/** Gives the setting back to the browser, then the permission. */
export async function restoreBrowserSaving(api: PrivacyBrowser): Promise<BrowserSavingState> {
  await setting(api)?.clear({});
  await api.permissions.remove(PRIVACY);
  return "off";
}
