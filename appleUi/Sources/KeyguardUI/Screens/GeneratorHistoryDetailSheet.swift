import SwiftUI
import KeyguardShared

struct GeneratorHistoryDetailSheet: View {
    let item: GeneratorHistoryItemSnapshot
    let invokeAction: (String) -> Void

    private var primaryCopy: VaultActionSnapshot? { item.actions.first { $0.isCopy } }

    private var typeTitle: String? {
        switch item.type {
        case "PASSWORD": L10n.password
        case "USERNAME": L10n.username
        case "EMAIL": L10n.email
        case "EMAIL_RELAY": L10n.emailrelayIntegrationTitle
        case "SSH_KEY": L10n.cipherTypeSshKey
        case "GPG_KEY": L10n.cipherTypeGpgKey
        default: nil
        }
    }

    var body: some View {
        ModalSheet(title: L10n.generatorhistoryHeaderTitle) {
            Form {
                Section(item.type == "SSH_KEY" || item.type == "GPG_KEY" ? L10n.fingerprint : L10n.fieldValue) {
                    DetailAccessoryRow {
                        PasswordText(item.title, colorize: item.colorize)
                            .font(.body.monospaced())
                            .textSelection(.enabled)
                    } accessories: {
                        if let primaryCopy {
                            DetailIconButton(title: primaryCopy.title, systemImage: "doc.on.doc") {
                                invokeAction(primaryCopy.id)
                            }
                        }
                    }
                    .compactControlRowInsets(4)
                }
                Section {
                    if let typeTitle {
                        LabeledContent(L10n.type, value: typeTitle)
                    }
                    if let date = item.date, !date.isEmpty {
                        LabeledContent(L10n.date, value: date)
                    }
                }
                Section(L10n.actions) {
                    ForEach(item.actions.filter { $0.id != primaryCopy?.id }, id: \.id) { action in
                        ListItemActionButton(action: action, invoke: invokeAction)
                    }
                }
            }
            .formStyle(.grouped)
        }
    }
}
