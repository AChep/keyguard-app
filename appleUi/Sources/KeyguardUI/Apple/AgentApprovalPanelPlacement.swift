#if os(macOS)
import CoreGraphics
import Foundation

/// Keep both agents' prompts visible without depending on which one arrived first.
enum AgentApprovalPanelPlacement {
    static func origin(size: CGSize, visibleFrame: CGRect, companionFrame: CGRect?) -> CGPoint {
        let bounds = visibleFrame.insetBy(dx: 12, dy: 12)
        func bounded(_ point: CGPoint) -> CGPoint {
            CGPoint(
                x: max(bounds.minX, min(point.x, bounds.maxX - size.width)),
                y: max(bounds.minY, min(point.y, bounds.maxY - size.height))
            )
        }
        guard let companionFrame else {
            return bounded(
                CGPoint(
                    x: bounds.midX - size.width / 2,
                    y: bounds.midY - size.height / 2 + bounds.height * 0.12
                ))
        }
        let y = companionFrame.midY - size.height / 2
        let right = companionFrame.maxX + 16
        if right + size.width <= bounds.maxX {
            return bounded(CGPoint(x: right, y: y))
        }
        let left = companionFrame.minX - 16 - size.width
        if left >= bounds.minX {
            return bounded(CGPoint(x: left, y: y))
        }
        // A narrow display cannot fit both side by side. Leave a visible edge of
        // the older prompt, while keeping the new prompt's controls on screen.
        return bounded(CGPoint(x: companionFrame.minX + 32, y: companionFrame.minY - 32))
    }
}
#endif
