import SwiftUI
import KeyguardShared

/// A screen with the network settings of this device, as `KeyguardNetworkSnapshot`.
@MainActor
protocol KeyguardNetworkEditing: AnyObject {
    var network: KeyguardNetworkSnapshot { get }
    /// Types into the network field `id`.
    func setNetworkField(id: String, text: String)
    func setAllowLan(value: Bool)
    func setRelayStorage(value: Bool)
}

/// How this device reaches the other devices: the relay, the ICE servers, the local
/// network and relay storage. The rows of a form section.
struct KeyguardNetworkFields: View {
    let editor: any KeyguardNetworkEditing
    /// Disables the fields, for example while they are being saved.
    var fieldsDisabled = false

    private var network: KeyguardNetworkSnapshot { editor.network }

    var body: some View {
        Group {
            VStack(alignment: .leading, spacing: 16) {
                field(network.relayUrl, title: L10n.appLinkConnectionRelayUrlLabel, url: true)
                field(network.iceUrl, title: L10n.appLinkConnectionIceUrlLabel, url: true)
                field(network.accessToken, title: L10n.appLinkConnectionRelayTokenLabel, secure: true)
                Text(L10n.appLinkConnectionRelayUrlHint)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 8)
            Toggle(isOn: Binding(get: { network.allowLan }, set: { editor.setAllowLan(value: $0) })) {
                Text(L10n.appLinkConnectionAllowLanTitle)
                Text(L10n.appLinkConnectionAllowLanText)
            }
            Toggle(
                isOn: Binding(get: { network.relayStorage }, set: { editor.setRelayStorage(value: $0) })
            ) {
                Text(L10n.appLinkConnectionRelayStorageTitle)
                Text(L10n.appLinkConnectionRelayStorageText)
            }
        }
        .disabled(fieldsDisabled)
    }

    private func field(_ field: TextFieldSnapshot, title: String, secure: Bool = false, url: Bool = false)
        -> KeyguardFormField
    {
        KeyguardFormField(
            field: field, title: title, secure: secure, url: url,
            send: { [editor] in editor.setNetworkField(id: field.id, text: $0) })
    }
}

/// A titled field of the Keyguard forms, with its error.
struct KeyguardFormField: View {
    let field: TextFieldSnapshot
    let title: String
    var secure = false
    var url = false
    /// Keeps the input exactly as typed: no autocorrection or autocapitalization.
    var verbatim = false
    /// Takes digits only, with the number pad where there is one.
    var numeric = false
    var submitLabel: SubmitLabel = .return
    let send: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
            LoginFieldRow(field: field) {
                textField
            }
        }
    }

    private var textField: BridgedTextField {
        // Endpoint URLs, tokens and digits must preserve exact input.
        let exact = verbatim || url || secure || numeric
        let input = BridgedTextField(
            label: title,
            prompt: field.placeholder,
            text: field.text,
            textRevision: field.textRevision,
            secure: secure,
            send: send,
            style: .roundedBorder,
            submitLabel: submitLabel,
            disablesAutocorrection: exact
        )
        #if os(iOS)
        var configuredInput = input
        if exact {
            configuredInput.autocapitalization = .never
        }
        if url {
            configuredInput.keyboard = .URL
            configuredInput.contentType = .URL
        }
        if numeric {
            configuredInput.keyboard = .numberPad
        }
        return configuredInput
        #else
        return input
        #endif
    }
}
