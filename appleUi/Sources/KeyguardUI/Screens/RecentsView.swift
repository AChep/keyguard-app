import SwiftUI
import KeyguardShared

struct RecentsView: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    /// The focused list model, created on appear so its `onCopy` / `onReveal`
    /// closures can capture the live action models + `dismiss`.
    @State private var listModel: RecentsListModel?
    /// Required by the shared renderers; unused here (Recents has no multi-select).
    @State private var selection = VaultSelectionModel()

    private static let listConfig = VaultListConfig(
        rowTap: .copyPrimary,
        supportsMultiSelect: false,
        contextMenu: true,
        syncHeader: false,
        quickFilters: false
    )

    var body: some View {
        ModalSheet(title: L10n.vaultRecentsTitle, width: 380, height: 520) {
            // The sheet only exists inside the unlocked shell, but the vault can
            // lock from under it (auto-lock); fall back gracefully until the
            // status change tears the hierarchy down.
            switch authModel.status {
            case .unlocked:
                unlockedContent
            case .locked, .needsCreate:
                lockedState
            case .loading:
                LoadingIndicator()
            }
        }
        .localElevatedAccessHost(.recents)
        .onAppear {
            guard listModel == nil else { return }
            let lm = RecentsListModel(
                core: vaultActionsModel.keyguardCore,
                onCopy: { secretId, accountId, field in
                    vaultActionsModel.copyCipherField(secretId: secretId, accountId: accountId, field: field)
                },
                onReveal: { secretId in
                    navigationModel.pendingRevealSecretId = secretId
                    dismiss()
                }
            )
            lm.start()
            listModel = lm
        }
        .onDisappear {
            listModel?.stop()
            listModel = nil
        }
    }

    @ViewBuilder
    private var unlockedContent: some View {
        if let listModel {
            VStack(spacing: 0) {
                if !listModel.tabs.isEmpty {
                    // Tab titles come pre-localized from shared Kotlin.
                    Picker("", selection: tabBinding(listModel)) {
                        ForEach(listModel.tabs, id: \.key) { tab in
                            Text(tab.title).tag(tab.key)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    // The empty title + labelsHidden leaves VoiceOver with no name for
                    // the control itself; give it one while keeping the compact layout.
                    .accessibilityLabel(L10n.vaultRecentsTitle)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    Divider()
                }
                list(listModel)
            }
        } else {
            LoadingIndicator()
        }
    }

    @ViewBuilder
    private func list(_ listModel: RecentsListModel) -> some View {
        if !listModel.loaded {
            LoadingIndicator()
        } else if listModel.store.structure.entries.isEmpty {
            ContentUnavailableView(
                L10n.vaultRecentsEmptyTitle,
                systemImage: "clock",
                description: Text(L10n.vaultRecentsEmptyText)
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VaultListRepresentable(
                model: listModel,
                selection: selection,
                accountsModel: accountsModel,
                config: Self.listConfig,
                colorScheme: colorScheme,
                extendsUnderChrome: false
            )
        }
    }

    private var lockedState: some View {
        ContentUnavailableView {
            Label(L10n.agentHistoryResponseVaultLocked, systemImage: "lock.fill")
        } description: {
            Text(L10n.vaultRecentsLockedText)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func tabBinding(_ listModel: RecentsListModel) -> Binding<String> {
        Binding(
            get: { listModel.selectedTabKey },
            set: { listModel.setTab(key: $0) }
        )
    }
}
