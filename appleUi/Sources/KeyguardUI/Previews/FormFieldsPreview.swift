#if DEBUG
import SwiftUI
import KeyguardShared

/// Synthetic values and production field renderers; never opens a vault.
struct FormFieldsPreview: View {
    @State private var name = "Example"
    @State private var regex = "^https?://.*"
    @State private var command = "https://{url:rmvscm}"
    @State private var username = "demo@example.com"
    @State private var password = "Synthetic password"
    @State private var notes = "First line\nSecond line"
    @State private var referenceName = "Example"
    @State private var referenceSecret = "Synthetic token"
    @State private var showError = false
    @State private var enabled = true
    @State private var dark = false
    @State private var largeText = false

    init(dark: Bool = false, largeText: Bool = false, showError: Bool = false) {
        _dark = State(initialValue: dark)
        _largeText = State(initialValue: largeText)
        _showError = State(initialValue: showError)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Preview settings") {
                    Toggle("Dark appearance", isOn: $dark)
                    Toggle("Accessibility text size", isOn: $largeText)
                    Toggle("Validation error", isOn: $showError)
                }
                Section("URL override") {
                    confirmation("name", title: L10n.genericName, value: $name)
                    confirmation("regex", title: L10n.regex, value: $regex, monospace: true)
                    confirmation("command", title: L10n.command, value: $command, monospace: true)
                    Toggle(L10n.enabled, isOn: $enabled)
                }
                Section("Edit item") {
                    editor("username", title: L10n.username, value: $username, hint: .username)
                    editor("password", title: L10n.password, value: $password, hint: .password, hidden: true)
                    editor("notes", title: L10n.notes, value: $notes, multiline: true)
                }
                Section("Default fields / email forwarder reference") {
                    TextField(L10n.genericName, text: $referenceName)
                    SecureField("API key", text: $referenceSecret)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Native form fields")
        }
        .preferredColorScheme(dark ? .dark : .light)
        .dynamicTypeSize(largeText ? .accessibility3 : .large)
    }

    private func confirmation(
        _ id: String, title: String, value: Binding<String>, monospace: Bool = false
    ) -> some View {
        ConfirmationStringField(
            item: ConfirmationItemSnapshot(
                removable: false, key: id, kind: .string, enabled: true, title: title,
                text: nil, booleanValue: false, stringValue: value.wrappedValue, stringRevision: 0,
                descriptionText: nil, hint: nil,
                error: showError ? "Synthetic validation message that can appear while editing." : nil,
                sensitive: false, monospace: monospace, password: false,
                enumValue: "", options: [], docText: nil, docHasLink: false,
                fileName: nil, hasFile: false
            ),
            onChange: { value.wrappedValue = $0 }
        )
    }

    private func editor(
        _ id: String, title: String, value: Binding<String>, hint: AddFieldInputHint = .plain,
        hidden: Bool = false, multiline: Bool = false
    ) -> some View {
        AddFieldEditor(
            field: AddTextFieldSnapshot(
                field: TextFieldSnapshot(
                    id: id, text: value.wrappedValue, textRevision: 0, placeholder: nil,
                    error: showError ? "Synthetic validation message." : nil,
                    vlType: nil, vlText: nil, editable: true
                ),
                label: title, hidden: hidden, multiline: multiline, autofill: nil
            ),
            fallbackLabel: nil, hint: hint,
            onChange: { value.wrappedValue = $0 },
            onAutofill: { value.wrappedValue = $0 }
        )
    }
}

#Preview {
    FormFieldsPreview()
}
#endif
