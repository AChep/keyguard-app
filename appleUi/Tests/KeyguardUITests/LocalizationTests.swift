import XCTest
@testable import KeyguardUI

final class LocalizationTests: XCTestCase {
    @MainActor
    func testPluralAccessorsFollowLanguageChanges() {
        let localization = AppLocalization.shared
        let originalLanguage = localization.languageTag
        defer { localization.languageTag = originalLanguage }

        let cases = [
            ("en", ["1 word", "2 words", "21 words"]),
            ("ru", ["1 слово", "2 слова", "21 слово"]),
            ("uk", ["1 слово", "2 слова", "21 слово"]),
            ("en", ["1 word", "2 words", "21 words"]),
        ]
        for (language, expected) in cases {
            localization.languageTag = language
            XCTAssertEqual(
                [1, 2, 21].map { L10n.wordCountPlural($0) },
                expected,
                "Plural rules should follow the \(language) app language"
            )
        }
    }
}
