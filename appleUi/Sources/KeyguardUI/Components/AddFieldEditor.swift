import SwiftUI
import KeyguardShared

/// Renders one `AddTextFieldSnapshot` as a labelled text / secure / multiline
/// field, forwarding edits by id. Hidden fields get a reveal toggle.
struct AddFieldEditor: View {
    let field: AddTextFieldSnapshot
    let fallbackLabel: String?
    /// iOS input hints derived from the owning row's kind.
    var hint: AddFieldInputHint = .plain
    let onChange: (String) -> Void
    let onAutofill: (String) -> Void

    @State private var reveal = false

    @State private var showAutofill = false

    /// Local typing buffer; the Kotlin field cell stays the source of
    /// truth through the `bridgedText` reconciliation below.
    @State private var buffer = ""

    private var label: String? { field.label ?? fallbackLabel }

    /// The in-form generator trigger next to the username / password fields: presents
    /// `AutofillGeneratorSheet`, which writes the chosen value back into the field.
    private var autofillButton: some View {
        DetailIconButton(title: L10n.generatorHeaderTitle, systemImage: "wand.and.stars") {
            showAutofill = true
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if field.multiline {
                // Notes need the full row width and leading text alignment;
                // a value column would squeeze them beside their label.
                if let label, !label.isEmpty {
                    Text(label)
                }
                input
                    .multilineTextAlignment(.leading)
            } else {
                LabeledContent {
                    input
                        .labelsHidden()
                } label: {
                    if let label, !label.isEmpty {
                        Text(label)
                    }
                }
            }
            if let error = field.error, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .bridgedText(
            $buffer,
            remote: field.value,
            remoteRevision: field.textRevision,
            send: onChange
        )
        // Shared field flows first emit a non-editable empty placeholder. The
        // populated initial value can arrive at the same text revision, so it
        // is not a typing echo and must initialize the local buffer separately.
        .onChange(of: field.field.editable) { _, editable in
            if editable {
                buffer = field.value
            }
        }
        .disabled(!field.field.editable)
        .id(field.id)
        .sheet(isPresented: $showAutofill) {
            if let af = field.autofill {
                AutofillGeneratorSheet(
                    autofill: af,
                    onPick: onAutofill
                )
            }
        }
    }

    @ViewBuilder
    private var input: some View {
        let binding = $buffer
        if field.multiline {
            #if os(macOS)
            // NSTextField submits on Return even with a vertical axis.
            // A text editor keeps Return inside notes instead of invoking Save.
            TextEditor(text: binding)
                .font(.body)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 72, maxHeight: 180)
                .accessibilityLabel(label ?? field.placeholder ?? "")
            #else
            TextField(label ?? "", text: binding, prompt: field.placeholder.map { Text($0) }, axis: .vertical)
                .lineLimit(3...)
                .textFieldStyle(.automatic)
                .inputHints(hint)
                .accessibilityLabel(label ?? field.placeholder ?? "")
            #endif
        } else if field.hidden {
            HStack {
                if reveal {
                    TextField(label ?? "", text: binding, prompt: field.placeholder.map { Text($0) })
                        .textFieldStyle(.automatic)
                        .inputHints(hint)
                        .accessibilityLabel(label ?? field.placeholder ?? "")
                } else {
                    SecureField(label ?? "", text: binding, prompt: field.placeholder.map { Text($0) })
                        .textFieldStyle(.automatic)
                        .inputHints(hint)
                        .accessibilityLabel(label ?? field.placeholder ?? "")
                }
                DetailIconButton(
                    title: reveal ? L10n.hide : L10n.fileActionRevealTitle,
                    systemImage: reveal ? "eye.slash" : "eye"
                ) {
                    reveal.toggle()
                }
                if field.autofill != nil {
                    autofillButton
                }
            }
        } else if field.autofill != nil {
            HStack {
                TextField(label ?? "", text: binding, prompt: field.placeholder.map { Text($0) })
                    .textFieldStyle(.automatic)
                    .inputHints(hint)
                    .accessibilityLabel(label ?? field.placeholder ?? "")
                autofillButton
            }
        } else {
            TextField(label ?? "", text: binding, prompt: field.placeholder.map { Text($0) })
                .textFieldStyle(.automatic)
                .inputHints(hint)
                .accessibilityLabel(label ?? field.placeholder ?? "")
        }
    }

}

private extension View {
    @ViewBuilder
    func inputHints(_ hint: AddFieldInputHint) -> some View {
        #if os(iOS)
        switch hint {
        case .plain:
            self
        case .username:
            self
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        case .password:
            self
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        case .url:
            self
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        case .totp:
            // Base32 secret: no content type (it is not an AutoFill field), no
            // capitalization, no autocorrect — any of which would mangle the key.
            self
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        #else
        switch hint {
        case .plain:
            self
        case .username, .password, .url, .totp:
            self.autocorrectionDisabled()
        }
        #endif
    }
}
