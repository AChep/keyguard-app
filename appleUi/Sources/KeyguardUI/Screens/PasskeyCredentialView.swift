import SwiftUI
import KeyguardShared

struct PasskeyCredentialView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.passkeyCredential?.title ?? L10n.passkey,
            width: 460,
            height: 480,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.passkeyCredential {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            if dialogsModel.passkeyCredential?.canUse == true {
                Button(L10n.passkeyUseShort) { dialogsModel.usePasskeyCredential() }
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    @ViewBuilder
    private func content(_ snapshot: PasskeyCredentialSnapshot) -> some View {
        if let error = snapshot.error {
            ContentUnavailableView {
                Label(L10n.passkeyCredentialLoadFailedTitle, systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            }
        } else {
            fields(snapshot)
        }
    }

    private func fields(_ snapshot: PasskeyCredentialSnapshot) -> some View {
        Form {
            Section {
                LabeledContent(L10n.passkeyUserDisplayName) {
                    valueText(snapshot.userDisplayName)
                }
                LabeledContent(L10n.passkeyUserUsername) {
                    valueText(snapshot.userName)
                }
            }
            Section {
                LabeledContent(L10n.passkeyRelyingParty) {
                    VStack(alignment: .trailing, spacing: 2) {
                        if let rpName = snapshot.rpName, !rpName.isEmpty {
                            Text(rpName)
                                .textSelection(.enabled)
                        }
                        valueText(snapshot.rpId, monospace: true)
                    }
                }
                LabeledContent(L10n.passkeySignatureCounter) {
                    valueText(snapshot.signatureCounter)
                }
                LabeledContent(L10n.passkeyDiscoverable) {
                    Text(snapshot.discoverable ? L10n.yes : L10n.no)
                }
                LabeledContent(L10n.passkeyCredentialIdLabel) {
                    valueText(snapshot.credentialId, monospace: true)
                }
            } header: {
                Text(L10n.info)
            } footer: {
                if let createdAt = snapshot.createdAt, !createdAt.isEmpty {
                    Text(createdAt)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder
    private func valueText(_ value: String?, monospace: Bool = false) -> some View {
        if let value, !value.isEmpty {
            Text(value)
                .font(monospace ? .body.monospaced() : .body)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        } else {
            Text("—")
                .foregroundStyle(.tertiary)
        }
    }
}
