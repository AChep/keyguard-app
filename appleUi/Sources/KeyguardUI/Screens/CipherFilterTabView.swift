import SwiftUI
import KeyguardShared

struct CipherFilterTabView: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    @Environment(\.colorScheme) private var colorScheme
    let section: NavSection

    @State private var model: VaultListSessionModel?
    /// The renderers require a selection channel even though the filter tab is
    /// single-tap-to-open with no multi-select; on macOS the single-id selection
    /// becomes an `openVaultRow` (see `StackVaultListView`).
    @State private var selection = VaultSelectionModel()
    /// Local search typing buffer; the session's query stays the source of truth
    /// through the `bridgedText` reconciliation.
    @State private var query = ""

    /// The same interaction policy as a stacked filtered list: tap opens the
    /// row, no multi-select, row context menus on, no iOS sync-status header,
    /// no quick-filter chips (the tab IS a filter).
    private let config = VaultListConfig(
        rowTap: .open,
        supportsMultiSelect: false,
        contextMenu: true,
        syncHeader: false,
        quickFilters: false,
        usesNativeEmptyState: true
    )

    var body: some View {
        NavStackContainer(scope: section.scope) {
            Group {
                if let model {
                    content(model)
                } else {
                    LoadingIndicator()
                }
            }
            .navigationTitle(section.title)
            .onAppear {
                if model == nil {
                    model = VaultListSessionModel(
                        core: vaultActionsModel.keyguardCore,
                        config: .cipherFilter(id: section.filterId)
                    )
                }
                model?.start()
            }
            .onDisappear { model?.stop() }
        }
    }

    @ViewBuilder
    private func content(_ model: VaultListSessionModel) -> some View {
        Group {
            // Accountless sessions never set `loaded`; resolve that state before
            // showing the loading indicator, just like the main and stacked lists.
            if model.header.needsAccount {
                ContentUnavailableView {
                    Label(L10n.vaultMainEmptyTitle, systemImage: "tray")
                } description: {
                    Text(L10n.vaultMainEmptyAddAccountText)
                }
            } else if !model.header.loaded {
                LoadingIndicator()
            } else {
                VaultListRepresentable(
                    model: model,
                    selection: selection,
                    accountsModel: accountsModel,
                    config: config,
                    colorScheme: colorScheme,
                    opensOnSelection: true
                )
            }
        }
        .listSearchable(text: $query, prompt: Text(L10n.vaultMainSearchPlaceholder))
        .bridgedText(
            $query,
            remote: model.header.query,
            remoteRevision: model.header.queryRevision,
            send: { model.setQuery($0) }
        )
    }
}
