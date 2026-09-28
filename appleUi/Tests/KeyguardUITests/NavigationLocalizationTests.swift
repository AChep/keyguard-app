import Observation
import XCTest
@testable import KeyguardUI

final class NavigationLocalizationTests: XCTestCase {
    @MainActor
    func testDefaultSectionsTrackLanguageChangesAfterFirstAccess() async {
        let localization = AppLocalization.shared
        let originalLanguage = localization.languageTag
        defer { localization.languageTag = originalLanguage }

        // Prime the defaults before tracking, reproducing a previous presentation.
        _ = NavSection.defaults
        let invalidated = expectation(description: "Navigation labels observe the language override")
        withObservationTracking {
            _ = NavSection.defaults
        } onChange: {
            invalidated.fulfill()
        }

        localization.languageTag = originalLanguage == "uk" ? "en" : "uk"
        await fulfillment(of: [invalidated], timeout: 1)
        XCTAssertEqual(NavSection.defaults.first?.title, L10n.homeVaultLabel)
    }
}
