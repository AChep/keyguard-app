import SwiftUI
import KeyguardShared

#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// The details of one request of the SSH, GPG or CLI agent request log.
struct AgentHistoryDetailSheet: View {
    let details: AgentHistoryDetailsSnapshot

    @Environment(NavigationModel.self) private var navigationModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ModalSheet(title: details.title, detents: [.medium, .large]) {
            Form {
                Section {
                    statusBadge
                }
                ForEach(Array(details.sections.enumerated()), id: \.element.id) { index, section in
                    Section {
                        ForEach(section.rows, id: \.id) { row($0) }
                    } header: {
                        if let title = section.title { Text(title) }
                    } footer: {
                        // When the request was made, under all of its details.
                        if index == details.sections.count - 1 {
                            Text(details.date)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
            .formStyle(.grouped)
        }
    }

    private var statusBadge: some View {
        let style = AgentHistoryOutcomeStyle(outcome: details.outcome)
        return Label(details.outcomeText, systemImage: style.symbol)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(style.color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(style.color.opacity(0.15), in: Capsule())
    }

    // Kotlin enums bridge to Swift as classes, so compare `kind` with `==`.
    @ViewBuilder
    private func row(_ row: AgentHistoryDetailsRowSnapshot) -> some View {
        if row.kind == AgentHistoryDetailsRowKind.reference, let reference = row.reference {
            LabeledContent(row.label) {
                DetailReferenceChip(reference: reference, invoke: openItem)
            }
        } else if row.kind == AgentHistoryDetailsRowKind.code {
            DetailAccessoryRow {
                Text(row.value)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } accessories: {
                AgentHistoryCopyButton(text: row.value)
            }
        } else {
            DetailAccessoryRow {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    if !row.icon.isEmpty {
                        Image(systemName: agentHistorySymbol(row.icon))
                            .foregroundStyle(.secondary)
                            .frame(width: 20)
                            .accessibilityHidden(true)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.label)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(row.value)
                            .font(row.monospace ? .body.monospaced() : .body)
                            .textSelection(.enabled)
                    }
                }
                .accessibilityElement(children: .combine)
            } accessories: {
                AgentHistoryCopyButton(text: row.value)
            }
        }
    }

    /// Closes the sheet and opens the item behind the `keyguard://` deep link.
    private func openItem(_ deepLink: String) {
        dismiss()
        navigationModel.openDeepLink(deepLink)
    }
}

/// Copies a value and shows a check mark for a moment.
private struct AgentHistoryCopyButton: View {
    let text: String

    @State private var copied = false
    @State private var copyGeneration = 0

    var body: some View {
        DetailIconButton(
            title: copied ? L10n.copiedValue : L10n.copy,
            systemImage: copied ? "checkmark" : "doc.on.doc"
        ) {
            copy()
            copied = true
            copyGeneration += 1
        }
        .task(id: copyGeneration) {
            guard copied else { return }
            do {
                try await Task.sleep(for: .milliseconds(1500))
                copied = false
            } catch {}
        }
    }

    private func copy() {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #else
        UIPasteboard.general.string = text
        #endif
    }
}

/// The color and symbol of an `AgentHistoryOutcome` name.
struct AgentHistoryOutcomeStyle {
    let color: Color
    let symbol: String

    init(outcome: String?) {
        switch outcome {
        case "SUCCESS":
            color = .green
            symbol = "checkmark.circle.fill"
        case "FAILURE":
            color = .red
            symbol = "xmark.octagon.fill"
        default:
            color = .secondary
            symbol = "minus.circle"
        }
    }
}

/// Maps an `AgentHistoryDetailsIcon` name to an SF Symbol.
func agentHistorySymbol(_ icon: String) -> String {
    switch icon {
    case "LIST_KEYS": "key.horizontal"
    case "SSH_SIGN", "GPG_SIGN": "signature"
    case "GPG_DECRYPT": "lock.open"
    case "RUN", "EXECUTABLE": "terminal"
    case "MCP": "sparkles"
    case "ENV_FILE": "doc.text"
    case "APPLICATION": "app"
    case "APPLICATION_PATH": "shippingbox"
    case "PROCESS": "cpu"
    case "PROCESS_ID": "number"
    case "FINGERPRINT": "touchid"
    case "KEYGRIP": "key"
    case "CLIENT": "point.3.connected.trianglepath.dotted"
    case "ERROR": "exclamationmark.triangle"
    default: "questionmark.circle"
    }
}
