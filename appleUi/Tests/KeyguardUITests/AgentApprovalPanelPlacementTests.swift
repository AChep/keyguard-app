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
                size: firstSize, visibleFrame: visible, companionFrames: [])
            let firstFrame = CGRect(origin: firstOrigin, size: firstSize)
            let secondSize = sizes[1 - first]
            let secondOrigin = AgentApprovalPanelPlacement.origin(
                size: secondSize, visibleFrame: visible, companionFrames: [firstFrame])
            let secondFrame = CGRect(origin: secondOrigin, size: secondSize)
            XCTAssertFalse(firstFrame.intersects(secondFrame))
            XCTAssertTrue(visible.contains(firstFrame))
            XCTAssertTrue(visible.contains(secondFrame))
        }
    }

    func testSingleCompanionPlacesBesideItRightThenLeft() {
        let visible = CGRect(x: 0, y: 0, width: 1440, height: 900)
        let size = CGSize(width: 400, height: 300)
        let left = CGRect(x: 100, y: 200, width: 400, height: 500)
        XCTAssertEqual(
            AgentApprovalPanelPlacement.origin(size: size, visibleFrame: visible, companionFrames: [left]),
            CGPoint(x: 516, y: 300))
        let right = CGRect(x: 1000, y: 200, width: 400, height: 500)
        XCTAssertEqual(
            AgentApprovalPanelPlacement.origin(size: size, visibleFrame: visible, companionFrames: [right]),
            CGPoint(x: 584, y: 300))
    }

    func testThreeSimultaneousPromptsDoNotOverlapRegardlessOfArrivalOrder() {
        let visible = CGRect(x: 0, y: 0, width: 1440, height: 900)
        let sizes = [
            CGSize(width: 420, height: 360),
            CGSize(width: 460, height: 640),
            CGSize(width: 460, height: 600),
        ]
        let arrivalOrders = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]]
        for order in arrivalOrders {
            var frames: [CGRect] = []
            for index in order {
                let size = sizes[index]
                let origin = AgentApprovalPanelPlacement.origin(
                    size: size, visibleFrame: visible, companionFrames: frames)
                frames.append(CGRect(origin: origin, size: size))
            }
            for (index, frame) in frames.enumerated() {
                XCTAssertTrue(visible.contains(frame), "order \(order)")
                for other in frames[(index + 1)...] {
                    XCTAssertFalse(frame.intersects(other), "order \(order)")
                }
            }
        }
    }

    func testNarrowDisplayCascadesAndKeepsApprovalControlsOnScreen() {
        let visible = CGRect(x: -800, y: 100, width: 800, height: 700)
        let size = CGSize(width: 460, height: 640)
        let first = AgentApprovalPanelPlacement.origin(size: size, visibleFrame: visible, companionFrames: [])
        let second = AgentApprovalPanelPlacement.origin(
            size: size, visibleFrame: visible,
            companionFrames: [CGRect(origin: first, size: size)])
        XCTAssertNotEqual(first, second)
        XCTAssertTrue(visible.contains(CGRect(origin: second, size: size)))
    }

    func testNarrowDisplayCascadesThreePromptsFromTheNewest() {
        let visible = CGRect(x: -800, y: 100, width: 800, height: 700)
        let size = CGSize(width: 460, height: 640)
        var origins: [CGPoint] = []
        for _ in 0..<3 {
            let companions = origins.map { CGRect(origin: $0, size: size) }
            origins.append(
                AgentApprovalPanelPlacement.origin(size: size, visibleFrame: visible, companionFrames: companions))
        }
        XCTAssertEqual(Set(origins.map(\.x)).count, 3)
        XCTAssertGreaterThan(origins[2].x, origins[1].x)
        for origin in origins {
            XCTAssertTrue(visible.contains(CGRect(origin: origin, size: size)))
        }
    }
}
#endif
