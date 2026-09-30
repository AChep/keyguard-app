import SwiftUI

/// A Settings category with a system-colored tile. The surrounding native list
/// supplies the background, separators, disclosure indicator, and selection.
struct SettingsCategoryLabel: View {
    let title: String
    let id: String
    /// Where a search result lives, e.g. "Security › Vault".
    var subtitle: String? = nil

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
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
