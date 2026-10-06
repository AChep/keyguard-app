#if os(macOS)
import SwiftUI
import XCTest
import KeyguardShared
@testable import KeyguardUI

final class DetailFieldLayoutTests: XCTestCase {
    @MainActor
    func testLongMenuValueWrapsWithinNarrowForm() {
        let wide = field(width: 800)
        let narrow = field(width: 220)
        XCTAssertEqual(narrow.fittingSize.width, 220, accuracy: 0.5)
        XCTAssertGreaterThan(narrow.fittingSize.height, wide.fittingSize.height)
    }

    @MainActor
    func testAccessibilitySizeStacksEvenWhenTheRowIsWide() {
        let regular = NSHostingView(rootView: shortField.dynamicTypeSize(.large).frame(width: 800))
        let accessible = NSHostingView(rootView: shortField.dynamicTypeSize(.accessibility3).frame(width: 800))
        XCTAssertGreaterThan(accessible.fittingSize.height, regular.fittingSize.height)
    }

    @MainActor
    func testPlainMultilineValueReservesItsHeight() {
        let value = "First line\nSecond line\nThird line"
        let text = NSHostingView(rootView: Text(value).frame(width: 220))
        let cell = NSHostingView(
            rootView: FieldCell(title: "Notes", actions: [], invoke: { _ in }) {
                Text(value)
            }.frame(width: 220))
        XCTAssertEqual(cell.fittingSize.width, 220, accuracy: 0.5)
        XCTAssertGreaterThanOrEqual(cell.fittingSize.height, text.fittingSize.height)
    }

    @MainActor
    private var shortField: some View {
        FieldCell(title: "Username", actions: [], invoke: { _ in }) {
            Text("demo@example.com")
        }
    }

    @MainActor
    private func field(width: CGFloat) -> NSHostingView<some View> {
        NSHostingView(
            rootView: FieldCell(
                title: "Fingerprint",
                actions: [
                    VaultActionSnapshot(
                        id: "copy", title: "Copy fingerprint", isCopy: true,
                        iconName: nil, startsSection: false, switchState: nil, danger: false
                    )
                ],
                invoke: { _ in }
            ) {
                Text(String(repeating: "0123456789ABCDEF", count: 4))
                    .font(.body.monospaced())
            }.frame(width: width))
    }
}
#endif
