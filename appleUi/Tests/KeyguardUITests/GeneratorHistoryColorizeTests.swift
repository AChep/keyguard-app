import KeyguardShared
import XCTest
@testable import KeyguardUI

final class GeneratorHistoryColorizeTests: XCTestCase {
    func testHistoryUsesTypeMetadataForBothPresentations() {
        for type in ["USERNAME", "EMAIL", "EMAIL_RELAY"] {
            XCTAssertFalse(item(type: type).colorize)
        }
        for type in ["PASSWORD", "SSH_KEY", "GPG_KEY", nil] {
            XCTAssertTrue(item(type: type).colorize)
        }
    }

    private func item(type: String?) -> GeneratorHistoryItemSnapshot {
        GeneratorHistoryItemSnapshot(
            id: "fixture", kind: .value, title: "demo123@example.com", date: nil,
            type: type, actions: [], selected: false, selecting: false
        )
    }
}
