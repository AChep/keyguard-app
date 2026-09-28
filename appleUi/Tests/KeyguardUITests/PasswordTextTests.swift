import SwiftUI
import XCTest
@testable import KeyguardUI

final class PasswordTextTests: XCTestCase {
    func testClassificationMatchesCompose() {
        assertStyle("aZéЖ中ǅʰא", color: nil)
        assertStyle("09٠۹९９", color: .blue)
        assertStyle("!_ •\t\n\r\u{301}\u{FE0F}²Ⅳ", color: .red)
        // Supplementary letters, decimal digits, emoji and symbols stay plain.
        assertStyle("𐐀𝟠🔐🙂𝄞", color: nil)
    }

    func testPreservesExactUnicodeScalarsAndOnlyColorsEligibleScalars() {
        let original = "e\u{301} é 9️⃣ 👩🏽‍💻 🇺🇦\r\n\t<&>\u{0}𝟠"
        let attributed = colorizedPassword(original)
        XCTAssertEqual(Array(attributed.unicodeScalars), Array(original.unicodeScalars))
        for index in attributed.unicodeScalars.indices {
            let scalar = attributed.unicodeScalars[index]
            let end = attributed.unicodeScalars.index(after: index)
            XCTAssertEqual(attributed[index..<end].foregroundColor, PasswordCharacterStyle(scalar).color)
        }
    }

    func testEmptyAndDisabledColoring() {
        XCTAssertTrue(colorizedPassword("").characters.isEmpty)
        let original = "a9!e\u{301}🙂•"
        let attributed = colorizedPassword(original, enabled: false)
        XCTAssertEqual(Array(attributed.unicodeScalars), Array(original.unicodeScalars))
        XCTAssertTrue(attributed.runs.allSatisfy { $0.foregroundColor == nil })
    }

    func testMaskTransitionKeepsLiteralBulletColoredAndGeneratedBulletsPlain() {
        let value = ConcealedTextValue("•1a!", colorize: true)
        let hidden = value.display(progress: 0)
        XCTAssertEqual(String(hidden.characters), "••••••••")
        XCTAssertTrue(hidden.runs.allSatisfy { $0.foregroundColor == nil })

        let partial = value.display(progress: 0.5)
        XCTAssertEqual(String(partial.characters), "•1••")
        XCTAssertEqual(coloredText(partial, color: .red), "•")
        XCTAssertEqual(coloredText(partial, color: .blue), "1")
        XCTAssertEqual(coloredText(partial, color: nil), "••")
        XCTAssertEqual(value.display(progress: 1), colorizedPassword("•1a!"))
        // Concealing again uses the same runs and never colors generated padding.
        XCTAssertEqual(value.display(progress: 0), hidden)
    }

    func testMaskingPreservesGraphemesAndClampsAnimationOvershoot() {
        let original = "e\u{301}👩🏽‍💻9!"
        let value = ConcealedTextValue(original, colorize: true)
        XCTAssertEqual(String(value.display(progress: 0.5).characters), "e\u{301}👩🏽‍💻••")
        XCTAssertEqual(value.display(progress: -0.1), value.display(progress: 0))
        XCTAssertEqual(value.display(progress: 1.1), colorizedPassword(original))
        XCTAssertEqual(Array(value.display(progress: 1).unicodeScalars), Array(original.unicodeScalars))
    }

    func testEmptyMaskedAndDisabledValues() {
        let empty = ConcealedTextValue("", colorize: true)
        XCTAssertEqual(String(empty.display(progress: 0).characters), "••••••••")
        XCTAssertTrue(empty.display(progress: 1).characters.isEmpty)
        let plain = ConcealedTextValue("a1!•")
        for progress in [0.0, 0.25, 0.5, 0.75, 1.0] {
            XCTAssertTrue(plain.display(progress: progress).runs.allSatisfy { $0.foregroundColor == nil })
        }
        let long = String(repeating: "a1!", count: 100)
        XCTAssertEqual(ConcealedTextValue(long, colorize: true).display(progress: 1), colorizedPassword(long))
    }

    private func assertStyle(_ string: String, color: Color?, file: StaticString = #filePath, line: UInt = #line) {
        let attributed = colorizedPassword(string)
        XCTAssertEqual(Array(attributed.unicodeScalars), Array(string.unicodeScalars), file: file, line: line)
        XCTAssertTrue(attributed.runs.allSatisfy { $0.foregroundColor == color }, file: file, line: line)
    }

    private func coloredText(_ text: AttributedString, color: Color?) -> String {
        text.runs.filter { $0.foregroundColor == color }.map { String(text[$0.range].characters) }.joined()
    }
}
