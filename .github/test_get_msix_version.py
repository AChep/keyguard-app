import unittest

from get_msix_version import count_earlier_releases, get_msix_version


def _release(semantic: str, tag: str):
    return {"version": {"semantic": semantic, "tag": tag}}


class GetMsixVersionTest(unittest.TestCase):
    def test_first_release_of_a_version(self):
        self.assertEqual(get_msix_version("3.2.0", 0), "3.2.0.0")

    def test_repeated_release_of_a_version(self):
        self.assertEqual(get_msix_version("3.1.0", 2), "3.1.2.0")

    def test_patch_version_orders_above_repeated_releases(self):
        self.assertGreater(
            tuple(map(int, get_msix_version("3.1.1", 0).split("."))),
            tuple(map(int, get_msix_version("3.1.0", 99).split("."))),
        )

    def test_rejects_non_semantic_version(self):
        with self.assertRaises(ValueError):
            get_msix_version("3.2", 0)

    def test_rejects_ordinal_overflow(self):
        with self.assertRaises(ValueError):
            get_msix_version("3.2.0", 100)

    def test_counts_only_other_tags_with_same_version(self):
        items = [
            _release("3.0.4", "r20260819"),
            _release("3.0.4", "r20260819.1"),
            _release("3.1.0", "r20260903"),
            _release("3.1.0", "r20260903.1"),
            _release("3.1.0", "r20260904"),
            "garbage",
        ]
        self.assertEqual(count_earlier_releases(items, "3.1.0", "r20260904"), 2)
        self.assertEqual(count_earlier_releases(items, "3.2.0", "r20260905"), 0)


if __name__ == "__main__":
    unittest.main()
