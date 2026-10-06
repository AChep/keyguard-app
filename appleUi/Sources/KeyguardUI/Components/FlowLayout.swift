import SwiftUI

/// A flexbox-style layout: lays subviews out left-to-right, wrapping to a new
/// line when the next subview would overflow the proposed width. Mirrors the
/// Compose `FlowRow`.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    /// Clamps every subview to the container width and proposes the clamped size,
    /// so an over-wide subview (a long filename badge) truncates inside the row
    /// instead of spilling past its edge. Off by default: chips size to content.
    var clampsToWidth: Bool = false

    // Cache ideal sizes; only oversized children need another measurement for
    // the constrained width (which can change their height through wrapping).
    func makeCache(subviews: Subviews) -> [CGSize] {
        subviews.map { $0.sizeThatFits(.unspecified) }
    }

    func updateCache(_ cache: inout [CGSize], subviews: Subviews) {
        cache = subviews.map { $0.sizeThatFits(.unspecified) }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout [CGSize]) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        var totalHeight: CGFloat = 0

        for index in subviews.indices {
            let size = measure(index, maxWidth: maxWidth, subviews: subviews, cache: cache)
            if rowWidth > 0, rowWidth + spacing + size.width > maxWidth {
                totalWidth = max(totalWidth, rowWidth)
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
            rowHeight = max(rowHeight, size.height)
        }
        totalWidth = max(totalWidth, rowWidth)
        totalHeight += rowHeight
        return CGSize(width: totalWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout [CGSize]) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        // A clamped subview measures exactly `bounds.width`, which a strict `>`
        // against `maxX` can trip on floating-point noise; allow half a point.
        let wrapSlack: CGFloat = clampsToWidth ? 0.5 : 0

        for index in subviews.indices {
            let size = measure(index, maxWidth: bounds.width, subviews: subviews, cache: cache)
            if x > bounds.minX, x + size.width > bounds.maxX + wrapSlack {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subviews[index].place(
                at: CGPoint(x: x, y: y),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }

    private func measure(_ index: Subviews.Index, maxWidth: CGFloat, subviews: Subviews, cache: [CGSize]) -> CGSize {
        let ideal = cache.indices.contains(index) ? cache[index] : subviews[index].sizeThatFits(.unspecified)
        guard clampsToWidth, ideal.width > maxWidth else { return ideal }
        let constrained = subviews[index].sizeThatFits(ProposedViewSize(width: maxWidth, height: nil))
        return CGSize(width: min(constrained.width, maxWidth), height: constrained.height)
    }
}
