#if os(macOS)
import CoreGraphics
import Foundation
import XCTest
@testable import KeyguardUI

final class AgentApprovalPanelPlacementTests: XCTestCase {
    func testSimultaneousPromptsFitBesideEachOtherRegardlessOfArrivalOrder() {
        let visible = CGRect(x: 0, y: 0, width: 1440, height: 900)
        let sizes = [CGSize(width: 420, height: 360), CGSize(width: 460, height: 640)]
        for first in 0..<2 {
            let firstSize = sizes[first]
            let firstOrigin = AgentApprovalPanelPlacement.origin(
                size: firstSize, visibleFrame: visible, companionFrame: nil)
            let firstFrame = CGRect(origin: firstOrigin, size: firstSize)
            let secondSize = sizes[1 - first]
            let secondOrigin = AgentApprovalPanelPlacement.origin(
                size: secondSize, visibleFrame: visible, companionFrame: firstFrame)
            let secondFrame = CGRect(origin: secondOrigin, size: secondSize)
            XCTAssertFalse(firstFrame.intersects(secondFrame))
            XCTAssertTrue(visible.contains(firstFrame))
            XCTAssertTrue(visible.contains(secondFrame))
        }
    }

    func testNarrowDisplayCascadesAndKeepsApprovalControlsOnScreen() {
        let visible = CGRect(x: -800, y: 100, width: 800, height: 700)
        let size = CGSize(width: 460, height: 640)
        let first = AgentApprovalPanelPlacement.origin(size: size, visibleFrame: visible, companionFrame: nil)
        let second = AgentApprovalPanelPlacement.origin(
            size: size, visibleFrame: visible,
            companionFrame: CGRect(origin: first, size: size))
        XCTAssertNotEqual(first, second)
        XCTAssertTrue(visible.contains(CGRect(origin: second, size: size)))
    }
}
#endif
