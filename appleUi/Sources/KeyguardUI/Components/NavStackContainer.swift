import SwiftUI
import KeyguardShared

struct NavStackContainer<Content: View>: View {
    @Environment(NavigationModel.self) private var navigationModel
    /// The section/tab scope this container renders ("vault", "watchtower", …). Each
    /// section observes its own stack, so drill-down survives section/tab switches.
    let scope: String
    var rootList: NavigationListKind? = nil
    var rootVaultList: VaultListSessionModel? = nil
    @ViewBuilder var content: () -> Content

    @State private var listSessions = NavigationListSessions()

    private var usesPanels: Bool { ListDetailNavigation.usesPanels }

    private var projection: ListDetailNavigation {
        ListDetailNavigation(
            root: rootList,
            entries: entries.map { .init(id: $0.instanceId, isVaultList: $0.kind == .vaultList) })
    }

    private var entries: [ScreenEntrySnapshot] { navigationModel.navStack(scope) }

    private var navPath: Binding<[Int64]> {
        Binding(
            get: { entries.map { $0.instanceId } },
            set: { newPath in
                guard newPath.count < entries.count else { return }
                navigationModel.popToScreen(scope: scope, instanceId: newPath.last)
            }
        )
    }

    var body: some View {
        ZStack {
            platformBody
        }
        .observing(
            start: { navigationModel.startNavScopeObservation(scope) },
            stop: { navigationModel.stopNavScopeObservation(scope) }
        )
        .onAppear { updateListSessions() }
        // Gated so only iPad containers observe their stack here.
        .onChange(of: usesPanels ? entries.map(\.instanceId) : []) { _, _ in updateListSessions() }
        .onDisappear { listSessions.stop() }
        .background { selectionWatcher }
    }

    @ViewBuilder
    private var selectionWatcher: some View {
        if usesPanels, projection.kind == .vault, let model = activeVaultList {
            VaultBrowseSelectionWatcher(model: model, context: listContext(for: projection.listEntryId))
                .id(projection.listEntryId)
        }
    }

    private var activeVaultList: VaultListSessionModel? {
        if let id = projection.listEntryId { return listSessions.models[id] }
        return rootVaultList
    }

    @ViewBuilder
    private var platformBody: some View {
        #if os(iOS)
        if usesPanels, projection.kind != nil {
            splitBody
        } else {
            stackBody
        }
        #else
        stackBody
        #endif
    }

    private var stackBody: some View {
        NavigationStack(path: navPath) {
            content()
                .navigationDestination(for: Int64.self) { id in
                    screenView(for: id)
                }
        }
    }

    private func updateListSessions() {
        if usesPanels { listSessions.update(entries: entries, scope: scope) }
    }

    private func listContext(for id: Int64?) -> NavigationListContext {
        let start = id.flatMap { id in entries.firstIndex { $0.instanceId == id }.map { $0 + 1 } } ?? 0
        return NavigationListContext(
            scope: scope, listEntryId: id,
            detail: entries.indices.contains(start) ? NavigationListContext.Detail(entries[start]) : nil,
            isActive: projection.listEntryId == id)
    }

    #if os(iOS)
    private var splitBody: some View {
        ListDetailNavigationView(
            projection: projection,
            popTo: { navigationModel.popToScreen(scope: scope, instanceId: $0) },
            clearDetail: { navigationModel.clearListDetail(listContext(for: projection.listEntryId)) },
            sidebar: { content().environment(\.navigationListContext, listContext(for: nil)) },
            sidebarDestination: { id in
                screenView(for: id).environment(\.navigationListContext, listContext(for: id))
            },
            detailDestination: { id in screenView(for: id) }
        )
    }
    #endif

    /// Renders a pushed navigation-stack entry by its instance id. Each entry reads
    /// its own inline snapshot and routes input by its instance id, so a folder-
    /// filtered list (or a second cipher detail) is fully independent of the root.
    @ViewBuilder
    private func screenView(for id: Int64) -> some View {
        if let entry = entries.first(where: { $0.instanceId == id }) {
            if entry.kind == ScreenEntryKind.cipherDetail {
                CipherDetailView(entry: entry)
            } else if entry.kind == ScreenEntryKind.vaultList {
                if usesPanels {
                    StackVaultListContent(model: listSessions.models[id])
                        .navigationTitle(entry.title.isEmpty ? L10n.homeVaultLabel : entry.title)
                } else {
                    StackVaultListView(entry: entry)
                }
            } else if entry.kind == ScreenEntryKind.serviceDirectoryList {
                StackServiceDirectoryView(entry: entry)
            } else if entry.kind == ScreenEntryKind.serviceDirectoryDetail {
                ServiceDirectoryDetailView(entry: entry)
            } else if entry.kind == ScreenEntryKind.watchtower {
                WatchtowerView(entry: entry)
            } else if entry.kind == ScreenEntryKind.watchtowerAlerts {
                WatchtowerAlertsView(entry: entry)
            } else if entry.kind == ScreenEntryKind.generatorHistory {
                GeneratorHistoryView()
            } else if entry.kind == ScreenEntryKind.emailRelayList {
                EmailForwardersView(entry: entry)
            } else if entry.kind == ScreenEntryKind.wordlistList {
                WordlistsView(entry: entry)
            } else if entry.kind == ScreenEntryKind.wordlistDetail {
                WordlistDetailView(entry: entry)
            } else if entry.kind == ScreenEntryKind.passwordHistory {
                VaultViewPasswordHistoryView(itemId: entry.passwordHistoryItemId ?? "")
            } else if entry.kind == ScreenEntryKind.sshAgentHistory {
                SshAgentHistoryView(cipherId: entry.sshAgentHistoryCipherId)
                    .navigationTitle(L10n.sshAgentHistoryHeaderTitle)
            } else if entry.kind == ScreenEntryKind.sendDetail {
                StackSendDetailView(
                    sendId: entry.sendId ?? "",
                    accountId: entry.sendAccountId ?? ""
                )
            } else if entry.kind == ScreenEntryKind.organizationsList {
                OrganizationsListView(entry: entry)
            } else if entry.kind == ScreenEntryKind.collectionsList {
                CollectionsListView(entry: entry)
            } else if entry.kind == ScreenEntryKind.foldersList {
                FoldersListView(entry: entry)
            } else if entry.kind == ScreenEntryKind.accountDetail {
                StackAccountDetailView(accountId: entry.accountDetailId ?? "")
            } else if entry.kind == ScreenEntryKind.equivalentDomains {
                EquivalentDomainsView(entry: entry)
            } else if entry.kind == ScreenEntryKind.duplicates {
                DuplicatesView(entry: entry)
            } else if entry.kind == ScreenEntryKind.downloads {
                DownloadsView(entry: entry)
            } else if entry.kind == ScreenEntryKind.cipherFiltersList {
                StackCipherFiltersView(entry: entry)
            } else if entry.kind == ScreenEntryKind.cipherFilterDetail {
                CipherFilterDetailView(entry: entry)
            } else if entry.kind == ScreenEntryKind.export_ {
                ExportView(entry: entry)
            } else if entry.kind == ScreenEntryKind.feedback {
                FeedbackView(entry: entry)
            } else if entry.kind == ScreenEntryKind.subscriptions {
                SubscriptionsSettingsView()
            }
        }
    }
}
