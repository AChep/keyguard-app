#if os(macOS)
import SwiftUI
import KeyguardShared

/// A top-level "Vault" menu in the macOS menu bar. A thin renderer over the shared
/// `AppViewModel`: while locked it lists the Unlock screen's escape-hatch actions
/// (chiefly "Erase data", from the shared `unlockStateProducer`), and while unlocked
/// it offers "Sync vault" and "Lock vault". These two are the menu-bar home of the
/// shared vault-/send-list `*.sync` / `*.lock` overflow actions, which the macOS
/// list toolbars deliberately drop (see `menuBarHoistedActionIds`) so they aren't
/// duplicated. It duplicates no unlock logic — every item routes through the same
/// `AppViewModel` API the list actions use.
///
/// The escape-hatch handler erases the vault with no built-in confirmation, so these
/// items do NOT invoke it directly: they raise the main window and arm
/// `AppViewModel.pendingUnlockAction`, which the window root (`ContentView`) confirms
/// before calling `invokeUnlockAction` — a `Commands` structure cannot host a
/// `.confirmationDialog` itself.
public struct VaultCommands: Commands {
    public init() {
        let app = AppViewModel.shared
        authModel = app.auth
        accountsModel = app.accounts
    }

    // The shared singleton; because `AppViewModel` is `@Observable`, reading its
    // `status` / `unlockActions` here re-evaluates the menu when they change (and
    // macOS re-validates the menu on open), so the locked/unlocked items swap
    // automatically.
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
