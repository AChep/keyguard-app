import SwiftUI
import KeyguardShared

/// A vault list pushed onto the navigation stack, such as a folder- or
/// organization-filtered list. It uses the same virtualized row renderers as
/// the main list.
struct StackVaultListView: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(\.colorScheme) private var colorScheme
    let entry: ScreenEntrySnapshot

    /// Drives the renderers from the entry's Kotlin-owned session. Created lazily
    /// on first appear from `entry.vaultListSession` (always non-null for a
    /// VAULT_LIST entry — `startEntry` sets it before the first snapshot is emitted).
    @State private var model: VaultListSessionModel?
    @State private var selection = VaultSelectionModel()
    /// Local search typing buffer; the session's query stays the source of truth
    /// through the `bridgedText` reconciliation.
    @State private var query = ""

    /// The stacked list's interaction policy: tap opens the row, no multi-select,
    /// row context menus on, no iOS sync-status header, no quick-filter chips.
    private let config = VaultListConfig(
        rowTap: .open,
        supportsMultiSelect: false,
        contextMenu: true,
        syncHeader: false,
        quickFilters: false,
        usesNativeEmptyState: true
    )

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                LoadingIndicator()
            }
        }
        .navigationTitle(entry.title)
        .onAppear {
            if model == nil, let session = entry.vaultListSession {
                model = VaultListSessionModel(session: session)
            }
            model?.start()
        }
        .onDisappear { model?.stop() }
    }

    @ViewBuilder
    private func content(_ model: VaultListSessionModel) -> some View {
        Group {
            // An accountless session intentionally keeps `loaded` false.
            // Handle that terminal state before the loading gate, as the main list does.
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
