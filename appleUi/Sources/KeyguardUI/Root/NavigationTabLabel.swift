import SwiftUI

#if os(iOS)
struct NavigationTabLabel: View {
    let title: String
    let systemImage: String
    let showTitle: Bool

    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: systemImage)
                .font(.title3)
            if showTitle {
                Text(title)
                    .font(.caption)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        .frame(minWidth: 44, minHeight: 48)
        .contentShape(Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
    }
}
#endif
