import SwiftUI
import UniformTypeIdentifiers
import KeyguardShared
#if os(macOS)
import AppKit
#endif

struct EnvironmentsView: View {
    @Bindable var model: EnvironmentsModel
    @State private var query = ""
    @State private var fileError = false
    #if os(iOS)
    /// The draft that the picked file is imported into, while the importer is shown.
    @State private var importDraft: EnvironmentDraftHandle?
    #endif

    var body: some View {
        Form {
            if let snapshot = model.snapshot {
                if !snapshot.unlocked {
                    Text(L10n.environmentsLocked)
                } else if let editor = snapshot.editor {
                    EnvironmentEditorView(
                        model: model, snapshot: snapshot, editor: editor, importFile: importFile
                    )
                } else {
                    Section {
                        TextField(L10n.environmentsSearch, text: $query)
                        Button(L10n.environmentsCreate, systemImage: "plus") { model.session.create() }
                            .disabled(!snapshot.canCreate || snapshot.busy)
                        ForEach(
                            snapshot.environments.filter {
                                query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
                            }, id: \.id
                        ) { item in
                            Button(item.name, systemImage: "terminal") { model.session.open(id: item.id) }
                                .disabled(snapshot.busy)
                        }
                        if snapshot.environments.isEmpty { Text(L10n.environmentsEmpty).foregroundStyle(.secondary) }
                    }
                    #if os(macOS)
                    // iOS has no command-line agent to serve MCP.
                    Section {
                        NavigationLink(L10n.protocolMcp) { EnvironmentsMcpScreen() }
                    }
                    #endif
                }
                if snapshot.failed || fileError {
                    Text(L10n.errorFailedUnknown).foregroundStyle(.red)
                }
            } else {
                ProgressView()
            }
        }
        .formStyle(.grouped)
        #if os(iOS)
        // iOS has no NSOpenPanel. Only the callbacks may clear the
        // draft: SwiftUI resets the binding before either of them arrives.
        .fileImporter(
            isPresented: Binding(get: { importDraft != nil }, set: { _ in }),
            allowedContentTypes: [.item],
            allowsMultipleSelection: false,
            onCompletion: { result in
                guard let draft = importDraft else { return }
                importDraft = nil
                switch result {
                case let .success(urls):
                    if let url = urls.first { importFile(url: url, into: draft) }
                case .failure:
                    fileError = true
                }
            },
            onCancellation: { importDraft = nil }
        )
        #endif
    }

    private func importFile() {
        // The draft's actions do nothing once the draft is gone.
        guard let draft = model.session.currentDraft() else { return }
        #if os(macOS)
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        panel.begin { result in
            guard result == .OK, let url = panel.url else { return }
            importFile(url: url, into: draft)
        }
        #else
        importDraft = draft
        #endif
    }

    private func importFile(url: URL, into draft: EnvironmentDraftHandle) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile == true, (values.fileSize ?? Int.max) <= 65_536 else {
                fileError = true
                return
            }
            let content = try String(contentsOf: url, encoding: .utf8)
            draft.setImportText(value: content)
            draft.previewImport()
            fileError = false
        } catch { fileError = true }
    }
}
