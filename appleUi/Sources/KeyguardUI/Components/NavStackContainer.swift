import SwiftUI
import KeyguardShared

struct NavStackContainer<Content: View>: View {
    @Environment(NavigationModel.self) private var navigationModel
    /// The section/tab scope this container renders ("vault", "watchtower", …). Each
    /// section observes its own stack, so drill-down survives section/tab switches.
    let scope: String
    @ViewBuilder var content: () -> Content

    private var entries: [ScreenEntrySnapshot] { navigationModel.navStack(scope) }

    private var navPath: Binding<[Int64]> {
        Binding(
            get: { entries.map { $0.instanceId } },
            set: { newPath in
                let pops = entries.count - newPath.count
                guard pops > 0 else { return }
                for _ in 0..<pops { navigationModel.popScreen(scope: scope) }
            }
        )
    }

    var body: some View {
        NavigationStack(path: navPath) {
            content()
                .navigationDestination(for: Int64.self) { id in
                    screenView(for: id)
                }
        }
        .observing(
            start: { navigationModel.startNavScopeObservation(scope) },
            stop: { navigationModel.stopNavScopeObservation(scope) }
        )
    }

    /// Renders a pushed navigation-stack entry by its instance id. Each entry reads
    /// its own inline snapshot and routes input by its instance id, so a folder-
    /// filtered list (or a second cipher detail) is fully independent of the root.
    @ViewBuilder
    private func screenView(for id: Int64) -> some View {
        if let entry = entries.first(where: { $0.instanceId == id }) {
            if entry.kind == ScreenEntryKind.cipherDetail {
                CipherDetailView(entry: entry)
            } else if entry.kind == ScreenEntryKind.vaultList {
                StackVaultListView(entry: entry)
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
