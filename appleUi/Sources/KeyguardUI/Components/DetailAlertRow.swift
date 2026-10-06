import SwiftUI
import KeyguardShared

/// Security notices retain their actions while making the severity visible and spoken.
struct DetailAlertRow: View {
    let item: VaultItemSnapshot
    let severity: VaultAlertSeverity
    let invoke: (String) -> Void

    private var actionId: String? { item.clickActionId ?? item.actions.first?.id }
    private var appearance: DetailAlertAppearance { DetailAlertAppearance(severity: severity) }

    var body: some View {
        DetailAccessoryRow {
            if let actionId {
                Button {
                    invoke(actionId)
                } label: {
                    label
                        .touchTarget()
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                label
            }
        } accessories: {
            DetailActionMenu(actions: item.actions.filter { $0.id != actionId }, invoke: invoke)
        }
        .padding(.vertical, 4)
        #if os(iOS)
        .listRowBackground(appearance.color.opacity(0.10))
        #else
        .padding(10)
        .background(appearance.color.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
        #endif
    }

    private var label: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: appearance.symbol)
                .foregroundStyle(appearance.color)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                if let title = item.title, !title.isEmpty {
                    Text(title)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.primary)
                }
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if actionId != nil {
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityValue(appearance.accessibilityLabel)
    }
}
