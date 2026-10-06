import SwiftUI

/// Keeps the label and counts on one line while the flow wraps complete chips.
struct WatchtowerStrengthChipContent<Badge: View>: View {
    let title: String
    let color: Color
    let count: Int32
    @ViewBuilder var badge: () -> Badge

    var body: some View {
        HStack(spacing: 8) {
            strengthLabel
            counts
        }
        .lineLimit(1)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .touchTarget()
        .background(GroupedSurfaceStyle.content, in: RoundedRectangle(cornerRadius: 22))
    }

    private var strengthLabel: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 10, height: 10)
            Text(title)
                .font(.callout)
        }
    }

    private var counts: some View {
        HStack(spacing: 8) {
            Text("\(count)")
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
            badge()
        }
        .fixedSize(horizontal: true, vertical: false)
    }
}
