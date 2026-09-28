import SwiftUI

/// A Settings category with a system-colored tile. The surrounding native list
/// supplies the background, separators, disclosure indicator, and selection.
struct SettingsCategoryLabel: View {
    let title: String
    let id: String

    var body: some View {
        Label {
            Text(title)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            SettingsRowIcon(
                systemName: SettingsIcon.symbol(for: id),
                color: AnyShapeStyle(SettingsIcon.color(for: id))
            )
            .accessibilityHidden(true)
        }
        .labelStyle(SettingsRowLabelStyle())
        .padding(.vertical, 2)
    }
}
