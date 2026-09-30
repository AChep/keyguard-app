import SwiftUI
import KeyguardShared

struct SettingsView: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(SettingsModel.self) private var settingsModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var selectedId: String?
    @State private var query = ""
    @State private var searchSelection: String?
    @State private var revealRequest: SettingsRevealRequest?
    @State private var results: [SettingsSearchEntrySnapshot] = []
    #if os(macOS)
    @State private var searchFocusRequest = 0
    #endif

    private var searching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var items: [SettingsItemSnapshot] { settingsModel.settings.items }
    private var accounts: [AccountListItemSnapshot] { accountsModel.accountList.items }

    /// `true` while the shared account-list producer reports an active multi-selection
    /// (bulk Sync / Sign-out). Mirrors the Desktop/Android account multi-select mode.
    private var selectingAccounts: Bool { accountsModel.accountList.selectionCount >= 1 }

    /// Forwards an account-list selection action (per-row toggle / bulk sync /
    /// select-all / sign-out / clear) to the shared producer through the bridge.
    private func invokeAccountListAction(_ id: String) {
        accountsModel.invokeAccountListAction(id: id)
    }

    /// Account rows share the sidebar `List(selection:)` with the settings
    /// categories, so account ids are namespaced behind this prefix to keep the
    /// two selection spaces apart.
    static let accountTagPrefix = "account:"
    static func accountTag(_ id: String) -> String { accountTagPrefix + id }
    static func accountId(fromTag tag: String) -> String? {
        tag.hasPrefix(accountTagPrefix) ? String(tag.dropFirst(accountTagPrefix.count)) : nil
    }

    /// A macOS-only "General" category (launch-at-login, menu-bar-only mode) that
    /// isn't part of the shared settings catalog, so it carries its own fixed id.
    static let generalTag = "macos_general"

    /// The catalog's `.action` rows grouped under their preceding `.section` marker.
    static func groupedSettings(_ items: [SettingsItemSnapshot]) -> [SnapshotListSection<SettingsItemSnapshot>] {
        snapshotListSections(
            items, id: { $0.id },
            sectionTitle: {
                $0.kind == SettingsItemKind.section ? $0.title : nil
            })
    }

    var body: some View {
        baseBody
            .task(id: AppLocalization.shared.locale.identifier) { await settingsModel.loadSettings() }
            .onAppear {
                accountsModel.startAccountListObservation()
                syncAccountDetailObservation()
            }
            .onDisappear {
                accountsModel.stopAccountListObservation()
                accountsModel.stopAccountDetailObservation()
            }
            .onChange(of: selectedId) {
                syncAccountDetailObservation()
            }
            .onChange(of: query, initial: true) {
                searchSelection = nil
                refreshResults()
            }
            .onChange(of: settingsModel.searchIndex) { refreshResults() }
            .onChange(of: accountsModel.accountList.loaded ? accounts.map(\.id) : nil) { _, accountIds in
                // Signing out removes the sidebar row, but List keeps its selection.
                // Ignore loading/reset snapshots so navigating away does not clear it.
                guard let accountIds,
                    let tag = selectedId,
                    let accountId = Self.accountId(fromTag: tag),
                    !accountIds.contains(accountId)
                else { return }
                selectedId = nil
            }
            // Reserve space for bulk actions so the last account stays reachable.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ZStack {
                    if selectingAccounts {
                        AccountSelectionBar(
                            snapshot: accountsModel.accountList,
                            invoke: { invokeAccountListAction($0) }
                        )
                        .padding(.bottom, 12)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(reduceMotion ? nil : .spring(duration: 0.3), value: selectingAccounts)
            }
    }

    private func syncAccountDetailObservation() {
        if let selectedId, let accountId = Self.accountId(fromTag: selectedId) {
            accountsModel.startAccountDetailObservation(accountId: accountId)
        } else {
            accountsModel.stopAccountDetailObservation()
        }
    }

    @ToolbarContentBuilder
    private var addAccountToolbar: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Menu {
                AddAccountMenuItems { navigationModel.addAccountRequest = $0 }
            } label: {
                Label(L10n.accountMainAddAccountTitle, systemImage: "person.badge.plus")
            }
        }
    }

    @ViewBuilder
    private var baseBody: some View {
        #if os(macOS)
        NavStackContainer(scope: "settings") {
            HSplitView {
                VStack(spacing: 0) {
                    NativeListSearchField(
                        text: $query, prompt: L10n.settingssearchSearchPlaceholder, focusRequest: searchFocusRequest
                    )
                    .padding(12)
                    .background {
                        Button(L10n.settingssearchSearchPlaceholder) { searchFocusRequest += 1 }
                            .keyboardShortcut("f", modifiers: [.command, .option])
                            .hidden()
                    }
                    searchableSidebar
                }
                .frame(width: SidebarLayout.width)
                detailColumn
                    .frame(minWidth: SidebarLayout.detailMinWidth, maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle(L10n.settingsMainHeaderTitle)
            .toolbar { addAccountToolbar }
        }
        #else
        // The scaffold places the "settings" nav stack correctly per size class —
        // flat on iPhone, only in the detail pane of the (never-collapsing) split on
        // iPad — so the account detail's producer-driven pushes can't oscillate.
        AdaptiveNavScaffold(
            scope: "settings",
            compact: { compactSettingsList },
            sidebar: {
                searchableSidebar
                    .searchable(text: $query, placement: .sidebar, prompt: Text(L10n.settingssearchSearchPlaceholder))
                    .navigationTitle(L10n.settingsMainHeaderTitle)
                    .toolbar { addAccountToolbar }
            },
            detail: { detailColumn }
        )
        #endif
    }

    @ViewBuilder
    private var searchableSidebar: some View {
        if searching {
            List(selection: $searchSelection) {
                ForEach(results, id: \.id) { entry in
                    Button {
                        // A new selection activates through `onChange`; a repeated click reveals again.
                        if searchSelection == entry.id { activate(entry) } else { searchSelection = entry.id }
                    } label: {
                        SettingsCategoryLabel(title: entry.title, id: entry.categoryId, subtitle: entry.path)
                    }
                    .buttonStyle(.plain)
                    .tag(entry.id)
                }
            }
            #if os(macOS)
            .listStyle(.sidebar)
            #else
            .listStyle(.insetGrouped)
            #endif
            .overlay { searchEmptyState }
            .onChange(of: searchSelection) { _, id in
                if let entry = results.first(where: { $0.id == id }) { activate(entry) }
            }
        } else {
            SettingsSidebar(
                accounts: accounts,
                items: items,
                selection: $selectedId,
                selecting: selectingAccounts,
                invokeAccountListAction: { invokeAccountListAction($0) }
            )
            .onChange(of: selectedId) { _, _ in revealRequest = nil }
        }
    }

    @ViewBuilder
    private var searchEmptyState: some View {
        if settingsModel.searchLoadFailed {
            ContentUnavailableView {
                Label(L10n.settingssearchSearchPlaceholder, systemImage: "exclamationmark.triangle")
            } actions: {
                Button(L10n.retry) { Task { await settingsModel.loadSettings() } }
            }
        } else if settingsModel.searchIndex == nil {
            ProgressView()
        } else if results.isEmpty {
            ContentUnavailableView.search(text: query)
        }
    }

    private func refreshResults() {
        results = settingsModel.searchIndex?.search(query: query) ?? []
    }

    private func activate(_ entry: SettingsSearchEntrySnapshot) {
        // Exit an account's pushed detail stack before revealing a settings page.
        navigationModel.clearScope("settings")
        selectedId = entry.categoryId
        revealRequest = entry.target.map { SettingsRevealRequest(target: $0) }
    }

    #if os(iOS)
    private var compactSettingsList: some View {
        Group {
            if searching {
                List(results, id: \.id) { entry in
                    NavigationLink {
                        SettingsSearchDestination(entry: entry)
                    } label: {
                        SettingsCategoryLabel(title: entry.title, id: entry.categoryId, subtitle: entry.path)
                    }
                }
                .overlay { searchEmptyState }
            } else {
                compactCategories
            }
        }
        .listStyle(.insetGrouped)
        .listSearchable(text: $query, prompt: Text(L10n.settingssearchSearchPlaceholder))
        .navigationTitle(L10n.settingsMainHeaderTitle)
        .toolbar { addAccountToolbar }
    }

    private var compactCategories: some View {
        List {
            if !accounts.isEmpty {
                Section(L10n.accounts) {
                    ForEach(accounts, id: \.id) { account in
                        Button {
                            // While selecting, a tap toggles membership through the
                            // shared producer; otherwise it pushes the detail.
                            if account.selecting {
                                toggleAccount(account)
                            } else {
                                navigationModel.pushAccountDetail(accountId: account.id)
                            }
                        } label: {
                            compactAccountRow(account)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(account.selected ? .isSelected : [])
                        // A drill-down into the producer-driven nav stack; the Button
                        // already carries the button trait, made explicit here since the
                        // hand-drawn chevron isn't a system NavigationLink accessory.
                        .accessibilityAddTraits(.isButton)
                        .onLongPressGesture {
                            if !account.selecting { toggleAccount(account) }
                        }
                        .contextMenu {
                            if !account.selecting, account.toggleActionId != nil {
                                Button {
                                    toggleAccount(account)
                                } label: {
                                    Label(L10n.select, systemImage: "checkmark.circle")
                                }
                            }
                        }
                    }
                }
            }
            // Real grouped-list sections keyed off the catalog's `.section` markers,
            // so each settings group renders as a native section header rather than a
            // styled text row in the flat list.
            ForEach(SettingsView.groupedSettings(items)) { group in
                Section {
                    ForEach(group.items, id: \.id) { item in
                        NavigationLink {
                            SettingsSubroute(item: item)
                        } label: {
                            SettingsCategoryLabel(title: item.title, id: item.id)
                        }
                    }
                } header: {
                    if let title = group.title {
                        Text(title)
                    }
                }
            }
        }
    }

    private func compactAccountRow(_ account: AccountListItemSnapshot) -> some View {
        HStack(spacing: 12) {
            SettingsAccountLabel(account: account, selecting: account.selecting)
            Spacer(minLength: 8)
            if !account.selecting {
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
    }

    /// Toggles the account's selection membership through the shared producer.
    private func toggleAccount(_ account: AccountListItemSnapshot) {
        if let actionId = account.toggleActionId {
            accountsModel.invokeAccountListAction(id: actionId)
        }
    }
    #endif

    @ViewBuilder
    private var detailColumn: some View {
        #if os(macOS)
        if selectedId == Self.generalTag {
            GeneralSettingsView()
                .environment(\.settingsRevealRequest, revealRequest)
        } else {
            selectedDetailContent
        }
        #else
        selectedDetailContent
        #endif
    }

    @ViewBuilder
    private var selectedDetailContent: some View {
        if let tag = selectedId, Self.accountId(fromTag: tag) != nil {
            AccountDetailView()
        } else if let id = selectedId,
            let item = items.first(where: { $0.id == id && $0.kind == SettingsItemKind.action })
        {
            SettingsSubroute(item: item)
                .environment(\.settingsRevealRequest, revealRequest)
        } else {
            ContentUnavailableView {
                Label(L10n.settingsNoSelectionTitle, systemImage: "gearshape")
            } description: {
                Text(L10n.settingsNoSelectionText)
            }
        }
    }
}

struct SettingsSidebar: View {
    let accounts: [AccountListItemSnapshot]
    let items: [SettingsItemSnapshot]
    @Binding var selection: String?
    /// `true` while an account multi-selection is active; account rows then toggle
    /// membership on tap instead of driving the navigation `selection` tag.
    var selecting: Bool = false
    /// Forwards an account-list selection action (toggle / bulk) to the producer.
    var invokeAccountListAction: (String) -> Void = { _ in }

    private var groups: [SnapshotListSection<SettingsItemSnapshot>] { SettingsView.groupedSettings(items) }
    /// Catalog actions that appear before any `.section` marker — rendered next to
    /// the macOS-only "General" category in the leading header-less section.
    private var leadingGroup: SnapshotListSection<SettingsItemSnapshot>? {
        groups.first(where: { $0.id == .leading })
    }
    private var titledGroups: [SnapshotListSection<SettingsItemSnapshot>] {
        groups.filter { $0.id != .leading }
    }

    var body: some View {
        List(selection: $selection) {
            // Accounts as a real source-list section so SwiftUI renders the native
            // header treatment instead of a pseudo-header text row.
            if !accounts.isEmpty {
                Section(L10n.accounts) {
                    ForEach(accounts, id: \.id) { account in
                        accountRow(account)
                    }
                }
            }
            // The macOS-only "General" category sits in the leading, header-less
            // section alongside any catalog actions that precede a `.section` marker.
            #if os(macOS)
            Section {
                SettingsCategoryLabel(title: L10n.settingsGeneralHeaderTitle, id: SettingsView.generalTag)
                    .tag(SettingsView.generalTag)
                ForEach(leadingGroup?.items ?? [], id: \.id) { item in
                    row(item)
                }
            }
            ForEach(titledGroups) { group in
                Section(group.title ?? "") {
                    ForEach(group.items, id: \.id) { item in
                        row(item)
                    }
                }
            }
            #else
            ForEach(groups) { group in
                Section {
                    ForEach(group.items, id: \.id) { item in
                        row(item)
                    }
                } header: {
                    if let title = group.title {
                        Text(title)
                    }
                }
            }
            #endif
        }
        #if os(macOS)
        .listStyle(.sidebar)
        // AppKit can retain estimated single-line heights when the account
        // section arrives asynchronously. Rebuild its rows when membership changes.
        .id(accounts.map(\.id))
        #else
        .listStyle(.insetGrouped)
        #endif
    }

    @ViewBuilder
    private func accountRow(_ account: AccountListItemSnapshot) -> some View {
        let label = SettingsAccountLabel(account: account, selecting: selecting)

        if selecting {
            // No `.tag` while selecting: a tap toggles membership through the shared
            // producer rather than selecting the navigation row.
            Button {
                toggle(account)
            } label: {
                label.contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(account.selected ? .isSelected : [])
        } else {
            label
                .tag(SettingsView.accountTag(account.id))
                #if os(iOS)
            .onLongPressGesture {
                toggle(account)
            }
                #endif
                .contextMenu {
                    if account.toggleActionId != nil {
                        Button {
                            toggle(account)
                        } label: {
                            Label(L10n.select, systemImage: "checkmark.circle")
                        }
                    }
                }
        }
    }

    /// Toggles the account's selection membership through the shared producer.
    private func toggle(_ account: AccountListItemSnapshot) {
        if let actionId = account.toggleActionId {
            invokeAccountListAction(actionId)
        }
    }

    /// Renders a single selectable category row. Section grouping is now handled by
    /// `body` via real `Section`s, so only `.action` items reach here; a `.section`
    /// item (should one slip through) falls back to a quiet pseudo-header.
    @ViewBuilder
    private func row(_ item: SettingsItemSnapshot) -> some View {
        if item.kind == SettingsItemKind.section {
            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
        } else {
            SettingsCategoryLabel(title: item.title, id: item.id)
                .tag(item.id)
        }
    }
}

/// Maps a settings category's stable id to an SF Symbol. The shared snapshot
/// intentionally drops the Compose `ImageVector` icons (they don't translate to
/// SF Symbols), so the macOS-appropriate glyph is chosen here by id.
enum SettingsIcon {
    static func symbol(for id: String) -> String {
        switch id {
        case SettingsView.generalTag: return "gearshape.fill"
        case "subscription": return "star.circle"
        case "autofill": return "wand.and.stars"
        case "security": return "lock"
        case "automatic_backups": return "externaldrive.badge.timemachine"
        case "developer": return "chevron.left.forwardslash.chevron.right"
        case "watchtower": return "checkmark.shield"
        case "notifications": return "bell"
        case "display": return "paintpalette"
        case "debug": return "ladybug"
        case "about": return "info.circle"
        default: return "gearshape"
        }
    }

    /// Semantic system colors adapt to appearance and increased contrast. Keep
    /// these independent of the app accent so categories remain recognizable.
    static func color(for id: String) -> Color {
        switch id {
        case "subscription": return .purple
        case "autofill": return .blue
        case "security": return .gray
        case "automatic_backups": return .green
        case "developer": return .indigo
        case "watchtower": return .orange
        case "notifications": return .red
        case "display": return .indigo
        case "debug": return .brown
        case "about": return .blue
        default: return .gray
        }
    }
}

struct AccountSelectionBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let snapshot: AccountListSnapshot
    let invoke: (String) -> Void

    var body: some View {
        HStack(spacing: 10) {
            if let clearId = snapshot.selectionClearActionId {
                Button {
                    invoke(clearId)
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .touchTarget()
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(L10n.selectionClearAction)
                .accessibilityLabel(L10n.selectionClearAction)
                // Esc clears the selection on macOS, matching the native cancel idiom.
                #if os(macOS)
                .keyboardShortcut(.cancelAction)
                #endif
            }

            Text(L10n.selectionNSelected(Int(snapshot.selectionCount)))
                .font(.subheadline.weight(.medium))
                .contentTransition(reduceMotion ? .identity : .numericText())
                .animation(reduceMotion ? nil : .default, value: snapshot.selectionCount)

            if let selectAllId = snapshot.selectionSelectAllActionId {
                Button {
                    invoke(selectAllId)
                } label: {
                    Image(systemName: "checklist")
                        .font(.title3)
                        .touchTarget()
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(L10n.selectionSelectAllAction)
                .accessibilityLabel(L10n.selectionSelectAllAction)
                // Cmd-A selects all on macOS, the standard select-all shortcut.
                #if os(macOS)
                .keyboardShortcut("a", modifiers: .command)
                #endif
            }

            if let syncId = snapshot.selectionSyncActionId {
                Button {
                    invoke(syncId)
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.title3)
                        .touchTarget()
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(L10n.sync)
                .accessibilityLabel(L10n.sync)
            }

            if !snapshot.selectionActions.isEmpty {
                Menu {
                    ForEach(snapshot.selectionActions, id: \.id) { action in
                        Button(action.title) { invoke(action.id) }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .touchTarget()
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help(L10n.actions)
                .accessibilityLabel(L10n.selectionActionsTitle)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .contentShape(Capsule())
        .glassCapsule()
    }
}
