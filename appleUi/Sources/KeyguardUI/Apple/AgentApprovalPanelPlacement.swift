#if os(macOS)
import CoreGraphics
import Foundation

/// Keep every agent's prompt visible without depending on which one arrived first.
enum AgentApprovalPanelPlacement {
    /// - Parameter companionFrames: The frames of the other visible approval panels.
    static func origin(size: CGSize, visibleFrame: CGRect, companionFrames: [CGRect]) -> CGPoint {
        let bounds = visibleFrame.insetBy(dx: 12, dy: 12)
        func bounded(_ point: CGPoint) -> CGPoint {
            CGPoint(
                x: max(bounds.minX, min(point.x, bounds.maxX - size.width)),
                y: max(bounds.minY, min(point.y, bounds.maxY - size.height))
            )
        }
        // The prompt furthest along the cascade; the narrow-display fallback steps from it.
        guard let newest = companionFrames.max(by: { $0.minX - $0.minY < $1.minX - $1.minY }) else {
            return bounded(
                CGPoint(
                    x: bounds.midX - size.width / 2,
                    y: bounds.midY - size.height / 2 + bounds.height * 0.12
                ))
        }
        func isFree(_ origin: CGPoint) -> Bool {
            let frame = CGRect(origin: origin, size: size)
            return !companionFrames.contains { $0.intersects(frame) }
        }
        for companionFrame in companionFrames {
            let y = companionFrame.midY - size.height / 2
            let right = companionFrame.maxX + 16
            if right + size.width <= bounds.maxX {
                let origin = bounded(CGPoint(x: right, y: y))
                if isFree(origin) { return origin }
            }
            let left = companionFrame.minX - 16 - size.width
            if left >= bounds.minX {
                let origin = bounded(CGPoint(x: left, y: y))
                if isFree(origin) { return origin }
            }
        }
        // A narrow display cannot fit the prompts side by side. Leave a visible edge
        // of the older prompts, while keeping the new prompt's controls on screen.
        return bounded(CGPoint(x: newest.minX + 32, y: newest.minY - 32))
    }
}
#endif
