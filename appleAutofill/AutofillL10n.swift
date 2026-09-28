import Foundation

/// Localized string from the appex's own bundle.
///
/// The AutoFill extensions deliberately do not link the KeyguardUI package (that
/// would drag the whole screen layer into the appex), so they cannot use its
/// generated `L10n` / `Bundle.module`. The same String Catalog is bundled directly
/// into each appex instead and resolved against `Bundle.main` here.
///
/// Keys are therefore unchecked at compile time — they must match the catalog
/// generated from the shared `strings.xml` by `:common:generateAppleStrings`. The
/// post-build `verify-apple-strings.py` phase checks the compiled tables.
// The single-letter name mirrors the generated `L10n` of the main app and keeps the
// call sites in the appex views short; it is the one deliberate exception.
// swift-format-ignore: AlwaysUseLowerCamelCase
func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }
