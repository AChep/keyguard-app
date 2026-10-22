import SwiftUI
import KeyguardShared
#if os(macOS)
import AppKit
#endif

struct EnvironmentEditorView: View {
    let model: EnvironmentsModel
    let snapshot: EnvironmentsSnapshot
    let editor: EnvironmentEditorSnapshot
    let importFile: () -> Void
    @State private var showTrashConfirmation = false

    var body: some View {
        Section {
            // Borderless, so that iOS fires only the tapped button of the row.
            HStack {
                Button(L10n.close) { model.session.clear() }
                Spacer()
                Button(L10n.save) { model.session.save() }.disabled(!editor.canSave || snapshot.busy)
            }
            .buttonStyle(.borderless)
            OwnershipRow(ownership: editor.ownership) {
                model.session.pickOwnership()
            }
            EnvironmentTextField(title: L10n.environmentsName, value: editor.name, onChange: model.session.setName)
                .disabled(!editor.editable || snapshot.busy)
            EnvironmentTextField(
                title: L10n.description, value: editor.description_,
                onChange: model.session.setDescription
            )
            .disabled(!editor.editable || snapshot.busy)
            if !editor.editable {
                Text(editor.unsupported ? L10n.environmentsErrorUnsupportedFormat : L10n.environmentsReadOnly)
                    .foregroundStyle(.secondary)
            }
        }
        Section(L10n.environmentsVariables) {
            if editor.exceedsLimits {
                Text(L10n.environmentsErrorLimitsExceeded).foregroundStyle(.red)
            }
            ForEach(Array(editor.variables.enumerated()), id: \.offset) { index, variable in
                EnvironmentVariableRow(model: model, index: Int32(index), variable: variable, editable: editor.editable)
                    .disabled(snapshot.busy)
            }
            Menu {
                Button(L10n.environmentsVariableTypeValue, systemImage: "textformat") {
                    model.session.addValueVariable()
                }
                Button(L10n.environmentsVariableTypeReference, systemImage: "link") {
                    model.session.addReferenceVariable()
                }
            } label: {
                Label(L10n.environmentsAddVariable, systemImage: "plus")
            }
            .disabled(!editor.editable || snapshot.busy)
        }
        Section(L10n.environmentsImport) {
            Text(L10n.environmentsImportNote).foregroundStyle(.secondary)
            Button(L10n.environmentsImportFile, action: importFile)
            EnvironmentTextField(
                title: L10n.environmentsImportText, value: editor.importText, concealed: true,
                onChange: model.session.setImportText)
            Button(L10n.environmentsImportPreview) { model.session.previewImport() }
            if let names = editor.importNames {
                Text(names.joined(separator: "\n")).font(.system(.body, design: .monospaced))
                Button(L10n.environmentsImportApply) { model.session.applyImport() }
            }
            if editor.importError { Text(L10n.environmentsImportErrorParse).foregroundStyle(.red) }
        }
        .disabled(snapshot.busy || !editor.editable)
        if editor.id != nil {
            Section(L10n.environmentsMounts) {
                Text(snapshot.supportsMounts ? L10n.environmentsMountNote : L10n.environmentsMountUnsupported)
                    .foregroundStyle(.secondary)
                ForEach(editor.mounts, id: \.id) { mount in
                    Text(mount.path).textSelection(.enabled)
                    Text(mountStatusText(mount.status))
                        .foregroundStyle(mount.status == .error ? Color.red : Color.secondary)
                    Button(L10n.environmentsMountDisable) { model.session.disableMount(id: mount.id) }
                }
                if snapshot.supportsMounts {
                    Button(L10n.environmentsMountCreate, action: chooseMountDirectory)
                        .disabled(editor.unsupported)
                }
            }
            .disabled(snapshot.busy)
            Section {
                Button(L10n.ciphersActionTrashTitle, role: .destructive) { showTrashConfirmation = true }
                    .disabled(snapshot.busy)
            }
            .confirmationDialog(L10n.ciphersActionTrashConfirmationTitle, isPresented: $showTrashConfirmation) {
                Button(L10n.ciphersActionTrashTitle, role: .destructive) { model.session.trash() }
            }
        }
    }

    private func mountStatusText(_ status: EnvironmentMountSnapshotStatus) -> String {
        switch status {
        case .ready: L10n.statusReady
        case .error: L10n.environmentsMountStatusError
        default: L10n.statusStarting
        }
    }

    private func chooseMountDirectory() {
        #if os(macOS)
        // The draft's actions do nothing once the draft is gone.
        guard let draft = model.session.currentDraft() else { return }
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.begin { result in
            guard result == .OK, let url = panel.url else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard let bookmark = url.securityScopedBookmarkToken() else { return }
            draft.mount(path: url.appendingPathComponent(".env").path, bookmark: bookmark)
        }
        #endif
    }
}
