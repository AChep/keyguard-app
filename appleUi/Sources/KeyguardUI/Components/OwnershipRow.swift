import SwiftUI
import KeyguardShared

/// The "Save to" row that opens the account, organization, collections, and folder picker.
struct OwnershipRow: View {
    let ownership: AddOwnershipSnapshot
    let onPick: () -> Void

    var body: some View {
        Button(action: onPick) {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle")
                    .foregroundStyle(.tint)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.saveTo)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(ownership.title)
                        .foregroundStyle(.primary)
                    if let text = ownership.text, !text.isEmpty {
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if ownership.canPick {
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!ownership.canPick)
    }
}
