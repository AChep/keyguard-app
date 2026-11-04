import SwiftUI
import KeyguardShared

struct CliAgentHistoryView: View {
    let snapshot: CliAgentHistorySnapshot
    /// Clears the log shown; offered only while `snapshot.canClear`.
    let clear: () -> Void

    @State private var confirmingClear = false
    @State private var presentedItem: ListDetailSheetItem?

    var body: some View {
        VStack(spacing: 0) {
            if snapshot.subtitle != nil || snapshot.canClear {
                HStack {
                    if let subtitle = snapshot.subtitle { Text(subtitle).foregroundStyle(.secondary) }
                    Spacer()
                    if snapshot.canClear {
                        Button(L10n.agentHistoryClearHistoryTitle, role: .destructive) { confirmingClear = true }
                    }
                }
                .padding(16)
                Divider()
            }
            SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
                ContentUnavailableView(L10n.agentHistoryEmptyLabel, systemImage: "clock.arrow.circlepath")
            } content: {
                list
            }
        }
        .confirmationDialog(
            L10n.cliAgentHistoryClearHistoryConfirmationTitle,
            isPresented: $confirmingClear, titleVisibility: .visible
        ) {
            Button(L10n.agentHistoryClearHistoryTitle, role: .destructive) { clear() }
        } message: {
            Text(L10n.cliAgentHistoryClearHistoryConfirmationText)
        }
        .sheet(item: $presentedItem) { presented in
            if let details = snapshot.items.first(where: { $0.id == presented.id })?.details {
                AgentHistoryDetailSheet(details: details)
            }
        }
        .onChange(of: snapshot.items.map(\.id)) { _, ids in
            // The row is gone, e.g. the log was cleared.
            if let presentedItem, !ids.contains(presentedItem.id) {
                self.presentedItem = nil
            }
        }
    }

    private var list: some View {
        List {
            // The shared producer reuses a section marker's `caller` for its date header.
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == CliAgentHistoryItemKind.section ? $0.caller : nil
                    })
            ) { section in
                if let title = section.title {
                    Section(title) {
                        ForEach(section.items, id: \.id) { rowEntry($0) }
                    }
                } else {
                    ForEach(section.items, id: \.id) { rowEntry($0) }
                }
            }
        }
    }

    @ViewBuilder
    private func rowEntry(_ item: CliAgentHistoryItemSnapshot) -> some View {
        if item.details != nil {
            Button {
                presentedItem = ListDetailSheetItem(id: item.id)
            } label: {
                row(item)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            row(item)
        }
    }

    private func row(_ item: CliAgentHistoryItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: responseIcon(item.response))
                .foregroundStyle(responseColor(item.response))
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.caller)
                    .font(.body)
                    .lineLimit(1)
                    .truncationMode(.middle)
                // Kotlin exports this field with a suffix to avoid NSObject.description.
                if !item.description_.isEmpty {
                    Text(item.description_)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                // The command line and its directory, or the path of a local .env file.
                if let commandText = item.commandText, !commandText.isEmpty {
                    Text(commandText)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                }
                HStack(spacing: 6) {
                    if !item.responseText.isEmpty {
                        Text(item.responseText)
                            .font(.caption2)
                            .foregroundStyle(responseColor(item.response))
                    }
                    if let date = item.date, !date.isEmpty {
                        Text(date)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    /// Maps the response category (the uppercase `CliAgentUsageHistoryResponseType`
    /// name) to an SF Symbol.
    private func responseIcon(_ response: String?) -> String {
        switch response {
        case "SUCCESS": return "checkmark.circle.fill"
        case "USER_DENIED": return "hand.raised.fill"
        case "NOT_FOUND": return "questionmark.circle.fill"
        case "VAULT_LOCKED": return "lock.fill"
        case "TIMED_OUT": return "hourglass"
        case "CANCELLED": return "xmark.circle"
        case "FAILURE": return "xmark.octagon.fill"
        default: return "circle"
        }
    }

    private func responseColor(_ response: String?) -> Color {
        switch response {
        case "SUCCESS": return .green
        case "USER_DENIED", "NOT_FOUND", "VAULT_LOCKED", "TIMED_OUT", "FAILURE": return .red
        default: return .secondary
        }
    }
}
