#if os(macOS)
import SwiftUI
import XCTest
@testable import KeyguardUI

final class FlowLayoutBadgeTests: XCTestCase {
    @MainActor
    func testOversizedLocalizedBadgeKeepsCountsOnSameLineAndWrapsNextChip() {
        let badge = WatchtowerStrengthChipContent(title: "Дуже надійний", color: .green, count: 1234) {
            Text("+99")
        }
        let constrained = NSHostingView(rootView: badge.frame(width: 150))
        let next = NSHostingView(rootView: Text("Наступний"))
        let flow = NSHostingView(
            rootView: FlowLayout(spacing: 8, clampsToWidth: true) {
                badge
                Text("Наступний")
            }.frame(width: 150))

        XCTAssertEqual(flow.fittingSize.width, 150, accuracy: 0.5)
        XCTAssertEqual(
            flow.fittingSize.height, constrained.fittingSize.height + 8 + next.fittingSize.height, accuracy: 0.5)
        XCTAssertEqual(
            constrained.fittingSize.height, NSHostingView(rootView: badge).fittingSize.height, accuracy: 0.5)
    }
}
#endif
