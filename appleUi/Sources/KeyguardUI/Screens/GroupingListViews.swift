import SwiftUI
import KeyguardShared

// Native SwiftUI rendering of Organizations, Collections, and Folders.

// MARK: - Shared count badge

/// A trailing item-count, styled like the secondary counts in Settings lists.
private struct CountBadge: View {
    let count: Int

    var body: some View {
        if count > 0 {
            Text("\(count)")
                .font(.callout)
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
    }
}

private struct DisclosureChevron: View {
    var body: some View {
        Image(systemName: "chevron.forward")
            .font(.caption.weight(.semibold))
            .foregroundStyle(.tertiary)
    }
}

// MARK: - Organizations

/// Lists the organizations an account belongs to. Tapping a row drills into that
/// organization's collections; "View items" opens the filtered vault list.
struct OrganizationsListView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    private var snapshot: OrganizationsSnapshot {
        entry.organizations ?? OrganizationsSnapshot.companion.empty
    }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.organizationsEmptyLabel, systemImage: "building.2")
            } description: {
                Text(L10n.organizationsEmptyText)
            }
        } content: {
            list
        }
        .navigationTitle(L10n.organizations)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var list: some View {
        List {
            ForEach(snapshot.items, id: \.id) { item in
                Button {
                    navigationModel.pushCollectionsList(
                        accountId: snapshot.accountId,
                        organizationId: item.id,
                        title: item.title
                    )
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "building.2")
                            .foregroundStyle(.tint)
                            .frame(width: 24)
                        Text(item.title)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        CountBadge(count: Int(item.ciphers))
                        DisclosureChevron()
                    }
                    .contentShape(Rectangle())
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    viewItemsAction(item.itemsActionId)
                }
                .contextMenu {
                    viewItemsButton(item.itemsActionId)
                    infoButton(item.infoActionId)
                }
            }
        }
    }

    @ViewBuilder
    private func viewItemsAction(_ actionId: String?) -> some View {
        if let actionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            } label: {
                Label(L10n.items, systemImage: "list.bullet")
            }
            .tint(.accentColor)
        }
    }

    @ViewBuilder
    private func viewItemsButton(_ actionId: String?) -> some View {
        if let actionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            } label: {
                Label(L10n.groupingViewItemsAction, systemImage: "list.bullet")
            }
        }
    }

    @ViewBuilder
    private func infoButton(_ actionId: String?) -> some View {
        if let actionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            } label: {
                Label(L10n.info, systemImage: "info.circle")
            }
        }
    }
}

// MARK: - Collections

/// Lists an account's collections, grouped by organization. Tapping a row opens the
/// filtered vault list of that collection's items.
struct CollectionsListView: View {
    @Environment(NavigationModel.self) private var navigationModel
    let entry: ScreenEntrySnapshot

    private var snapshot: CollectionsSnapshot {
        entry.collections ?? CollectionsSnapshot.companion.empty
    }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.collectionsEmptyLabel, systemImage: "rectangle.stack")
            } description: {
                Text(L10n.collectionsEmptyText)
            }
        } content: {
            list
        }
        .navigationTitle(entry.title.isEmpty ? L10n.collections : entry.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var list: some View {
        List {
            ForEach(groups) { group in
                Section {
                    ForEach(group.items, id: \.id) { item in
                        row(item)
                    }
                } header: {
                    if let title = group.title, !title.isEmpty {
                        Text(title)
                    }
                }
            }
        }
    }

    private func row(_ item: CollectionListItemSnapshot) -> some View {
        Button {
            if let actionId = item.itemsActionId {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "rectangle.stack")
                    .foregroundStyle(.tint)
                    .frame(width: 24)
                Text(item.title)
                    .lineLimit(1)
                Spacer(minLength: 8)
                CountBadge(count: Int(item.ciphers))
                if item.itemsActionId != nil {
                    DisclosureChevron()
                }
            }
            .contentShape(Rectangle())
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
        .disabled(item.itemsActionId == nil)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            infoAction(item.infoActionId)
        }
        .contextMenu {
            infoButton(item.itemsActionId, info: item.infoActionId)
        }
    }

    @ViewBuilder
    private func infoAction(_ actionId: String?) -> some View {
        if let actionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            } label: {
                Label(L10n.info, systemImage: "info.circle")
            }
            .tint(.gray)
        }
    }

    @ViewBuilder
    private func infoButton(_ itemsActionId: String?, info infoActionId: String?) -> some View {
        if let itemsActionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: itemsActionId)
            } label: {
                Label(L10n.groupingViewItemsAction, systemImage: "list.bullet")
            }
        }
        if let infoActionId {
            Button {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: infoActionId)
            } label: {
                Label(L10n.info, systemImage: "info.circle")
            }
        }
    }

    /// Consecutive runs of the producer-ordered rows sharing an organization name.
    private var groups: [CollectionGroup] {
        var result: [CollectionGroup] = []
        for item in snapshot.items {
            if let last = result.last, last.title == item.organizationName {
                result[result.count - 1].items.append(item)
            } else {
                result.append(
                    CollectionGroup(
                        id: "\(result.count)",
                        title: item.organizationName,
                        items: [item]
                    )
                )
            }
        }
        return result
    }

    private struct CollectionGroup: Identifiable {
        let id: String
        let title: String?
        var items: [CollectionListItemSnapshot]
    }
}

