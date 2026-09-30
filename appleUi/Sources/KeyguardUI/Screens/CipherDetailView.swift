import SwiftUI
import KeyguardShared

struct CipherDetailView: View {
    @Environment(CipherDetailModel.self) private var cipherDetailModel
    @Environment(NavigationModel.self) private var navigationModel

    /// When non-nil this view renders a *stacked* detail instance (a navigation-stack
    /// entry) instead of the single-slot root detail, routing its actions by the
    /// entry's instance id. Defaults to the root detail (`nil`).
    var entry: ScreenEntrySnapshot? = nil

    private var detail: VaultDetailSnapshot { entry?.detail ?? cipherDetailModel.detail }

    private func invoke(_ id: String) {
        if let entry {
            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: id)
        } else {
            cipherDetailModel.invokeVaultAction(id: id)
        }
    }

    private func toggleFavorite() {
        if let entry {
            navigationModel.toggleEntryFavorite(instanceId: entry.instanceId)
        } else {
            cipherDetailModel.toggleVaultFavorite()
        }
    }

    var body: some View {
        #if os(macOS)
        if entry != nil {
            detailContent
                .navigationTitle(detail.title.isEmpty ? L10n.credentialExchangeImportUntitled : detail.title)
        } else {
            detailContent
        }
        #else
        detailContent
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var detailContent: some View {
        Group {
            if detail.notFound {
                ContentUnavailableView {
                    Label(L10n.itemNotFound, systemImage: "questionmark.folder")
                }
            } else if detail.isLoading {
                LoadingIndicator()
            } else {
                content
            }
        }
        .keepScreenAwake()
    }

    private var content: some View {
        DetailForm(items: detail.items, invoke: invoke) {
            DetailIdentityHeader(title: detail.title) { size in
                FaviconView(
                    url: detail.iconUrl,
                    placeholder: detail.iconPlaceholder,
                    fallbackSymbol: typeSymbol(detail.typeIcon),
                    size: size
                )
            }
        }
        .environment(\.detailTotpProvider, totpProvider)
        .toolbar { detailToolbar }
    }

    /// Reads the live TOTP badges from their separate per-second channel.
    private var totpProvider: @MainActor @Sendable (String) -> TotpFieldSnapshot? {
        let cipherId = detail.cipherId
        if entry != nil {
            let navigationModel = navigationModel
            return { rowId in navigationModel.entryTotp[cipherId]?.states[rowId] }
        } else {
            let cipherDetailModel = cipherDetailModel
            return { rowId in cipherDetailModel.totpState(cipherId: cipherId, rowId: rowId) }
        }
    }

    private var actionPlacement: ToolbarItemPlacement {
        #if os(macOS)
        .primaryAction
        #else
        .topBarTrailing
        #endif
    }

    @ToolbarContentBuilder
    private var detailToolbar: some ToolbarContent {
        #if os(macOS)
        if #available(macOS 26.0, *) {
            ToolbarSpacer(.fixed, placement: .primaryAction)
        }
        #endif
        ToolbarItem(id: "vault.item.favorite", placement: actionPlacement) {
            Button(
                favoriteTitle,
                systemImage: detail.favorite ? "star.fill" : "star",
                action: toggleFavorite
            )
            .help(favoriteTitle)
            .accessibilityAddTraits(detail.favorite ? .isSelected : [])
        }
        if let editActionId = detail.editActionId {
            ToolbarItem(id: "vault.item.edit", placement: actionPlacement) {
                Button(L10n.edit, systemImage: "pencil") {
                    invoke(editActionId)
                }
                .labelStyle(.iconOnly)
                .help(L10n.edit)
            }
        }
        if !detail.actions.isEmpty {
            ToolbarItem(id: "vault.item.more", placement: actionPlacement) {
                Menu(L10n.moreActions, systemImage: "ellipsis") {
                    listActionMenuItems(actions: detail.actions) { invoke($0) }
                }
                .help(L10n.moreActions)
            }
        }
    }

    private var favoriteTitle: String {
        detail.favorite ? L10n.ciphersActionRemoveFromFavoritesTitle : L10n.ciphersActionAddToFavoritesTitle
    }

    private func typeSymbol(_ icon: String) -> String {
        switch icon {
        case "Login": return "person.badge.key"
        case "Card": return "creditcard"
        case "Identity": return "person.text.rectangle"
        case "SecureNote": return "note.text"
        case "SshKey": return "terminal"
        case "GpgKey": return "key.horizontal"
        default: return "key"
        }
    }
}
