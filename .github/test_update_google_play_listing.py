import unittest

from update_google_play_listing import (
    LANGUAGE_DEFAULT,
    LISTING_LIMITS,
    compose_listing,
    get_language_tag,
    get_listing_violations,
    load_listings,
)

# Translations run up to a third longer than the English text,
# so the English description has to stay well below the limit.
FULL_DESCRIPTION_BUDGET = 3000


class GetLanguageTagTest(unittest.TestCase):
    def test_converts_android_code(self):
        self.assertEqual(get_language_tag("de-rDE"), "de-DE")
        self.assertEqual(get_language_tag("zh-rTW"), "zh-TW")

    def test_shortens_mapped_languages(self):
        self.assertEqual(get_language_tag("uk-rUA"), "uk")
        self.assertEqual(get_language_tag("sr-rSP"), "sr")


class ComposeListingTest(unittest.TestCase):
    def test_composes_fields(self):
        listing = compose_listing(
            {
                "listing_store_google_app_name": "Keyguard",
                "listing_store_google_summary": "Password manager.",
            },
            {"title": "Essentials"},
            '<b><keyguard_text_res token="title" /></b>\n',
        )
        self.assertEqual(listing, {
            "title": "Keyguard",
            "shortDescription": "Password manager.",
            "fullDescription": "<b>Essentials</b>",
        })

    def test_reports_fields_over_the_limit(self):
        listing = {
            "title": "Keyguard",
            "shortDescription": "a" * (LISTING_LIMITS["shortDescription"] + 1),
            "fullDescription": "a" * LISTING_LIMITS["fullDescription"],
        }
        violations = get_listing_violations(listing)
        self.assertEqual(len(violations), 1)
        self.assertIn("shortDescription", violations[0])


class DefaultListingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.listing = load_listings()[LANGUAGE_DEFAULT]

    def test_fits_the_limits(self):
        self.assertEqual(get_listing_violations(self.listing), [])
        self.assertLessEqual(len(self.listing["fullDescription"]), FULL_DESCRIPTION_BUDGET)

    def test_has_no_placeholders_or_escapes(self):
        for field in LISTING_LIMITS.keys():
            self.assertTrue(self.listing[field], field)
            self.assertNotIn("keyguard_text_res", self.listing[field], field)
            self.assertNotIn("\\", self.listing[field], field)


if __name__ == "__main__":
    unittest.main()
