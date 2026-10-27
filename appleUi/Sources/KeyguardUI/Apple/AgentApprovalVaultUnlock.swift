#if os(macOS)
import SwiftUI
import KeyguardShared

/// Inline vault unlock of an approval prompt. Unlocking only unlocks the vault;
/// a separate click approves the operation. Renders nothing while unlocked.
struct AgentApprovalVaultUnlock: View {
    @Environment(VaultSessionModel.self) private var authModel

    var body: some View {
        if authModel.status == .locked {
            MasterPasswordView(mode: .unlock)
                .frame(height: 290)
        } else if authModel.status != .unlocked {
            ProgressView(L10n.vaultLoadingText)
        }
    }
}

#endif
