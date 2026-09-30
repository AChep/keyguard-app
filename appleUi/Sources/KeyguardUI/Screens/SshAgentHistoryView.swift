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
            // The shared producer reuses a section marker's `caller` for its date header.
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == SshAgentHistoryItemKind.section ? $0.caller : nil
                    })
            ) { section in
                if let title = section.title {
                    Section(title) {
                        ForEach(section.items, id: \.id) { row($0) }
                    }
                } else {
                    ForEach(section.items, id: \.id) { row($0) }
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
