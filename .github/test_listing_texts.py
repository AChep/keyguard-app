import tempfile
import unittest
from pathlib import Path

from listing_texts import (
    LANGUAGE_BASE,
    LISTING_COMMON_DIR,
    LISTING_COMMON_FILE,
    REPO_DIR,
    list_locales,
    load_locale_strings,
    load_strings,
    merge,
    render_text_template,
    template_tokens,
    translated_locales,
    unescape_android,
)

PLAY_TEMPLATE_FILE = REPO_DIR / "listing" / "google" / "store_description.html"
FLATHUB_TEMPLATE_FILE = REPO_DIR / "desktopApp" / "flatpak" / "com.artemchep.keyguard.metainfo.xml"

# The strings that no template uses, but that
# are kept for their translations.
UNUSED_STRINGS = {
    "listing_summary_long",
}


def _write_strings(directory, strings):
    path = Path(directory) / "strings.xml"
    body = "".join(strings)
    path.write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>{body}</resources>\n',
        encoding="utf-8",
    )
    return path


class UnescapeAndroidTest(unittest.TestCase):
    def test_resolves_escaped_apostrophe(self):
        self.assertEqual(unescape_android(r"n\'importe"), "n'importe")

    def test_resolves_escaped_backslash_before_apostrophe(self):
        self.assertEqual(unescape_android(r"a\\'b"), "a\\'b")

    def test_resolves_newline_and_unicode(self):
        self.assertEqual(unescape_android(r"a\nbé"), "a\nbé")

    def test_collapses_whitespace(self):
        self.assertEqual(unescape_android("  a \n   b\t"), "a b")

    def test_keeps_plain_text(self):
        self.assertEqual(unescape_android("Bitwarden® is a trademark"), "Bitwarden® is a trademark")


class LoadStringsTest(unittest.TestCase):
    def test_loads_and_unescapes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = _write_strings(directory, [
                "<!-- A comment -->",
                r'<string name="a">l\'app</string>',
                '<string name="b">Fill &amp; save</string>',
            ])
            self.assertEqual(load_strings(path), {"a": "l'app", "b": "Fill & save"})

    def test_joins_nested_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = _write_strings(directory, [
                '<string name="a">Open <b>Keyguard</b> now</string>',
            ])
            self.assertEqual(load_strings(path), {"a": "Open Keyguard now"})

    def test_missing_locale_file_has_no_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(load_locale_strings(directory, "de-rDE", "strings.xml"), {})

    def test_lists_locale_folders_without_base_and_its_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("uk-rUA", LANGUAGE_BASE, "en-rUS", "en-rGB", "de-rDE"):
                (Path(directory) / name).mkdir()
            (Path(directory) / "template.html").write_text("", encoding="utf-8")
            self.assertEqual(list_locales(directory), ["de-rDE", "en-rGB", "uk-rUA"])


class MergeTest(unittest.TestCase):
    def test_takes_translation(self):
        self.assertEqual(merge({"a": "Search"}, {"a": "Suche"}), {"a": "Suche"})

    def test_falls_back_on_missing_string(self):
        self.assertEqual(merge({"a": "Search", "b": "Import"}, {"a": "Suche"}), {"a": "Suche", "b": "Import"})

    def test_falls_back_on_empty_string(self):
        self.assertEqual(merge({"a": "Search"}, {"a": ""}), {"a": "Search"})

    def test_drops_removed_string(self):
        self.assertEqual(merge({"a": "Search"}, {"a": "Suche", "old": "Alt"}), {"a": "Suche"})

    def test_lists_locales_that_translate_a_string(self):
        base = {"a": "Search", "b": "Import"}
        libraries = {
            "de": {"a": "Suche", "b": "Import"},
            "fr": {"a": "Search", "b": "Importer"},
        }
        self.assertEqual(translated_locales(["a"], base, libraries), ["de"])
        self.assertEqual(translated_locales(["a", "b"], base, libraries), ["de", "fr"])


class RenderTextTemplateTest(unittest.TestCase):
    def test_replaces_placeholders(self):
        template = (
            '<b><keyguard_text_res token="title" /></b>\n'
            '• <keyguard_text_res token="item"/>\n'
        )
        strings = {"title": "Essentials", "item": "Search: Find items."}
        self.assertEqual(
            render_text_template(template, strings),
            "<b>Essentials</b>\n• Search: Find items.",
        )

    def test_lists_tokens_in_order(self):
        template = '<keyguard_text_res token="b" />\n<keyguard_text_res token="a" />'
        self.assertEqual(template_tokens(template), ["b", "a"])

    def test_rejects_unknown_token(self):
        with self.assertRaises(KeyError):
            render_text_template('<keyguard_text_res token="missing" />', {})

    def test_rejects_malformed_placeholder(self):
        with self.assertRaises(ValueError):
            render_text_template('<keyguard_text_res name="title" />', {"title": "Essentials"})


class ListingTextsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strings = load_strings(LISTING_COMMON_DIR / LANGUAGE_BASE / LISTING_COMMON_FILE)
        cls.play_tokens = template_tokens(PLAY_TEMPLATE_FILE.read_text(encoding="utf-8"))
        cls.flathub_tokens = template_tokens(FLATHUB_TEMPLATE_FILE.read_text(encoding="utf-8"))

    def test_templates_use_existing_strings(self):
        self.assertTrue(self.play_tokens)
        self.assertTrue(self.flathub_tokens)
        for token in self.play_tokens + self.flathub_tokens:
            self.assertIn(token, self.strings)

    def test_every_string_is_used(self):
        used = set(self.play_tokens) | set(self.flathub_tokens) | UNUSED_STRINGS
        self.assertEqual(set(self.strings) - used, set())

    def test_platform_strings_stay_on_their_platform(self):
        for token in self.play_tokens:
            self.assertFalse(token.endswith("_desktop"), token)
        for token in self.flathub_tokens:
            self.assertFalse(token.endswith("_android"), token)

    def test_strings_are_single_line_plain_text(self):
        for name, text in self.strings.items():
            self.assertTrue(text, name)
            for char in "<>\n\\":
                self.assertNotIn(char, text, name)


if __name__ == "__main__":
    unittest.main()
