#if os(macOS)
import SwiftUI
import KeyguardShared

struct GpgAgentHistoryView: View {
    @Environment(GpgAgentModel.self) private var gpgAgentModel

    @State private var confirmingClear = false

    private var snapshot: GpgAgentHistorySnapshot { gpgAgentModel.gpgAgentHistory }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                if let subtitle = snapshot.subtitle { Text(subtitle).foregroundStyle(.secondary) }
                Spacer()
                Button(L10n.agentHistoryClearHistoryTitle, role: .destructive) { confirmingClear = true }
                    .disabled(!snapshot.canClear)
            }
            .padding(16)
            Divider()
            SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
                empty
            } content: {
                list
            }
        }
        .confirmationDialog(
            L10n.agentHistoryClearHistoryConfirmationTitle(L10n.protocolGpg),
            isPresented: $confirmingClear, titleVisibility: .visible
        ) {
            Button(L10n.agentHistoryClearHistoryTitle, role: .destructive) {
                gpgAgentModel.clearGpgAgentHistory()
            }
        } message: {
            Text(L10n.agentHistoryClearHistoryConfirmationText(L10n.protocolGpg))
        }
        .observing(
            start: { gpgAgentModel.startGpgAgentHistoryObservation() },
            stop: { gpgAgentModel.stopGpgAgentHistoryObservation() }
        )
    }

    private var empty: some View {
        ContentUnavailableView {
            Label(L10n.agentHistoryEmptyLabel, systemImage: "clock.arrow.circlepath")
        } description: {
            Text(L10n.gpgAgentSetupIntro)
        }
    }

    private var list: some View {
        List {
            // The shared producer reuses a section marker's `caller` for its date header.
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == GpgAgentHistoryItemKind.section ? $0.caller : nil
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

    private func row(_ item: GpgAgentHistoryItemSnapshot) -> some View {
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
                if let operation = operationTitle(item.request) {
                    Text(operation).font(.caption).foregroundStyle(.secondary)
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

    private func operationTitle(_ request: String?) -> String? {
        switch request {
        case "AGENT_SIGN_HASH": return L10n.gpgAgentHistoryRequestSignHash
        case "AGENT_DECRYPT": return L10n.gpgAgentHistoryRequestDecrypt
        case "AGENT_LIST_KEYS": return L10n.agentHistoryRequestListKeys
        default: return nil
        }
    }

    /// Maps the response category (the uppercase `GpgUsageHistoryResponseType`
    /// name) to an SF Symbol.
    private func responseIcon(_ response: String?) -> String {
        switch response {
        case "SUCCESS": return "checkmark.circle.fill"
        case "USER_DENIED": return "hand.raised.fill"
        case "KEY_NOT_FOUND": return "questionmark.circle.fill"
        case "VAULT_LOCKED": return "lock.fill"
        case "UNSUPPORTED": return "nosign"
        case "FAILURE": return "xmark.octagon.fill"
        default: return "circle"
        }
    }

    private func responseColor(_ response: String?) -> Color {
        switch response {
        case "SUCCESS": return .green
        case "USER_DENIED": return .orange
        case "KEY_NOT_FOUND": return .yellow
        case "VAULT_LOCKED", "UNSUPPORTED": return .orange
        case "FAILURE": return .red
        default: return .secondary
        }
    }
}

#endif
