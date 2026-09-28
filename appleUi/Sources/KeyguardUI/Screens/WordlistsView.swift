import SwiftUI
#if canImport(AppKit)
import AppKit
#endif
import UniformTypeIdentifiers
import KeyguardShared

/// Native SwiftUI rendering of the shared Wordlists state.
struct WordlistsView: View {
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(WordlistsModel.self) private var wordlistsModel

    let entry: ScreenEntrySnapshot

    @State private var sheet: WordlistSheet?
    @State private var pendingDelete: WordlistActionRequestSnapshot?
    @State private var importingFile = false
    @State private var importError: String?

    /// The list's multi-selection, kept in lockstep with the shared producer's
    /// selection handle.
    @State private var selection = ListSelectionModel()

    private var snapshot: WordlistListSnapshot {
        navigationModel.navStacks.values.joined().first { $0.instanceId == entry.instanceId }?.wordlistList
            ?? WordlistListSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if snapshot.status == WordlistLoadStatus.failed {
                ContentUnavailableView {
                    Label(L10n.errorFailedUnknown, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button(L10n.retry) { navigationModel.retryEntryList(instanceId: entry.instanceId) }
                }
            } else {
                SnapshotContent(loaded: snapshot.status == WordlistLoadStatus.ready, isEmpty: snapshot.items.isEmpty) {
                    empty
                } content: {
                    list
                }
            }
        }
        .navigationTitle(L10n.wordlistListSectionTitle)
        .toolbar { toolbar }
        .listSelection(
            selection,
            selectionCount: snapshot.selectionCount,
            producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
            isKnownId: { id in snapshot.items.contains { $0.id == id } },
            toggle: { navigationModel.toggleEntryListSelection(instanceId: entry.instanceId, itemId: $0) },
            clear: { navigationModel.clearEntryListSelection(instanceId: entry.instanceId) }
        )
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case let .fromFile(url):
                WordlistFromFileView(fileURL: url)
            case .fromUrl:
                WordlistFromUrlView()
            case let .rename(id, currentName):
                WordlistRenameView(wordlistId: id, currentName: currentName)

            }
        }
        .alert(
            pendingDelete?.items.count == 1
                ? L10n.wordlistDeleteOneConfirmationTitle : L10n.wordlistDeleteManyConfirmationTitle,
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { item in
            Button(L10n.delete, role: .destructive) {
                wordlistsModel.deleteWordlists(ids: item.items.map(\.id))
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: { item in
            Text(item.items.map(\.name).joined(separator: "\n"))
        }
        #if os(iOS)
        // iOS has no NSOpenPanel; present the system document picker. The picked
        // URL is security-scoped — `WordlistFromFileView.save` holds access for the
        // import (matching the macOS path).
        .fileImporter(
            isPresented: $importingFile,
            allowedContentTypes: [.plainText, .text, .data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case let .success(urls):
                if let url = urls.first {
                    sheet = .fromFile(url: url)
                }
            case let .failure(error):
                if (error as? CocoaError)?.code != .userCancelled {
                    importError = error.localizedDescription
                }
            }
        }
        .alert(
            L10n.prefItemAutomaticBackupsPanelErrorLabel,
            isPresented: Binding(
                get: { importError != nil },
                set: { if !$0 { importError = nil } }
            )
        ) {
        } message: {
            Text(importError ?? "")
        }
        #endif
    }

    private var empty: some View {
        ContentUnavailableView {
            Label(L10n.wordlistEmptyLabel, systemImage: "text.book.closed")
        } description: {
            Text(L10n.wordlistListImportHint)
        } actions: {
            Button {
                #if os(macOS)
                pickFile()
                #else
                importingFile = true
                #endif
            } label: {
                Label(L10n.wordlistAddWordlistViaFileTitle, systemImage: "doc")
            }
            Button {
                sheet = .fromUrl
            } label: {
                Label(L10n.wordlistAddWordlistViaUrlTitle, systemImage: "link")
            }
        }
    }

    private var list: some View {
        List(selection: $selection.selectedRowIds) {
            ForEach(snapshot.items, id: \.id) { item in
                rowEntry(item)
                    .tag(item.id)
                    .contextMenu { rowContextMenu(item) }
            }
        }
        .selectionBar(
            count: snapshot.selectionCount,
            actions: snapshot.selectionActions,
            invoke: { requestAction($0) },
            clear: { navigationModel.clearEntryListSelection(instanceId: entry.instanceId) }
        )
    }

    @ViewBuilder
    private func rowEntry(_ item: WordlistListItemSnapshot) -> some View {
        if editing {
            row(item)
        } else {
            Button {
                navigationModel.openEntryListItem(instanceId: entry.instanceId, itemId: item.id)
            } label: {
                HStack {
                    row(item)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var editing: Bool {
        selection.isEditing(selectionCount: snapshot.selectionCount)
    }

    private func row(_ item: WordlistListItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "text.book.closed")
                .foregroundStyle(.tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                if !item.counter.isEmpty {
                    Text(item.counter)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    /// A row's per-item actions (rename / delete), or the bulk selection actions when
    /// a multi-selection is active.
    @ViewBuilder
    private func rowContextMenu(_ item: WordlistListItemSnapshot) -> some View {
        if showsBulkContextMenu {
            selectionContextMenuItems(actions: snapshot.selectionActions) {
                requestAction($0)
            }
        } else {
            #if os(macOS)
            // macOS has no Edit button; a context-menu "Select" begins a
            // multi-selection (mirroring FoldersListView). The first toggle flips the
            // list into selection mode so subsequent clicks select instead of navigate.
            Button {
                navigationModel.toggleEntryListSelection(instanceId: entry.instanceId, itemId: item.id)
            } label: {
                Label(L10n.select, systemImage: "checkmark.circle")
            }
            Divider()
            #endif
            Button(L10n.rename) {
                requestAction("wordlist.selection.edit", itemId: item.id)
            }
            Divider()
            Button(L10n.delete, role: .destructive) {
                requestAction("wordlist.selection.delete", itemId: item.id)
            }
        }
    }

    private func requestAction(_ actionId: String, itemId: String? = nil) {
        navigationModel.requestEntryWordlistAction(instanceId: entry.instanceId, actionId: actionId, itemId: itemId) {
            request in
            guard let request else { return }
            if request.actionId == "wordlist.selection.edit", let item = request.items.first {
                sheet = .rename(id: item.id, currentName: item.name)
            } else if request.actionId == "wordlist.selection.delete" {
                pendingDelete = request
            }
        }
    }

    private var showsBulkContextMenu: Bool {
        selection.showsBulkActions(selectionCount: snapshot.selectionCount)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if snapshot.selectionCount > 0 && snapshot.canSelectAll {
            ToolbarItem {
                Button(L10n.selectionSelectAllAction) {
                    navigationModel.selectAllEntryListItems(instanceId: entry.instanceId)
                }
            }
        }
        #if os(iOS)
        ToolbarItem(placement: .topBarLeading) {
            if !snapshot.items.isEmpty {
                EditButton()
            }
        }
        #endif
        ToolbarItem {
            Menu {
                Button {
                    #if os(macOS)
                    pickFile()
                    #else
                    importingFile = true
                    #endif
                } label: {
                    Label(L10n.wordlistAddWordlistViaFileTitle, systemImage: "doc")
                }
                Button {
                    sheet = .fromUrl
                } label: {
                    Label(L10n.wordlistAddWordlistViaUrlTitle, systemImage: "link")
                }
            } label: {
                Label(L10n.add, systemImage: "plus")
            }
        }
    }

    /// Presents a file picker for the wordlist source, then opens the name prompt.
    private func pickFile() {
        #if os(macOS)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = [.plainText, .text, .data]
        panel.allowsOtherFileTypes = false
        panel.treatsFilePackagesAsDirectories = false
        if panel.runModal() == .OK, let url = panel.url {
            sheet = .fromFile(url: url)
        }
        #else
        // iOS: present `.fileImporter` at the view layer instead (follow-up).
        #endif
    }
}

/// The sheet shown over the wordlists list: importing from a picked file,
/// importing from a URL, or renaming an existing wordlist.
private enum WordlistSheet: Identifiable {
    case fromFile(url: URL)
    case fromUrl
    case rename(id: Int64, currentName: String)

    var id: String {
        switch self {
        case let .fromFile(url): return "file:\(url.absoluteString)"
        case .fromUrl: return "url"
        case let .rename(id, _): return "rename:\(id)"
        }
    }
}

struct WordlistFromFileView: View {
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    let fileURL: URL

    @State private var name: String = ""
    @State private var isSaving = false
    @State private var errorText: String?

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !isSaving
    }

    var body: some View {
        ModalSheet(
            title: L10n.wordlistAddWordlistViaFileTitle,
            width: 460,
            height: 320,
            detents: [.medium, .large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.genericName, text: $name)
                    LabeledContent(L10n.file, value: fileURL.lastPathComponent)
                } footer: {
                    if let errorText {
                        Text(errorText).foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(L10n.add) { save() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave)
        }
        .onAppear { name = fileURL.deletingPathExtension().lastPathComponent }
    }

    private func save() {
        isSaving = true
        errorText = nil
        let url = fileURL
        Task {
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
            do {
                try await wordlistsModel.addWordlistFromFile(name: name, uri: url.absoluteString)
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}

/// Downloads and imports a wordlist from a URL through the shared `AddWordlist`
/// use case.
struct WordlistFromUrlView: View {
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var urlText: String = ""
    @State private var isSaving = false
    @State private var errorText: String?

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !urlText.trimmingCharacters(in: .whitespaces).isEmpty
            && !isSaving
    }

    var body: some View {
        ModalSheet(
            title: L10n.wordlistAddWordlistViaUrlTitle,
            width: 460,
            height: 340,
            detents: [.medium, .large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.genericName, text: $name)
                    TextField(L10n.url, text: $urlText, prompt: Text("https://"))
                        .autocorrectionDisabled()
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                        #endif
                } footer: {
                    if let errorText {
                        Text(errorText).foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(L10n.add) { save() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave)
        }
    }

    private func save() {
        isSaving = true
        errorText = nil
        Task {
            do {
                try await wordlistsModel.addWordlistFromUrl(
                    name: name,
                    url: urlText.trimmingCharacters(in: .whitespaces)
                )
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}

/// Renames an existing wordlist through the shared `EditWordlist` use case.
struct WordlistRenameView: View {
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    let wordlistId: Int64
    let currentName: String

    @State private var name: String = ""
    @State private var isSaving = false
    @State private var errorText: String?

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !isSaving
    }

    var body: some View {
        ModalSheet(
            title: L10n.wordlistRenameAction,
            width: 460,
            height: 240,
            detents: [.medium],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.genericName, text: $name)
                } footer: {
                    if let errorText {
                        Text(errorText).foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(L10n.save) { save() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave)
        }
        .onAppear { name = currentName }
    }

    private func save() {
        isSaving = true
        errorText = nil
        Task {
            do {
                try await wordlistsModel.renameWordlist(id: wordlistId, name: name)
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}
