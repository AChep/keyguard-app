import Foundation
import XCTest
@testable import KeyguardUI

final class VaultTitleHighlightTests: XCTestCase {
    func testHighlightsUseKotlinUTF16OffsetsAfterEmojiAndCombiningMarks() {
        let title = "🔐 e\u{301}mail account"
        let location = (title as NSString).range(of: "account")
        let highlighted = highlightedText(title, utf16Ranges: [location.location..<NSMaxRange(location)])
        XCTAssertEqual(emphasizedText(highlighted), "account")
        XCTAssertEqual(String(highlighted.characters), title)
    }

    func testHighlightsWholeEmojiAndIgnoresInvalidUTF16Spans() {
        let highlighted = highlightedText("🔐 vault", utf16Ranges: [-1..<1, 0..<1, 0..<2, 5..<40])
        XCTAssertEqual(emphasizedText(highlighted), "🔐")
    }

    private func emphasizedText(_ value: AttributedString) -> String {
        value.runs
            .filter { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true }
            .map { String(value[$0.range].characters) }
            .joined()
    }
}
