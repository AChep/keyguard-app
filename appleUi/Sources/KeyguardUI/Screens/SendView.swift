import SwiftUI
import KeyguardShared

struct SendView: View {
    @Environment(AddItemModel.self) private var addItemModel
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(SendModel.self) private var sendModel
    @State private var showingAddItem = false
    /// Send publishes no per-item `selected` flag, so the reconcile is the count-only variant.
    @State private var selection = ListSelectionModel()

    #if os(macOS)
    @State private var filterSidebarShown = FilterSidebarMemory.mainWide
    #endif

    #if os(iOS)
    private var usesPanels: Bool { ListDetailNavigation.usesPanels }
    private var listOrigin: ListNavigationOrigin { ListNavigationOrigin(scope: "send", listEntryId: nil) }
    private var browseDetail: ScreenEntrySnapshot? {
        guard usesPanels, let entry = navigationModel.navStack("send").first, entry.kind == .sendDetail else {
            return nil
        }
        return entry
    }
    private var selectedDetailId: String? { browseDetail.flatMap(rowId(for:)) }
    private func rowId(for detail: ScreenEntrySnapshot) -> String? {
        snapshot.items.first {
            $0.kind == .item && $0.secretId == detail.sendId && $0.accountId == detail.sendAccountId
        }?.id
    }
    private var browseSelectionInput: BrowseSelectionInput {
        guard let detail = browseDetail else { return BrowseSelectionInput() }
        return BrowseSelectionInput(
            detailId: detail.instanceId, fromList: detail.fromList,
            isPresent: rowId(for: detail) != nil, isLoaded: snapshot.loaded || snapshot.needsAccount)
    }
    private var browseSelection: Binding<String?> {
        Binding(
            get: { selectedDetailId },
            set: { id in
                if let item = snapshot.items.first(where: { $0.id == id }) { openDetail(item) }
            })
    }

    private func openDetail(_ item: SendListItemSnapshot) {
        guard let secretId = item.secretId, let accountId = item.accountId else { return }
        if usesPanels {
            navigationModel.openListSend(origin: listOrigin, sendId: secretId, accountId: accountId)
        } else {
            navigationModel.pushSendDetail(sendId: secretId, accountId: accountId)
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

    private var isItemSelected: Bool {
        selectedItemRows.count == 1
    }
    #endif

    // MARK: - iOS body

    #if os(iOS)
    private var iosBody: some View {
        NavStackContainer(scope: "send", rootList: .send) {
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
        .clearsMissingBrowseSelection(browseSelectionInput) { clearBrowseSelection() }
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
            : List(selection: browseSelection) {
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
        if item.kind == VaultListItemKind.item && !usesPanels && !editing {
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
            clearBrowseSelection()
        } else {
            selection.clear { sendModel.clearSendListSelection() }
        }
    }

    private func clearBrowseSelection() {
        guard let detail = browseDetail else { return }
        navigationModel.clearListDetail(origin: listOrigin, detailInstanceId: detail.instanceId)
    }
    #endif

    // MARK: - Selection

    #if os(macOS)
    private struct DetailObservationTarget: Equatable {
        let itemId: String
        let accountId: String
    }

    private var detailObservationTarget: DetailObservationTarget? {
        let items = selectedItemRows
        guard items.count == 1, let item = items.first, let secretId = item.secretId, let accountId = item.accountId
        else { return nil }
        return DetailObservationTarget(itemId: secretId, accountId: accountId)
    }

    private func syncDetailObservation(_ target: DetailObservationTarget?) {
        if let target {
            sendModel.startSendDetailObservation(itemId: target.itemId, accountId: target.accountId)
        } else {
            sendModel.stopSendDetailObservation()
        }
    }

    /// Ignores stale ids the producer pruned out of the visible list.
    private var selectedItemRows: [SendListItemSnapshot] {
        snapshot.items.filter {
            $0.kind == VaultListItemKind.item && selection.selectedRowIds.contains($0.id)
        }
    }
    #endif

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

    #if os(macOS)
    @ViewBuilder
    private var detailColumn: some View {
        if isItemSelected {
            SendDetailView()
        } else {
            ListNoSelectionView(kind: .send)
        }
    }
    #endif

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
