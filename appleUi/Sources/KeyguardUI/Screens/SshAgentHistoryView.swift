import SwiftUI
import KeyguardShared

struct SshAgentHistoryView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel

    /// Limits the history to one cipher (the cipher detail's action); `nil` shows all.
    var cipherId: String? = nil

    private var snapshot: SshAgentHistorySnapshot { sshAgentModel.sshAgentHistory }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            empty
        } content: {
            list
        }
        .observing(
            start: { sshAgentModel.startSshAgentHistoryObservation(cipherId: cipherId) },
            stop: { sshAgentModel.stopSshAgentHistoryObservation() }
        )
    }

    private var empty: some View {
        ContentUnavailableView {
            Label(L10n.agentHistoryEmptyLabel, systemImage: "clock.arrow.circlepath")
        } description: {
            Text(L10n.sshAgentHistoryEmptyText)
        }
    }

    private var list: some View {
        List {
            ForEach(historyGroups(snapshot.items)) { group in
                if group.title.isEmpty {
                    ForEach(group.items, id: \.id) { row($0) }
                } else {
                    Section(group.title) {
                        ForEach(group.items, id: \.id) { row($0) }
                    }
                }
            }
        }
    }

    private func row(_ item: SshAgentHistoryItemSnapshot) -> some View {
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

    /// Maps the response category (the uppercase `SshUsageHistoryResponseType`
    /// name) to an SF Symbol.
    private func responseIcon(_ response: String?) -> String {
        switch response {
        case "SUCCESS": return "checkmark.circle.fill"
        case "USER_DENIED": return "hand.raised.fill"
        case "KEY_NOT_FOUND": return "questionmark.circle.fill"
        case "FAILURE": return "xmark.octagon.fill"
        default: return "circle"
        }
    }

    private func responseColor(_ response: String?) -> Color {
        switch response {
        case "SUCCESS": return .green
        case "USER_DENIED": return .orange
        case "KEY_NOT_FOUND": return .yellow
        case "FAILURE": return .red
        default: return .secondary
        }
    }
}

private struct HistoryGroup: Identifiable {
    let id: String
    /// The date header, taken from the section marker's `caller` (the shared
    /// producer reuses that field for the section title). Empty for the leading
    /// group of rows that precede any SECTION marker.
    let title: String
    var items: [SshAgentHistoryItemSnapshot]
}

/// Groups the flat history list into sections keyed by their preceding SECTION
/// marker, so the list can render native `Section` headers.
private func historyGroups(_ items: [SshAgentHistoryItemSnapshot]) -> [HistoryGroup] {
    var result: [HistoryGroup] = []
    var current = HistoryGroup(id: "", title: "", items: [])
    for item in items {
        if item.kind == SshAgentHistoryItemKind.section {
            if !current.items.isEmpty {
                result.append(current)
            }
            current = HistoryGroup(id: item.id, title: item.caller, items: [])
        } else {
            current.items.append(item)
        }
    }
    if !current.items.isEmpty {
        result.append(current)
    }
    return result
}
