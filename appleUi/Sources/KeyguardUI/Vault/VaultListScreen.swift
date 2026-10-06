import SwiftUI
import KeyguardShared

/// Vault-list screen backed by one `VaultListSessionModel` per appearance.
struct VaultListScreen: View {
    @Environment(NavigationModel.self) private var navigationModel

    @State private var model: VaultListSessionModel
    /// Local search typing buffer; the session's query stays the source of truth
    /// through the `bridgedText` reconciliation.
    @State private var query = ""
    @State private var selection = VaultSelectionModel()
    /// The provider chosen in the add-account menu; non-nil pushes the matching
    /// login screen (Bitwarden form / KeePass form).
    @State private var accountLogin = FormPresentation<AccountLoginForm>()
    @State private var pendingLogin: AddAccountKind?
    @State private var showingRecents = false

    #if os(macOS)
    @State private var detailModel: CipherDetailModel
    @State private var filterSidebarShown = FilterSidebarMemory.mainWide
    #endif

    #if os(iOS)
    @State private var editMode: EditMode = .inactive
    #endif

    init(core: KeyguardCore) {
        _model = State(wrappedValue: VaultListSessionModel(core: core))
        #if os(macOS)
        _detailModel = State(wrappedValue: .cipherDetail(core: core))
        #endif
    }

    var body: some View {
        platformBody
            .onChange(of: pendingLogin) { _, kind in
                if kind == nil { accountLogin.close() }
            }
            .onAppear { onAppearScreen() }
            .onDisappear { onDisappearScreen() }
            // Retry reveal requests as list structure arrives without rebuilding the UI.
            .background { revealWatcher }
    }

    @ViewBuilder
    private var revealWatcher: some View {
        #if os(iOS)
        VaultRevealWatcher(model: model, selection: selection, navigationModel: navigationModel, editMode: $editMode)
        #else
        VaultRevealWatcher(model: model, selection: selection, navigationModel: navigationModel)
        #endif
    }

    // MARK: - Lifecycle

    private func onAppearScreen() {
        model.start()
    }

    private func onDisappearScreen() {
        model.stop()
    }

    // MARK: - Shared pieces

    @ViewBuilder
    private var searchSuggestions: some View {
        // The qualifier autocomplete affordance; accepting applies it through the
        // session (which re-seeds the query + bumps the revision the buffer adopts).
        if let suggestion = model.header.qualifierSuggestion {
            Button {
                model.applyQualifierSuggestion()
            } label: {
                Label(suggestion, systemImage: "sparkles")
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label(L10n.vaultMainEmptyTitle, systemImage: "tray")
        } description: {
            Text(L10n.vaultMainEmptyAddAccountText)
        } actions: {
            Menu(L10n.accountMainAddAccountTitle) {
                AddAccountMenuItems { pendingLogin = $0 }
            }
            .menuStyle(.button)
            .buttonStyle(.borderedProminent)
            .fixedSize()
        }
    }

    // MARK: - macOS body

    #if os(macOS)
    private var platformBody: some View {
        NavStackContainer(scope: "vault") {
            Group {
                if model.header.needsAccount {
                    emptyState
                } else {
                    twoPane
                }
            }
            .navigationTitle(L10n.homeVaultLabel)
            .searchable(text: $query, placement: .toolbar, prompt: Text(L10n.vaultMainSearchPlaceholder))
            .searchSuggestions { searchSuggestions }
            .bridgedText(
                $query,
                remote: model.header.query,
                remoteRevision: model.header.queryRevision,
                send: model.setQuery
            )
            .toolbar { macToolbar }
            .navigationDestination(item: $pendingLogin) { kind in
                AddAccountDestination(kind: kind, presentation: accountLogin)
            }
            .sheet(isPresented: $showingRecents) {
                RecentsView()
            }
            .vaultListEscHandler(model)
        }
    }

    private var listConfig: VaultListConfig {
        var config = VaultListConfig.vaultMain
        config.quickFilters = !filterSidebarShown
        return config
    }

    private var twoPane: some View {
        MasterDetailLayout(filterSidebarShown: $filterSidebarShown) {
            VaultFilterSidebar(
                catalog: model.filterCatalog,
                state: model.filterState,
                count: model.header.loaded ? model.store.structure.itemCount : nil,
                invoke: { model.invokeFilter(id: $0) },
                toggleSection: { model.toggleFilterSection(sectionId: $0) },
                clear: { model.clearFilters() },
                save: { model.saveFilters() }
            )
            .equatable()
        } list: {
            VaultListPane(model: model, selection: selection, config: listConfig)
        } detail: {
            VaultDetailPane(listModel: model, selection: selection, model: detailModel)
        }
    }

    @ToolbarContentBuilder
    private var macToolbar: some CustomizableToolbarContent {
        if macToolbarShowsFilters {
            ToolbarItem(id: "vault.filters") {
                VaultFilterToolbarButton(model: model)
            }
        }
        if macToolbarShowsSort {
            ToolbarItem(id: "vault.sort") {
                VaultSortToolbarButton(model: model)
            }
        }
        if !model.createActions.isEmpty {
            ToolbarItem(id: "vault.create") {
                VaultCreateToolbarButton(model: model)
            }
        }
        if model.toolbarSyncing {
            ToolbarItem(id: "vault.syncing") {
                VaultSyncToolbarIndicator()
            }
        }
        ToolbarItem(id: "vault.recents") {
            VaultRecentsToolbarButton(showingRecents: $showingRecents)
        }
        if #available(macOS 26.0, *) {
            ToolbarSpacer(.fixed)
        }
        ToolbarItem(id: "vault.addAccount") {
            VaultAddAccountToolbarButton(pendingLogin: $pendingLogin)
        }
        if !macToolbarOverflowActions.isEmpty {
            ToolbarItem(id: "vault.more") {
                VaultOverflowToolbarButton(model: model, actions: macToolbarOverflowActions)
            }
        }
    }

