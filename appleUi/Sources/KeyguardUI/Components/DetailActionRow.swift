import SwiftUI
import KeyguardShared

/// Uses a single primary button when the producer already gave it the row's title.
struct DetailActionRow: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void

    private var primaryAction: VaultActionSnapshot? {
        guard item.actions.count == 1, let action = item.actions.first else { return nil }
        return action.title == item.title || (item.title == nil && action.title == item.text) ? action : nil
    }

    var body: some View {
        if let clickActionId = item.clickActionId {
            DetailAccessoryRow {
                Button {
                    invoke(clickActionId)
                } label: {
                    DetailRowLabel(
                        title: item.title ?? "",
                        subtitle: item.text,
                        disclosure: "chevron.forward"
                    )
                    .touchTarget()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } accessories: {
                DetailActionMenu(actions: item.actions.filter { $0.id != clickActionId }, invoke: invoke)
            }
        } else if let action = primaryAction {
            Button {
                invoke(action.id)
            } label: {
                DetailRowLabel(
                    title: action.title,
                    subtitle: item.text == action.title ? nil : item.text
                )
                .foregroundStyle(.tint)
                .touchTarget()
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                if let title = item.title, !title.isEmpty {
                    DetailRowLabel(title: title, subtitle: item.text)
                } else if let text = item.text, !text.isEmpty {
                    Text(text)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
                if !item.actions.isEmpty {
                    FlowLayout(spacing: 8, clampsToWidth: true) {
                        ForEach(item.actions, id: \.id) { action in
                            Button(action.title) { invoke(action.id) }
                                .buttonStyle(.bordered)
                                .touchTarget()
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
