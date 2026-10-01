import SwiftUI
import KeyguardShared

struct SendView: View {
    @Environment(AddItemModel.self) private var addItemModel
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(SendModel.self) private var sendModel
    @State private var showingAddItem = false
    /// The list's multi-selection, kept in lockstep with the shared producer's
    /// selection handle. Send publishes no per-item `selected` flag, so the
    /// reconcile is the count-only variant.
    @State private var selection = ListSelectionModel()

    #if os(macOS)
    @State private var filterSidebarShown = FilterSidebarMemory.mainWide
    #endif

    #if os(iOS)
    /// Drives the iPhone (compact) vs iPad (regular) layout split: compact gets a
    /// `NavigationStack` (so the search bar collapses under the title and reveals on
    /// swipe-down), regular keeps the two-column `NavigationSplitView`.
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// iPad single selection that drives the side-pane detail column. On iPhone the
    /// detail is a shared-nav-stack entry instead (see `openDetail`), so this is only
    /// read on iPad (regular width).
    @State private var selectedDetailId: String?
    @State private var columnVisibility: NavigationSplitViewVisibility = .automatic

    /// Opens a Send item: on iPhone (compact) it pushes a Send-detail entry onto the
    /// shared Kotlin nav stack; on iPad (regular) it selects the side-pane detail.
    private func openDetail(_ item: SendListItemSnapshot) {
        if horizontalSizeClass == .compact {
            guard let secretId = item.secretId, let accountId = item.accountId else { return }
            navigationModel.pushSendDetail(sendId: secretId, accountId: accountId)
        } else {
            selectedDetailId = item.id
        }
    }
    #endif

    private var snapshot: SendListSnapshot { sendModel.sendList }

    /// Local typing buffer; the Kotlin query sink stays the source of truth
    /// through the `bridgedText` reconciliation on the searchable field.
    @State private var query = ""

    var body: some View {
        baseBody
            .observing(
                start: { sendModel.startSendListObservation() },
                stop: { sendModel.stopSendListObservation() }
            )
            .onChange(of: selection.selectedRowIds) { _, newValue in
                syncSelection(newValue)
            }
            .onChange(of: snapshot.selectionCount) { _, count in
                selection.reconcile(selectionCount: count)
            }
    }

    @ViewBuilder
    private var baseBody: some View {
        #if os(macOS)
        macBody
        #else
        iosBody
        #endif
    }

    // MARK: - macOS body

    #if os(macOS)
    private var macBody: some View {
        NavStackContainer(scope: "send") {
            Group {
                if snapshot.needsAccount {
                    emptyState
                } else {
                    twoPane
                }
            }
            .navigationTitle(L10n.send)
            .searchable(text: $query, placement: .toolbar, prompt: Text(L10n.sendMainSearchPlaceholder))
            .bridgedText(
                $query,
                remote: snapshot.query,
                remoteRevision: snapshot.queryRevision,
                send: sendModel.setSendListQuery
            )
            .toolbar { macToolbar }
            .sheet(isPresented: $showingAddItem, onDismiss: addItemModel.stopAddFormObservation) {
                AddItemSheet(mode: .send, canCreateFileSend: snapshot.canCreateFileSend)
            }
            .onChange(of: detailObservationTarget, initial: true) { _, target in
                syncDetailObservation(target)
            }
        }
    }

    @ToolbarContentBuilder
    private var macToolbar: some CustomizableToolbarContent {
        if !filterSidebarShown && !sendModel.sendFilterToolbar.filters.isEmpty {
            ToolbarItem(id: "send.filters") {
                SendFilterToolbarButton()
            }
        }
        if !sendModel.sendSortToolbar.sort.isEmpty {
            ToolbarItem(id: "send.sort") {
                SendSortToolbarButton()
            }
        }
        ToolbarItem(id: "send.create") {
            SendCreateToolbarButton(showingAddItem: $showingAddItem)
        }
        if !macToolbarActions.isEmpty {
            ToolbarItem(id: "send.more") {
                SendActionsToolbarButton(actions: macToolbarActions)
            }
        }
    }

    private var macToolbarActions: [VaultActionSnapshot] {
        sendModel.sendActionsToolbar.actions.filter { !menuBarHoistedActionIds.contains($0.id) }
    }

    private var twoPane: some View {
        MasterDetailLayout(filterSidebarShown: $filterSidebarShown) {
            filterSidebar
        } list: {
            listColumn
        } detail: {
            detailColumn
        }
    }

