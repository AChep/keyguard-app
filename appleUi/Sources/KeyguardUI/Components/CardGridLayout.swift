import SwiftUI

/// Adaptive cards with content-sized rows. Every card receives its row's tallest
/// measured height, so flexible card backgrounds and hit areas fill the row.
struct CardGridLayout: Layout {
    // Fits two cards on a 402-point iPhone with 24-point outer padding.
    // Narrower containers collapse to one column; wider ones gain columns.
    var minimumColumnWidth: CGFloat = 160
    var spacing: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let width = resolvedWidth(proposal.width, subviews: subviews)
        let columns = columnCount(width: width)
        let columnWidth = (width - CGFloat(columns - 1) * spacing) / CGFloat(columns)
        let heights = rowHeights(subviews: subviews, columns: columns, columnWidth: columnWidth)
        return CGSize(
            width: width,
            height: heights.reduce(0, +) + CGFloat(heights.count - 1) * spacing
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        guard !subviews.isEmpty else { return }
        let columns = columnCount(width: bounds.width)
        let columnWidth = (bounds.width - CGFloat(columns - 1) * spacing) / CGFloat(columns)
        let heights = rowHeights(subviews: subviews, columns: columns, columnWidth: columnWidth)
        var y = bounds.minY
        for (row, height) in heights.enumerated() {
            for column in 0..<columns {
                let index = row * columns + column
                guard index < subviews.count else { break }
                subviews[index].place(
                    at: CGPoint(x: bounds.minX + CGFloat(column) * (columnWidth + spacing), y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(width: columnWidth, height: height)
                )
            }
            y += height + spacing
        }
    }

    private func resolvedWidth(_ proposedWidth: CGFloat?, subviews: Subviews) -> CGFloat {
        if let proposedWidth, proposedWidth.isFinite {
            return max(0, proposedWidth)
        }
        // An unspecified/infinite proposal asks for a finite ideal size.
        return subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? minimumColumnWidth
    }

    private func columnCount(width: CGFloat) -> Int {
        max(1, Int((width + spacing) / (minimumColumnWidth + spacing)))
    }

    private func rowHeights(subviews: Subviews, columns: Int, columnWidth: CGFloat) -> [CGFloat] {
        stride(from: 0, to: subviews.count, by: columns).map { start in
            (start..<min(start + columns, subviews.count)).map { index in
                subviews[index].sizeThatFits(ProposedViewSize(width: columnWidth, height: nil)).height
            }.max() ?? 0
        }
    }
}
