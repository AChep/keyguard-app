import SwiftUI
import KeyguardShared

/// Uses the item editor's sheet and shared draft; no nested presentation owns key material.
struct AddGpgUserIdsView: View {
    let snapshot: AddGpgUserIdsSnapshot
    let model: AddFormModel

    var body: some View {
        Form {
            if snapshot.page == .list {
                listSections
            } else if snapshot.page == .edit {
                editSections
            } else {
                Section {
                    ForEach(snapshot.identities, id: \.id) { identity in
                        identityRow(identity)
                    }
                } footer: {
                    footer
                }
            }
        }
        .formStyle(.grouped)
        .onChange(of: snapshot.error) { _, error in
            if let error {
                AccessibilityNotification.Announcement(error).post()
            }
        }
    }

    @ViewBuilder
    private var listSections: some View {
        Section {
            ForEach(snapshot.identities, id: \.id) { identity in
                identityRow(identity)
            }
            if snapshot.identities.isEmpty && !snapshot.loading {
                Text(L10n.gpgUserIdsEmpty).foregroundStyle(.secondary)
            }
        } footer: {
            footer
        }
        if let action = snapshot.add {
            Section {
                Button(action.title, systemImage: "plus") {
                    model.invokeAddAction(id: action.id)
                }
                .disabled(!action.enabled)
            }
        }
        if !snapshot.history.isEmpty {
            Section {
                DisclosureGroup(L10n.gpgUserIdsHistory) {
                    ForEach(snapshot.history, id: \.id) { identity in
                        identityRow(identity)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var editSections: some View {
        // The identity being replaced; adding a new one has none.
        if let identity = snapshot.identities.first {
            Section {
                identityRow(identity)
            }
        }
        // Validation lives in the footer, not in a sibling row, so the
        // focused native field keeps its first responder while typing.
        Section {
            formatPicker
            ForEach(snapshot.fields, id: \.field.id) { field in
                LabeledContent(field.label) {
                    textField(field)
                        .labelsHidden()
                        .accessibilityLabel(field.label)
                        .disabled(!field.field.editable)
                }
            }
        } footer: {
            footer
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if snapshot.busy || snapshot.loading {
                ProgressView().controlSize(.small)
            }
            Text(snapshot.message)
            if let error = snapshot.error {
                // Pair the message with a glyph so the error is not
                // conveyed by color alone.
                Label {
                    Text(error)
                } icon: {
                    Image(systemName: "exclamationmark.circle")
                }
                .foregroundStyle(Color(platform: .platformDanger))
            }
        }
    }

    private var formatPicker: some View {
        Picker(
            L10n.input,
            selection: Binding(
                get: { snapshot.formats.first(where: \.selected)?.key ?? "" },
                set: { key in
                    if let format = snapshot.formats.first(where: { $0.key == key }), format.action.enabled {
                        model.invokeAddAction(id: format.action.id)
                    }
                }
            )
        ) {
            ForEach(snapshot.formats, id: \.key) { format in
                Text(format.action.title)
                    .tag(format.key)
                    .selectionDisabled(!format.action.enabled)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .disabled(snapshot.busy)
    }

    /// A row with actions opens them in a menu when tapped.
    @ViewBuilder
    private func identityRow(_ identity: AddGpgIdentitySnapshot) -> some View {
        if identity.actions.isEmpty {
            identityLabel(identity)
                .textSelection(.enabled)
        } else {
            Menu {
                ForEach(identity.actions, id: \.id) { action in
                    actionButton(action)
                }
            } label: {
                identityLabel(identity)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
        }
    }

    private func identityLabel(_ identity: AddGpgIdentitySnapshot) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(identity.value)
                .foregroundStyle(.primary)
            if let status = identity.status {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func actionButton(_ action: AddGpgUserIdActionSnapshot) -> some View {
        Button(action.title, role: action.destructive ? .destructive : nil) {
            model.invokeAddAction(id: action.id)
        }
        .disabled(!action.enabled)
    }

    private func textField(_ field: AddGpgUserIdFieldSnapshot) -> BridgedTextField {
        let view = BridgedTextField(
            label: field.label,
            text: field.field.text,
            textRevision: field.field.textRevision,
            secure: false,
            send: { model.setAddField(id: field.field.id, text: $0) },
            style: .automatic,
            disablesAutocorrection: true
        )
        #if os(iOS)
        var configured = view
        configured.keyboard = field.kind == .email ? .emailAddress : .default
        configured.autocapitalization = field.kind == .name ? .words : .never
        return configured
        #else
        return view
        #endif
    }
}