    private var listColumn: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: !hasSelectableItems) {
            ListEmptyState(
                title: L10n.sendMainEmptyTitle, systemImage: "paperplane", isSearching: !snapshot.query.isEmpty)
        } content: {
            macList
        }
        .contentShape(Rectangle())
        .dropDestination(for: URL.self) { urls, _ in
            guard snapshot.canDropFile, let url = urls.first else { return false }
            sendModel.dropFileOnSendList(url: url)
            return true
        }
        // Floating bulk-action bar, shown once two or more Sends are selected.
        .selectionBar(
            count: snapshot.selectionCount,
            visible: snapshot.selectionCount >= 2,
            actions: snapshot.selectionActions,
            invoke: { sendModel.invokeSendListSelectionAction(id: $0) },
            clear: { sendModel.clearSendListSelection() }
        )
    }

    private var macList: some View {
        List(selection: $selection.selectedRowIds) {
            ForEach(sections) { section in
                Section {
                    ForEach(section.items, id: \.id) { item in
                        row(item, editing: false)
                    }
                } header: {
                    if let title = section.title { Text(title) }
                }
            }
        }
        .scrollsToTop(onChangeOf: snapshot.itemsRevision, topId: sections.first?.items.first?.id)
    }

    /// Observes the detail of the single selected Send, or stops when the selection
    /// is empty or holds more than one Send. macOS-only.
    private func updateDetailObservation() {
        let items = selectedItemRows
        if items.count == 1,
            let item = items.first,
            let secretId = item.secretId,
            let accountId = item.accountId
        {
            sendModel.startSendDetailObservation(itemId: secretId, accountId: accountId)
        } else {
            sendModel.stopSendDetailObservation()
        }
    }

    /// The detail pane is shown only for a single selected Send.
    private var isItemSelected: Bool {
        selectedItemRows.count == 1
    }
    #endif

    // MARK: - iOS body

    #if os(iOS)
    @ViewBuilder
    private var iosBody: some View {
        if horizontalSizeClass == .compact {
            compactBody
        } else {
            splitBody
        }
    }

    /// iPad (regular width): the two-column master-detail split, search in the
    /// sidebar column. Unchanged from the original iOS body.
    private var splitBody: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            sidebarColumn
                .navigationTitle(L10n.send)
                .listSearchable(text: $query, prompt: Text(L10n.sendMainSearchPlaceholder))
                .bridgedText(
                    $query,
                    remote: snapshot.query,
                    remoteRevision: snapshot.queryRevision,
                    send: sendModel.setSendListQuery
                )
                .toolbar { iosToolbar }
                .environment(\.editMode, $selection.editMode)
        } detail: {
            // The selection-driven side-pane detail (single-slot `sendModel.sendDetail`)
            // is the root; producer pushes from it render above it via the shared
            // Kotlin nav stack.
            NavStackContainer(scope: "send") {
                detailColumn
            }
        }
        .navigationSplitViewStyle(.balanced)
        .sheet(isPresented: $showingAddItem, onDismiss: addItemModel.stopAddFormObservation) {
            AddItemSheet(mode: .send, canCreateFileSend: snapshot.canCreateFileSend)
        }
        .onChange(of: detailObservationTarget, initial: true) { _, target in
            syncDetailObservation(target)
            clearMissingSelectedDetail()
        }
        .onChange(of: selection.editMode) { _, mode in
            handleEditModeChange(mode)
        }
    }

    private var compactBody: some View {
        NavStackContainer(scope: "send") {
            sidebarColumn
                .navigationTitle(L10n.send)
                .navigationBarTitleDisplayMode(.large)
                .listSearchable(text: $query, prompt: Text(L10n.sendMainSearchPlaceholder))
                .bridgedText(
                    $query,
                    remote: snapshot.query,
                    remoteRevision: snapshot.queryRevision,
                    send: sendModel.setSendListQuery
                )
                .toolbar { iosToolbar }
                .environment(\.editMode, $selection.editMode)
        }
        .sheet(isPresented: $showingAddItem, onDismiss: addItemModel.stopAddFormObservation) {
            AddItemSheet(mode: .send, canCreateFileSend: snapshot.canCreateFileSend)
        }
        .onChange(of: selection.editMode) { _, mode in
            handleEditModeChange(mode)
        }
    }

    @ViewBuilder
    private var sidebarColumn: some View {
        Group {
            if snapshot.needsAccount {
                emptyState
            } else if !snapshot.loaded {
                LoadingIndicator()
            } else if !hasSelectableItems {
                ListEmptyState(
                    title: L10n.sendMainEmptyTitle, systemImage: "paperplane", isSearching: !snapshot.query.isEmpty)
            } else {
                iosList
            }
        }
        .selectionBar(
            count: snapshot.selectionCount,
            visible: selection.editMode.isEditing && snapshot.selectionCount >= 1,
            actions: snapshot.selectionActions,
            invoke: { sendModel.invokeSendListSelectionAction(id: $0) },
            clear: { sendModel.clearSendListSelection() }
        )
    }

    private var iosList: some View {
        modeList
            .scrollsToTop(onChangeOf: snapshot.itemsRevision, topId: sections.first?.items.first?.id)
    }

    private var modeList: some View {
        selection.editMode.isEditing
            ? List(selection: $selection.selectedRowIds) {
                ForEach(sections) { section in
                    Section {
                        ForEach(section.items, id: \.id) { item in
                            iosRow(item, editing: true)
                        }
                    } header: {
                        if let title = section.title { Text(title) }
                    }
                }
            }
            : List(selection: $selectedDetailId) {
                ForEach(sections) { section in
                    Section {
                        ForEach(section.items, id: \.id) { item in
                            iosRow(item, editing: false)
                        }
                    } header: {
                        if let title = section.title { Text(title) }
                    }
                }
            }
    }

    @ViewBuilder
    private func iosRow(_ item: SendListItemSnapshot, editing: Bool) -> some View {
        if item.kind == VaultListItemKind.item && horizontalSizeClass == .compact && !editing {
            Button {
                openDetail(item)
            } label: {
                row(item, editing: editing)
            }
            .buttonStyle(.plain)
        } else {
            row(item, editing: editing)
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
            Button {
                showingAddItem = true
            } label: {
                Label(L10n.addsendHeaderNewTitle, systemImage: "plus")
            }
            .accessibilityLabel(L10n.textActionSendTitle)
            .disabled(snapshot.needsAccount)
        }
        ToolbarItem(placement: .topBarTrailing) {
            filterMenu
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                sortMenu
                if !snapshot.listActions.isEmpty {
                    Divider()
                    listActionMenuItems(actions: snapshot.listActions) {
                        sendModel.invokeSendListAction(id: $0)
                    }
                }
            } label: {
                Label(L10n.more, systemImage: "ellipsis.circle")
            }
        }
    }

    private func handleEditModeChange(_ mode: EditMode) {
        if mode.isEditing {
            selectedDetailId = nil
        } else {
            selection.clear { sendModel.clearSendListSelection() }
        }
    }

    private func clearMissingSelectedDetail() {
        guard let id = selectedDetailId else { return }
        let itemExists = snapshot.items.contains {
            $0.id == id && $0.kind == VaultListItemKind.item
        }
        if !itemExists {
            selectedDetailId = nil
        }
    }
    #endif

    // MARK: - Selection

    private struct DetailObservationTarget: Equatable {
        let itemId: String
        let accountId: String
    }

    private var detailObservationTarget: DetailObservationTarget? {
        #if os(iOS)
        guard let selectedDetailId else { return nil }
        return makeDetailObservationTarget(forRowId: selectedDetailId)
        #else
        let items = selectedItemRows
        guard items.count == 1, let item = items.first else { return nil }
        return makeDetailObservationTarget(for: item)
        #endif
    }

    private func makeDetailObservationTarget(forRowId id: String) -> DetailObservationTarget? {
        guard let item = snapshot.items.first(where: { $0.id == id && $0.kind == VaultListItemKind.item }) else {
            return nil
        }
        return makeDetailObservationTarget(for: item)
    }

    private func makeDetailObservationTarget(for item: SendListItemSnapshot) -> DetailObservationTarget? {
        guard let secretId = item.secretId, let accountId = item.accountId else { return nil }
        return DetailObservationTarget(itemId: secretId, accountId: accountId)
    }

    private func syncDetailObservation(_ target: DetailObservationTarget?) {
        if let target {
            sendModel.startSendDetailObservation(itemId: target.itemId, accountId: target.accountId)
        } else {
            sendModel.stopSendDetailObservation()
        }
    }

    /// The currently selected rows that are real Sends (ignoring any stale ids the
    /// producer pruned out of the visible list).
    private var selectedItemRows: [SendListItemSnapshot] {
        snapshot.items.filter {
            $0.kind == VaultListItemKind.item && selection.selectedRowIds.contains($0.id)
        }
    }

    /// Forwards the list's selection delta to the shared producer. The detail pane
    /// observation is derived separately from the current selection and item list.
    private func syncSelection(_ newValue: Set<String>) {
        selection.sync(
            newValue,
            isKnownId: { id in
                snapshot.items.contains { $0.id == id && $0.kind == VaultListItemKind.item }
            },
            toggle: sendModel.toggleSendListSelection(id:)
        )
    }

    // MARK: - Shared pieces

    private var hasSelectableItems: Bool {
        snapshot.items.contains { $0.kind == VaultListItemKind.item }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label(L10n.sendMainEmptyTitle, systemImage: "paperplane")
        } description: {
            Text(L10n.sendMainEmptyAddAccountText)
        }
    }

    @ViewBuilder
    private var detailColumn: some View {
        if isDetailItemSelected {
            SendDetailView()
        } else {
            ContentUnavailableView {
                Label(L10n.sendViewNoSelectionTitle, systemImage: "sidebar.right")
            } description: {
                Text(L10n.sendViewNoSelectionText)
            }
        }
    }

    private var isDetailItemSelected: Bool {
        #if os(iOS)
        guard let id = selectedDetailId else { return false }
        return snapshot.items.contains { $0.id == id && $0.kind == VaultListItemKind.item }
        #else
        return isItemSelected
        #endif
    }

    private var sections: [SnapshotListSection<SendListItemSnapshot>] {
        snapshotListSections(
            snapshot.items, id: { $0.id },
            sectionTitle: {
                $0.kind == VaultListItemKind.section ? $0.title : nil
            })
    }

    @ViewBuilder
    private func row(_ item: SendListItemSnapshot, editing: Bool) -> some View {
        if item.kind == VaultListItemKind.item {
            SendRow(item: item)
                .tag(item.id)
                // Always attach the menu and gate only its content: toggling the
                // bar must not restructure every row, or the list scroll jumps.
                .contextMenu {
                    if showsBulkContextMenu(editing: editing) {
                        listActionMenuItems(actions: snapshot.selectionActions) {
                            sendModel.invokeSendListSelectionAction(id: $0)
                        }
                    }
                }
        } else {
            // NO_ITEMS placeholder.
            Text(item.title.isEmpty ? L10n.sendMainEmptyTitle : item.title)
                .font(.callout)
                .foregroundStyle(.secondary)
                .selectionDisabled()
        }
    }

    private func showsBulkContextMenu(editing: Bool) -> Bool {
        #if os(iOS)
        return editing && snapshot.selectionCount >= 1
        #else
        return snapshot.selectionCount >= 2
        #endif
    }

    // MARK: - Filter sidebar (macOS)

    #if os(macOS)
    private var filterSidebar: some View {
        FilterSidebar(
            filters: snapshot.filters,
            count: snapshot.loaded ? Int(snapshot.totalCount) : nil,
            canClearFilters: snapshot.canClearFilters,
            invoke: { sendModel.invokeSendListFilter(id: $0) },
            clear: { sendModel.clearSendListFilters() }
        )
    }
    #endif

    // MARK: - Toolbar menus

    private var filterMenu: some View {
        FilterMenu(
            filters: snapshot.filters,
            canClearFilters: snapshot.canClearFilters,
            activeFilterCount: Int(snapshot.activeFilterCount),
            invoke: { sendModel.invokeSendListFilter(id: $0) },
            clear: { sendModel.clearSendListFilters() }
        )
    }

    private var sortMenu: some View {
        SortMenu(
            sort: snapshot.sort,
            canClearSort: snapshot.canClearSort,
            invoke: { sendModel.invokeSendListSort(id: $0) },
            clear: { sendModel.clearSendListSort() }
        )
    }
}