// MARK: - Folders

struct FoldersListView: View {
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    let entry: ScreenEntrySnapshot

    @State private var addingFolder = false

    private var snapshot: FoldersSnapshot {
        entry.folders ?? FoldersSnapshot.companion.empty
    }

    private var canAdd: Bool { snapshot.accountId != nil }

    /// `true` while the shared producer reports an active multi-selection.
    private var selecting: Bool { snapshot.selectionCount >= 1 }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.foldersEmptyLabel, systemImage: "folder")
            } description: {
                Text(L10n.foldersEmptyText)
            }
        } content: {
            list
        }
        .navigationTitle(L10n.folders)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar { addToolbar }
        .selectionBar(
            count: snapshot.selectionCount,
            actions: snapshot.selectionActions,
            invoke: { invoke($0) },
            clear: { invoke("selection:clear") }
        )
        .sheet(isPresented: $addingFolder) {
            FolderNameSheet(title: L10n.folderNew, confirmLabel: L10n.add) { name in
                guard let accountId = snapshot.accountId else { return }
                try await vaultActionsModel.addFolder(accountId: accountId, name: name)
            }

        }

    }

    private var list: some View {
        List {
            ForEach(snapshot.items, id: \.id) { item in
                row(item)
            }
        }
    }

    @ViewBuilder
    private func row(_ item: FolderListItemSnapshot) -> some View {
        Button {
            if item.selecting {
                toggle(item)
            } else if let actionId = item.itemsActionId {
                navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
            }
        } label: {
            HStack(spacing: 12) {
                // While selecting, the leading glyph becomes a selection checkmark
                // (mirroring the producer's `selected`); otherwise the folder icon.
                if item.selecting {
                    Image(systemName: item.selected ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(item.selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        .frame(width: 24)
                } else {
                    Image(systemName: "folder")
                        .foregroundStyle(.tint)
                        .frame(width: 24)
                }
                Text(item.title)
                    .lineLimit(1)
                if item.failed {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                } else if !item.synced {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                CountBadge(count: Int(item.ciphers))
                if !item.selecting && item.itemsActionId != nil {
                    DisclosureChevron()
                }
            }
            .contentShape(Rectangle())
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(item.selected ? .isSelected : [])
        // A long-press begins a multi-selection (the producer's `onLongClick`); once
        // selecting, taps toggle membership through the button above.
        .onLongPressGesture {
            if !item.selecting { toggle(item) }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !item.selecting {
                if let actionId = item.deleteActionId {
                    Button(role: .destructive) {
                        invoke(actionId)
                    } label: {
                        Label(L10n.delete, systemImage: "trash")
                    }
                }
                if let actionId = item.renameActionId {
                    Button {
                        invoke(actionId)
                    } label: {
                        Label(L10n.rename, systemImage: "pencil")
                    }
                    .tint(.accentColor)
                }
            }
        }
        .contextMenu {
            if !item.selecting {
                if item.toggleActionId != nil {
                    Button {
                        toggle(item)
                    } label: {
                        Label(L10n.select, systemImage: "checkmark.circle")
                    }
                    Divider()
                }
                if let actionId = item.renameActionId {
                    Button {
                        invoke(actionId)
                    } label: {
                        Label(L10n.rename, systemImage: "pencil")
                    }
                }
                if let actionId = item.deleteActionId {
                    Divider()
                    Button(role: .destructive) {
                        invoke(actionId)
                    } label: {
                        Label(L10n.delete, systemImage: "trash")
                    }
                }
            }
        }
    }

    /// Toggles the folder's selection membership through the shared producer.
    private func toggle(_ item: FolderListItemSnapshot) {
        if let actionId = item.toggleActionId {
            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
        }
    }

    private func invoke(_ actionId: String) {
        navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
    }

    @ToolbarContentBuilder
    private var addToolbar: some ToolbarContent {
        ToolbarItem {
            Button {
                addingFolder = true
            } label: {
                Label(L10n.folderAddAction, systemImage: "plus")
            }
            .disabled(!canAdd || selecting)
        }
    }

}

// MARK: - Equivalent domains

struct EquivalentDomainsView: View {
    let entry: ScreenEntrySnapshot

    private var snapshot: EquivalentDomainsSnapshot {
        entry.equivalentDomains ?? EquivalentDomainsSnapshot.companion.empty
    }

    private var rows: [EquivalentDomainItemSnapshot] {
        snapshot.items.filter { !$0.isSection }
    }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: rows.isEmpty) {
            ContentUnavailableView {
                Label(L10n.equivalentDomainsEmptyLabel, systemImage: "globe")
            } description: {
                Text(L10n.equivalentDomainsEmptyText)
            }
        } content: {
            List {
                ForEach(rows, id: \.id) { item in
                    row(item)
                }
            }
        }
        .navigationTitle(L10n.equivalentDomains)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private func row(_ item: EquivalentDomainItemSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(item.title)
                .lineLimit(3)
                .textSelection(.enabled)
            if item.global || item.excluded {
                HStack(spacing: 6) {
                    if item.global { tag(L10n.urlruleGlobalLabel) }
                    if item.excluded { tag(L10n.urlruleExcludedLabel) }
                }
            }
        }
        .padding(.vertical, 2)
        // Collapse the title + tags into one VoiceOver element so it reads e.g.
        // "<domains>, Global, Excluded" together rather than as disconnected fragments.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(for: item))
    }

    private func accessibilityLabel(for item: EquivalentDomainItemSnapshot) -> String {
        var parts = [item.title]
        if item.global { parts.append(L10n.urlruleGlobalLabel) }
        if item.excluded { parts.append(L10n.urlruleExcludedLabel) }
        return parts.joined(separator: ", ")
    }

    private func tag(_ text: String) -> some View {
        // Bumped from caption2/secondary-on-quaternary (low contrast) to a heavier
        // caption with a stronger fill and primary text so the status reads clearly.
        Text(text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color(platform: .platformSeparator), in: Capsule())
            .foregroundStyle(.primary)
    }
}

// MARK: - Duplicates

/// Lists duplicate vault items using the shared row renderers and Kotlin-owned
/// group-scoped selection.
struct DuplicatesView: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(\.colorScheme) private var colorScheme
    let entry: ScreenEntrySnapshot

    /// Drives the renderers from the entry's Kotlin-owned session. Created lazily
    /// on first appear from `entry.duplicatesSession` (always non-null for a
    /// DUPLICATES entry — `startEntry` sets it before the first snapshot is emitted).
    @State private var listModel: DuplicatesListModel?
    /// Required by the shared renderers; unused here — Duplicates owns its selection
    /// via the projected row flags (Kotlin-side, group-scoped), not the native list
    /// selection, so the `.openOrToggle` bridge never writes `selectedRowIds`.
    @State private var selection = VaultSelectionModel()

    /// The Duplicates interaction policy: tap opens (or toggles while selecting), no
    /// native multi-select highlight, row context menus on (the "Select" begin), no
    /// iOS sync-status header, no quick-filter chips.
    private let config = VaultListConfig(
        rowTap: .openOrToggle,
        supportsMultiSelect: false,
        contextMenu: true,
        syncHeader: false,
        quickFilters: false
    )

    var body: some View {
        Group {
            if let listModel {
                content(listModel)
            } else {
                LoadingIndicator()
            }
        }
        .navigationTitle(L10n.featItemDuplicateItemsTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            if listModel == nil, let session = entry.duplicatesSession {
                listModel = DuplicatesListModel(session: session)
            }
            listModel?.start()
        }
        .onDisappear { listModel?.stop() }
    }

    @ViewBuilder
    private func content(_ listModel: DuplicatesListModel) -> some View {
        SnapshotContent(loaded: listModel.loaded, isEmpty: listModel.store.structure.entries.isEmpty) {
            ContentUnavailableView {
                Label(L10n.duplicatesEmptyLabel, systemImage: "doc.on.doc")
            } description: {
                Text(L10n.vaultDuplicatesEmptyText)
            }
        } content: {
            // The `.openOrToggle` row-tap policy is handled inside the bridge
            // (deselects the momentary native selection and calls open / toggle),
            // so no open-on-selection bridge is needed here.
            VaultListRepresentable(
                model: listModel,
                selection: selection,
                accountsModel: accountsModel,
                config: config,
                colorScheme: colorScheme
            )
        }
        // The bulk-action bar appears once a multi-selection is active; its overflow
        // menu carries the producer's full cipher bulk-action set (favourite / rename /
        // trash / send / merge / …), each routed through the entry's interceptor.
        .vaultListBottomBar {
            DuplicatesBulkBar(model: listModel)
        }
    }

}

private struct DuplicatesBulkBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let model: DuplicatesListModel

    var body: some View {
        ZStack {
            if model.selection.count >= 1 {
                VaultSelectionActionBar(
                    count: model.selection.count,
                    actions: model.selection.actions,
                    invoke: { model.invokeSelectionAction(id: $0) },
                    clear: { model.clearSelection() }
                )
                .padding(.bottom, 12)
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.3), value: model.selection.count >= 1)
    }
}

/// A minimal name prompt reused by the folder add / rename flows, in the shared
/// `ModalSheet` chrome.
private struct FolderNameSheet: View {

    @Environment(\.dismiss) private var dismiss

    let title: String
    let confirmLabel: String
    var initialName: String = ""
    let save: (String) async throws -> Void

    @State private var name: String = ""
    @State private var isSaving = false
    @State private var errorText: String?

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !isSaving
    }

    var body: some View {
        ModalSheet(
            title: title,
            width: 460,
            height: 220,
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
            Button(confirmLabel) { submit() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave)
        }
        .onAppear { name = initialName }
    }

    private func submit() {
        isSaving = true
        errorText = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                try await save(trimmed)
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}
