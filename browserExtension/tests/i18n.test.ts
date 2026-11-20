// Plurals (src/lib/i18n.ts): the category follows the plural rules of the
// language the plural is in, which is English where it has no translation.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import { tp } from "../src/lib/i18n";

const KEY = "browser_extension_logins_count_plural";

/**
 * The messages of the logins plural, as the generator makes them: every
 * category, `other` where the language has none. [locale] is its language.
 */
function catalog(locale: string | null, forms: { other: string } & Record<string, string>): Record<string, string> {
  const messages: Record<string, string> = {};
  for (const category of ["zero", "one", "two", "few", "many", "other"]) {
    messages[`${KEY}__${category}`] = forms[category] ?? forms.other;
  }
  if (locale !== null) messages[`${KEY}__locale`] = locale;
  return messages;
}

function useCatalog(messages: Record<string, string>) {
  vi.spyOn(fakeBrowser.i18n, "getMessage").mockImplementation(((name: string, substitutions?: string | string[]) => {
    const args = typeof substitutions === "string" ? [substitutions] : (substitutions ?? []);
    return (messages[name] ?? "").replace(/\$(\d)/g, (_, index: string) => args[Number(index) - 1] ?? "");
  }) as typeof fakeBrowser.i18n.getMessage);
}

const ENGLISH = { one: "$1 login", other: "$1 logins" };
const RUSSIAN = { one: "$1 логин", few: "$1 логина", many: "$1 логинов", other: "$1 логина" };

beforeEach(() => {
  fakeBrowser.reset();
  vi.spyOn(fakeBrowser.i18n, "getUILanguage").mockReturnValue("ru");
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("tp", () => {
  it("picks an English fallback's category with English rules", () => {
    useCatalog(catalog("en", ENGLISH));
    expect(tp(KEY, 21, 21)).toBe("21 logins");
    expect(tp(KEY, 1, 1)).toBe("1 login");
  });

  it("picks a translation's category with its own rules", () => {
    useCatalog(catalog("ru", RUSSIAN));
    expect(tp(KEY, 21, 21)).toBe("21 логин");
    expect(tp(KEY, 3, 3)).toBe("3 логина");
    expect(tp(KEY, 5, 5)).toBe("5 логинов");
  });

  it("follows the UI language without a plural locale", () => {
    useCatalog(catalog(null, RUSSIAN));
    expect(tp(KEY, 21, 21)).toBe("21 логин");
  });
});
