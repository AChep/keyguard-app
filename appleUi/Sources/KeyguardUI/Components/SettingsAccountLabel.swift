import KeyguardShared
import SwiftUI

struct SettingsAccountLabel: View {
    let account: AccountListItemSnapshot
    let selecting: Bool

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(account.title)
                    .fixedSize(horizontal: false, vertical: true)
                if let host = account.host, !host.isEmpty, host != account.title {
                    Text(host)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            if selecting {
                SettingsRowIcon(
                    systemName: account.selected ? "checkmark.circle.fill" : "circle",
                    color: account.selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary),
                    isTile: false
                )
            } else {
                SettingsRowIcon(
                    systemName: account.error ? "exclamationmark.triangle" : "person.crop.circle",
                    color: account.error ? AnyShapeStyle(.red) : AnyShapeStyle(.tint)
                )
            }
        }
        .labelStyle(SettingsRowLabelStyle())
        .padding(.vertical, 2)
    }
}
