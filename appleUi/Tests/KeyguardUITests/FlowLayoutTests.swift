#if os(macOS)
import SwiftUI
import XCTest
@testable import KeyguardUI

final class FlowLayoutTests: XCTestCase {
    @MainActor
    func testWidthClampingReservesWrappedTextHeight() {
        let text = "A long badge title that must wrap over several lines in a narrow container."
        let direct = NSHostingView(rootView: Text(text).frame(width: 90))
        let flow = NSHostingView(
            rootView: FlowLayout(clampsToWidth: true) {
                Text(text)
            }.frame(width: 90))
        XCTAssertEqual(flow.fittingSize.width, direct.fittingSize.width, accuracy: 0.5)
        XCTAssertEqual(flow.fittingSize.height, direct.fittingSize.height, accuracy: 0.5)
    }

    @MainActor
    func testWrappingReservesHeightBeforeTheNextRow() {
        let text = "A long badge title that must wrap over several lines in a narrow container."
        let first = NSHostingView(rootView: Text(text).frame(width: 90))
        let next = NSHostingView(rootView: Text("Next row"))
        let flow = NSHostingView(
            rootView: FlowLayout(spacing: 8, clampsToWidth: true) {
                Text(text)
                Text("Next row")
            }.frame(width: 90))
        XCTAssertEqual(flow.fittingSize.height, first.fittingSize.height + 8 + next.fittingSize.height, accuracy: 0.5)
    }
}
#endif