    private var macToolbarShowsFilters: Bool {
        // The accountless placeholder replaces the entire split layout, so
        // its remembered sidebar width does not mean a sidebar is visible.
        (model.header.needsAccount || !filterSidebarShown)
            && (model.filterState.canClear || model.filterCatalog.groups.contains { !$0.items.isEmpty })
    }

    private var macToolbarShowsSort: Bool {
        model.sortMenu.visible && !model.sortMenu.items.isEmpty
    }

    private var macToolbarOverflowActions: [VaultAction] {
        model.toolbarActions.filter { !menuBarHoistedActionIds.contains($0.id) }
    }
    #endif

    // MARK: - iOS body

    #if os(iOS)
    private var platformBody: some View {
        NavStackContainer(scope: "vault", rootList: .vault, rootVaultList: model) {
            iosContent
                .navigationTitle(L10n.homeVaultLabel)
                .navigationBarTitleDisplayMode(.large)
                .listSearchable(text: $query, prompt: Text(L10n.vaultMainSearchPlaceholder))
                .searchSuggestions { searchSuggestions }
                .bridgedText(
                    $query,
                    remote: model.header.query,
                    remoteRevision: model.header.queryRevision,
                    send: model.setQuery
                )
                .toolbar { iosToolbar }
                .navigationDestination(item: $pendingLogin) { kind in
                    AddAccountDestination(kind: kind, presentation: accountLogin)
                }
                .environment(\.editMode, $editMode)
        }
        .sheet(isPresented: $showingRecents) {
            RecentsView()
        }
        .onChange(of: editMode) { _, mode in
            handleEditModeChange(mode)
        }
    }

    @ViewBuilder
    private var iosContent: some View {
        if model.header.needsAccount {
            emptyState
        } else if !model.header.loaded {
            LoadingIndicator()
        } else {
            // The list + selection + bulk bar live in the pane, so a per-tap
            // `selectedRowIds` change re-bodies only the pane, not this screen
            // (its search field / toolbar are siblings).
            VaultListPane(model: model, selection: selection)
        }
    }

    @ToolbarContentBuilder
    private var iosToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if hasSelectableItems {
                EditButton()
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            VaultCreateMenu(
                actions: model.header.createActions,
                disabled: model.header.needsAccount,
                invoke: { model.createItem(actionId: $0) }
            )
        }
        ToolbarItem(placement: .topBarTrailing) {
            VaultFilterMenuView(
                catalog: model.filterCatalog,
                state: model.filterState,
                invoke: { model.invokeFilter(id: $0) },
                clear: { model.clearFilters() },
                save: { model.saveFilters() }
            )
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                if model.sortMenu.visible {
                    VaultSortMenuView(
                        menu: model.sortMenu,
                        invoke: { model.invokeSort(id: $0) },
                        clear: { model.clearSort() }
                    )
                }
                Divider()
                Button {
                    showingRecents = true
                } label: {
                    Label(L10n.vaultRecentsTitle, systemImage: "clock.arrow.circlepath")
                }
                Menu {
                    AddAccountMenuItems { pendingLogin = $0 }
                } label: {
                    Label(L10n.accountMainAddAccountTitle, systemImage: "person.badge.plus")
                }
                if !model.toolbar.actions.isEmpty {
                    Divider()
                    vaultActionMenuItems(model.toolbar.actions) { model.invokeToolbarAction(id: $0) }
                }
            } label: {
                Label(L10n.more, systemImage: "ellipsis.circle")
            }
        }
    }

    private var hasSelectableItems: Bool {
        model.store.structure.entries.contains { $0.kind == .item }
    }

    /// Leaving Edit mode clears the bulk selection (mirrored into the session).
    private func handleEditModeChange(_ mode: EditMode) {
        if !mode.isEditing && !selection.selectedRowIds.isEmpty {
            model.clearSelection()
            selection.reconcile([])
        }
    }
    #endif
}

