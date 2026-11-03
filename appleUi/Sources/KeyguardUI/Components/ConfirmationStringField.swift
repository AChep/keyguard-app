import SwiftUI
import KeyguardShared

/// One stable form row, including validation, so snapshot updates do not replace
/// the focused native field when an error appears or disappears.
struct ConfirmationStringField: View {
    let item: ConfirmationItemSnapshot
    let onChange: (String) -> Void

    @State private var revealed = false
    @State private var ephemeralText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent {
                HStack {
                    textField
                        .labelsHidden()
                        .accessibilityLabel(item.title.isEmpty ? (item.hint ?? "") : item.title)
                        .font(item.monospace ? .system(.body, design: .monospaced) : nil)
                        .disabled(!item.enabled)
                    // Passwords and tokens start masked, as in Compose.
                    if item.sensitive {
                        DetailIconButton(
                            title: revealed ? L10n.hide : L10n.fileActionRevealTitle,
                            systemImage: revealed ? "eye.slash" : "eye"
                        ) {
                            revealed.toggle()
                        }
                    }
                }
            } label: {
                if !item.title.isEmpty {
                    Text(item.title)
                }
            }
            if let description = item.descriptionText, !description.isEmpty {
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let error = item.error, !error.isEmpty {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .onDisappear { ephemeralText = "" }
    }

    private var textField: BridgedTextField {
        var field = BridgedTextField(
            label: item.title.isEmpty ? (item.hint ?? "") : item.title,
            prompt: item.hint,
            text: item.stringValue,
            textRevision: Int32(item.stringRevision),
            secure: item.sensitive && !revealed,
            send: onChange,
            style: .automatic,
            disablesAutocorrection: item.sensitive
        )
        if item.ephemeral {
            field.draft = Binding(
                get: { ephemeralText },
                set: {
                    ephemeralText = $0; onChange($0)
                }
            )
        }
        #if os(iOS)
        // A revealed token must keep its exact case.
        var configuredField = field
        if item.sensitive {
            configuredField.autocapitalization = .never
        }
        return configuredField
        #else
        return field
        #endif
    }
}
