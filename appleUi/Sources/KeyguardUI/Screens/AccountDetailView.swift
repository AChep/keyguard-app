import SwiftUI
import KeyguardShared

struct AccountDetailView: View {
    let detail: AccountDetailSnapshot
    let invoke: (String) -> Void

    var body: some View {
        Group {
            if detail.notFound {
                ContentUnavailableView {
                    Label(L10n.accountNotFoundTitle, systemImage: "person.crop.circle.badge.questionmark")
                }
            } else if !detail.loaded {
                LoadingIndicator()
            } else {
                accountForm
            }
        }
        #if os(iOS)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        #else
        .navigationTitle(detail.title.isEmpty ? L10n.account : detail.title)
        #endif
        .keepScreenAwake()
    }

    @ViewBuilder
    private var accountForm: some View {
        #if os(iOS)
        DetailForm(items: detail.items, invoke: invoke) {
            DetailIdentityHeader(
                title: detail.title.isEmpty ? L10n.account : detail.title,
                subtitle: detail.host
            ) { size in
                Image(systemName: "person.crop.circle")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.tint)
                    .frame(width: size, height: size)
                    .accessibilityHidden(true)
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                headerActions
            }
        }
        #else
        DetailScaffold(
            title: detail.title.isEmpty ? L10n.account : detail.title,
            subtitle: detail.host,
            items: detail.items,
            invoke: invoke
        ) {
            DetailHeaderSymbol(systemName: "person.crop.circle")
        } actions: {
            headerActions
        }
        #endif
    }

    @ViewBuilder
    private var headerActions: some View {
        // The web vault is for a Bitwarden account; the local vault reveals a
        // KeePass account's database file.
        if let actionId = detail.openWebVaultActionId {
            Button {
                invoke(actionId)
            } label: {
                Label(L10n.webVault, systemImage: "safari")
            }
            .help(L10n.launchWebVault)
        }
        if let actionId = detail.openLocalVaultActionId {
            Button {
                invoke(actionId)
            } label: {
                Label(L10n.localVault, systemImage: "folder")
            }
            .help(L10n.localVault)
        }
        if !detail.actions.isEmpty {
            Menu {
                listActionMenuItems(actions: detail.actions) {
                    invoke($0)
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .touchTarget()
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .accessibilityLabel(L10n.accountActionsTitle)
            .help(L10n.accountActionsTitle)
        }
    }
}
