// The user's options, in `storage.local`. The content scripts read them and
// follow changes; only the background and the options page write. Nothing
// secret goes here: content scripts can read it, so the pairing lives
// elsewhere (../pairing/store.ts).
import { isObject } from "../util/json";
import { SerialQueue } from "../util/serial";

export const OPTIONS_KEY = "options";

export interface ExtensionOptions {
  /** The inline icon and menu on login fields. */
  inlineMenu: boolean;
  /** After a fill of only a username, fills the password on the sign-in's next page. */
  fillPasswordStep: boolean;
  /** Selects the form's sign-in or next button after a login fill. */
  submitAfterFill: boolean;
  /** The save bar after a sign-in or sign-up with a new password. */
  savePrompts: boolean;
  /** Keyguard answers passkey requests of pages (protocol `passkeys`). */
  passkeys: boolean;
}

export const DEFAULT_OPTIONS: Readonly<ExtensionOptions> = {
  inlineMenu: true,
  fillPasswordStep: true,
  submitAfterFill: false,
  savePrompts: true,
  passkeys: true,
};

/** The part of `storage.local` the store uses. */
export interface OptionsArea {
  get(keys: string | string[]): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
}

/** `storage.local` with change events. */
export interface ObservableStorageArea extends OptionsArea {
  onChanged: {
    addListener(callback: (changes: Record<string, { newValue?: unknown }>) => void): void;
    removeListener(callback: (changes: Record<string, { newValue?: unknown }>) => void): void;
  };
}

/** Options from a stored value, with defaults for what's missing or malformed. */
export function parseOptions(value: unknown): ExtensionOptions {
  const stored = isObject(value) ? value : {};
  const flag = (key: keyof ExtensionOptions) => (typeof stored[key] === "boolean" ? stored[key] : DEFAULT_OPTIONS[key]);
  return {
    inlineMenu: flag("inlineMenu"),
    fillPasswordStep: flag("fillPasswordStep"),
    submitAfterFill: flag("submitAfterFill"),
    savePrompts: flag("savePrompts"),
    passkeys: flag("passkeys"),
  };
}

export class OptionsStore {
  // Serializes read-modify-write of the options.
  private readonly queue = new SerialQueue();
  /** The options as last read, until [invalidate]. */
  private cached: Promise<ExtensionOptions> | null = null;

  constructor(private readonly area: OptionsArea) {}

  /** The options, read once until they change, see [invalidate]. */
  options(): Promise<ExtensionOptions> {
    if (this.cached === null) {
      const read = this.read();
      this.cached = read;
      read.catch(() => {
        if (this.cached === read) this.cached = null;
      });
    }
    return this.cached;
  }

  /** The stored options changed, as `storage.onChanged` tells: [options] reads them again. */
  invalidate(): void {
    this.cached = null;
  }

  setOptions(patch: Partial<ExtensionOptions>): Promise<ExtensionOptions> {
    return this.queue.run(async () => {
      const next = { ...(await this.read()), ...patch };
      await this.area.set({ [OPTIONS_KEY]: next });
      this.cached = Promise.resolve(next);
      return next;
    });
  }

  private async read(): Promise<ExtensionOptions> {
    return parseOptions((await this.area.get(OPTIONS_KEY))[OPTIONS_KEY]);
  }
}

/** Calls [listener] with the options now and on every change; returns a function that stops it. */
export function watchOptions(area: ObservableStorageArea, listener: (options: ExtensionOptions) => void): () => void {
  let stopped = false;
  // A change that comes first is newer than what the first read gets.
  let changed = false;
  const onChanged = (changes: Record<string, { newValue?: unknown }>) => {
    if (!(OPTIONS_KEY in changes)) return;
    changed = true;
    listener(parseOptions(changes[OPTIONS_KEY]!.newValue));
  };
  area.onChanged.addListener(onChanged);
  void area.get(OPTIONS_KEY).then((stored) => {
    if (!stopped && !changed) listener(parseOptions(stored[OPTIONS_KEY]));
  });
  return () => {
    stopped = true;
    area.onChanged.removeListener(onChanged);
  };
}
