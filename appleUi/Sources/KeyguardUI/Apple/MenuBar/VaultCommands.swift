#if os(macOS)
import SwiftUI
import KeyguardShared

/// The macOS "Vault" menu: the Unlock screen's escape-hatch actions (e.g. "Erase data")
/// while locked, Sync / Lock while unlocked. Sync / Lock are the menu-bar home of the
/// shared vault-/send-list `*.sync` / `*.lock` overflow actions, which the macOS list
/// toolbars drop (see `menuBarHoistedActionIds`) so they aren't duplicated.
///
/// The escape-hatch handler erases the vault with no built-in confirmation, so the
/// locked-state items do NOT invoke it directly: they raise the main window and arm
/// `VaultSessionModel.pendingUnlockAction`. The `.unlockActionConfirmation` applied by
/// `keyguardAppConfiguration` confirms it before calling `invokeUnlockAction`; a
/// `Commands` structure cannot host a `.confirmationDialog` itself.
public struct VaultCommands: Commands {
    public init(model: AppViewModel) {
        authModel = model.auth
        accountsModel = model.accounts
    }

    // `VaultSessionModel` is `@Observable`, so reading its `status` / `unlockActions`
    // here re-evaluates the menu when they change.
    private let authModel: VaultSessionModel
    private let accountsModel: AccountsModel
    @Environment(\.openWindow) private var openWindow

    public var body: some Commands {
        CommandMenu(L10n.homeVaultLabel) {
            switch authModel.status {
            case .locked:
                ForEach(authModel.unlockActions, id: \.id) { action in
                    Button(action.title) {
                        // Raise the main window first so the confirmation has a
                        // presenter even in menu-bar-only mode (window closed);
                        // when a window already exists this just focuses it.
                        DockIconMode.showMainWindow { openWindow(id: "main") }
                        authModel.pendingUnlockAction = action
                    }
                }
            case .unlocked:
                Button(L10n.vaultActionSyncVaultTitle) { accountsModel.syncVault() }
                Button(L10n.vaultActionLockVaultTitle) { authModel.lockVault() }
            case .loading, .needsCreate:
                // No vault actions to offer; the menu is present but empty.
                EmptyView()
            }
        }
    }
}
#endif
