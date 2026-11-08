import SwiftUI

/// A list row that opens its menu when tapped anywhere on the row, not only on
/// the label. The plain button style drops the default accent and the disabled
/// dimming, so the label sets both explicitly.
struct RowMenu<Content: View>: View {
    @Environment(\.isEnabled) private var isEnabled

    let title: String
    let systemImage: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        Menu {
            content()
        } label: {
            Label(title, systemImage: systemImage)
                .foregroundStyle(isEnabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
    }
}