#if os(macOS)
private struct SendFilterToolbarButton: View {
    @Environment(SendModel.self) private var sendModel

    var body: some View {
        let state = sendModel.sendFilterToolbar
        FilterMenu(
            filters: state.filters,
            canClearFilters: state.canClearFilters,
            activeFilterCount: state.activeFilterCount,
            invoke: { sendModel.invokeSendListFilter(id: $0) },
            clear: { sendModel.clearSendListFilters() }
        )
    }
}

private struct SendSortToolbarButton: View {
    @Environment(SendModel.self) private var sendModel

    var body: some View {
        let state = sendModel.sendSortToolbar
        SortMenu(
            sort: state.sort,
            canClearSort: state.canClearSort,
            invoke: { sendModel.invokeSendListSort(id: $0) },
            clear: { sendModel.clearSendListSort() }
        )
    }
}

private struct SendCreateToolbarButton: View {
    @Environment(SendModel.self) private var sendModel
    @Binding var showingAddItem: Bool

    var body: some View {
        Button {
            showingAddItem = true
        } label: {
            Label(L10n.addsendHeaderNewTitle, systemImage: "plus")
        }
        .help(L10n.textActionSendTitle)
        .accessibilityLabel(L10n.textActionSendTitle)
        .disabled(sendModel.sendCreateToolbar.needsAccount)
    }
}

private struct SendActionsToolbarButton: View {
    @Environment(SendModel.self) private var sendModel
    let actions: [VaultActionSnapshot]

    var body: some View {
        Menu {
            listActionMenuItems(actions: actions) {
                sendModel.invokeSendListAction(id: $0)
            }
        } label: {
            Label(L10n.more, systemImage: "ellipsis.circle")
        }
        .help(L10n.more)
    }
}
#endif

private struct SendRow: View {
    let item: SendListItemSnapshot

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "paperplane.fill")
                .foregroundStyle(.tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.body)
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}