#if os(macOS)
private struct VaultFilterToolbarButton: View {
    let model: VaultListSessionModel

    var body: some View {
        VaultFilterMenuView(
            catalog: model.filterCatalog,
            state: model.filterState,
            invoke: { model.invokeFilter(id: $0) },
            clear: { model.clearFilters() },
            save: { model.saveFilters() }
        )
    }
}

private struct VaultSortToolbarButton: View {
    let model: VaultListSessionModel

    var body: some View {
        VaultSortMenuView(
            menu: model.sortMenu,
            invoke: { model.invokeSort(id: $0) },
            clear: { model.clearSort() }
        )
    }
}

private struct VaultCreateToolbarButton: View {
    let model: VaultListSessionModel

    var body: some View {
        VaultCreateMenu(
            actions: model.createActions,
            disabled: model.needsAccount,
            invoke: { model.createItem(actionId: $0) }
        )
    }
}

private struct VaultSyncToolbarIndicator: View {
    var body: some View {
        ProgressView()
            .controlSize(.small)
    }
}

private struct VaultRecentsToolbarButton: View {
    @Binding var showingRecents: Bool

    var body: some View {
        Button {
            showingRecents = true
        } label: {
            Label(L10n.vaultRecentsTitle, systemImage: "clock.arrow.circlepath")
        }
        .help(L10n.vaultRecentsDescription)
    }
}

private struct VaultAddAccountToolbarButton: View {
    @Binding var pendingLogin: AddAccountKind?

    var body: some View {
        Menu {
            AddAccountMenuItems { pendingLogin = $0 }
        } label: {
            Label(L10n.accountMainAddAccountTitle, systemImage: "person.badge.plus")
        }
        .help(L10n.accountMainAddAccountTitle)
    }
}

private struct VaultOverflowToolbarButton: View {
    let model: VaultListSessionModel
    let actions: [VaultAction]

    var body: some View {
        VaultToolbarOverflowMenu(
            actions: actions,
            invoke: { model.invokeToolbarAction(id: $0) }
        )
    }
}
#endif

// MARK: - Reveal watcher

/// Resolves pending cross-surface reveal requests and scrolls to the matching row.
private struct VaultRevealWatcher: View {
    let model: VaultListSessionModel
    let selection: VaultSelectionModel
    let navigationModel: NavigationModel
    #if os(iOS)
    @Binding var editMode: EditMode
    #endif

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            // Catch a request already pending when this sentinel mounts (the
            // screen just became active), then on every subsequent request …
            .onAppear { attemptReveal() }
            .onChange(of: navigationModel.pendingRevealSecretId) { _, _ in attemptReveal() }
            // … and retry as fresh frames stream in (the list may still be
            // loading, or the item may only surface after a search is cleared).
            .onChange(of: model.store.structure.revision) { _, _ in attemptReveal() }
    }

    /// Resolves a pending secret id to a row, clearing search if needed.
    private func attemptReveal() {
        guard let pending = navigationModel.pendingRevealSecretId else { return }
        let rowId = model.store.firstItemRowId { $0.secretId == pending }

        if let rowId {
            // Consume the request before driving the list.
            navigationModel.pendingRevealSecretId = nil
            // One-shot scroll via the dedicated reveal channel — never touches the
            // multi-select `selectedRowIds`.
            selection.reveal(rowId: rowId)
            #if os(macOS)
            selection.selectedRowIds = [rowId]
            #else
            // Open the item through the canonical path after leaving Edit mode.
            editMode = .inactive
            model.openVaultRow(rowId: rowId)
            #endif
        } else if model.header.loaded && !model.header.query.isEmpty {
            // Not in the current (searched) results: clear the query and retry on
            // the frame that produces — the item may be hidden only by the search.
            model.clearQuery()
        } else if model.header.loaded {
            // Loaded, no query narrowing it: the item is filtered out — give up.
            navigationModel.pendingRevealSecretId = nil
        }
        // Not loaded yet: keep the request pending; the revision onChange retries.
    }
}
