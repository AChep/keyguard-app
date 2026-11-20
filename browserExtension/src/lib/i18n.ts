// Localized strings from `_locales`, which `pnpm generate:strings` exports from
// the app's string resources. Unknown keys fail the typecheck.
import { browser } from "wxt/browser";
import type { I18nKey, I18nPluralKey } from "../generated/i18n-keys";

export type { I18nKey, I18nPluralKey };

type Arg = string | number;

function format(arg: Arg): string {
  return typeof arg === "number" ? arg.toLocaleString(browser.i18n.getUILanguage()) : arg;
}

// WXT types getMessage() per key; plural keys are built at runtime.
interface UntypedI18n {
  getMessage(name: string, substitutions?: string[]): string;
}

function message(key: string, args: readonly Arg[]): string {
  return (browser.i18n as unknown as UntypedI18n).getMessage(key, args.map(format));
}

/** The message `key`, with `args` for its `%1$s`-style placeholders. */
export function t(key: I18nKey, ...args: Arg[]): string {
  return message(key, args) || key;
}

const pluralRules = new Map<string, Intl.PluralRules>();

/**
 * The plural `key` for `count`, with `args` for its placeholders, like
 * `pluralStringResource(key, count, *args)`: pass the count again if the
 * message shows it.
 *
 * The category follows the rules of the plural's language, `<key>__locale`:
 * a locale without a translation of the plural has the English one.
 */
export function tp(key: I18nPluralKey, count: number, ...args: Arg[]): string {
  const locale = message(`${key}__locale`, []) || browser.i18n.getUILanguage();
  let rules = pluralRules.get(locale);
  if (rules === undefined) {
    rules = new Intl.PluralRules(locale);
    pluralRules.set(locale, rules);
  }
  const category = rules.select(count);
  return message(`${key}__${category}`, args) || message(`${key}__other`, args) || key;
}
