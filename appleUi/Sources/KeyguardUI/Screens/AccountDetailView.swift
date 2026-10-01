import SwiftUI
import KeyguardShared

struct AccountDetailView: View {
    @Environment(AccountsModel.self) private var accountsModel

    private var detail: AccountDetailSnapshot { accountsModel.accountDetail }

    var body: some View {
        Group {
            if detail.notFound {
                ContentUnavailableView {
                    Label(L10n.accountNotFoundTitle, systemImage: "person.crop.circle.badge.questionmark")
                }
            } else if !detail.loaded {
                LoadingIndicator()
            } else {
                content
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

    private var content: some View {
        accountForm
            // Local reveal state belongs to the entity delivered with this snapshot.
            .id(accountsModel.accountDetailIdentity)
    }

    @ViewBuilder
    private var accountForm: some View {
        #if os(iOS)
        DetailForm(items: detail.items, invoke: { accountsModel.invokeAccountAction(id: $0) }) {
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
            invoke: { accountsModel.invokeAccountAction(id: $0) }
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
                accountsModel.invokeAccountAction(id: actionId)
            } label: {
                Label(L10n.webVault, systemImage: "safari")
            }
            .help(L10n.launchWebVault)
        }
        if let actionId = detail.openLocalVaultActionId {
            Button {
                accountsModel.invokeAccountAction(id: actionId)
            } label: {
                Label(L10n.localVault, systemImage: "folder")
            }
            .help(L10n.localVault)
        }
        if !detail.actions.isEmpty {
            Menu {
                listActionMenuItems(actions: detail.actions) {
                    accountsModel.invokeAccountAction(id: $0)
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

struct StackAccountDetailView: View {
    @Environment(AccountsModel.self) private var accountsModel

    let accountId: String

    var body: some View {
        AccountDetailView()
            .observing(
                start: { accountsModel.startAccountDetailObservation(accountId: accountId) },
                stop: { accountsModel.stopAccountDetailObservation() }
            )
    }
}
