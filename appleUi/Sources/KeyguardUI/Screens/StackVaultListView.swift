import SwiftUI
import KeyguardShared

/// A vault list pushed onto the navigation stack, such as a folder- or organization-filtered list.
struct StackVaultListView: View {
    let entry: ScreenEntrySnapshot

    /// Created lazily on first appear; `entry.vaultListSession` is always non-null for a
    /// VAULT_LIST entry (`startEntry` sets it before the first snapshot is emitted).
    @State private var model: VaultListSessionModel?

    var body: some View {
        StackVaultListContent(model: model)
            .navigationTitle(entry.title.isEmpty ? L10n.homeVaultLabel : entry.title)
            .onAppear {
                if model == nil, let session = entry.vaultListSession {
                    model = VaultListSessionModel(session: session)
                }
                model?.start()
            }
            .onDisappear { model?.stop() }
    }
}

struct StackVaultListContent: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(\.colorScheme) private var colorScheme
    let model: VaultListSessionModel?

    /// The renderers require a selection channel even though these lists are
    /// single-tap-to-open with no multi-select; on macOS the single-id selection
    /// becomes an `openVaultRow`.
    @State private var selection = VaultSelectionModel()
    /// Local search typing buffer; the session's query stays the source of truth
    /// through the `bridgedText` reconciliation.
    @State private var query = ""

    var body: some View {
        if let model {
            content(model)
        } else {
            LoadingIndicator()
        }
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
                    config: .stacked,
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
