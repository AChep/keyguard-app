import SwiftUI
import KeyguardShared

struct MasterPasswordView: View {
    enum Mode: Hashable {
        case create
        case unlock

        var title: String {
            switch self {
            case .create: return L10n.setupButtonCreateVault
            case .unlock: return L10n.unlockBiometricAuthConfirmTitle
            }
        }

        var subtitle: String {
            switch self {
            case .create: return L10n.setupHeaderText
            case .unlock: return L10n.unlockHeaderText
            }
        }

        var actionTitle: String {
            switch self {
            case .create: return L10n.setupButtonCreateVault
            case .unlock: return L10n.unlockButtonUnlock
            }
        }
    }

    let mode: Mode

    @Environment(VaultSessionModel.self) private var authModel

    var body: some View {
        MasterPasswordForm(makeSession: mode == .create ? authModel.makeSetupSession : authModel.makeUnlockSession) {
            snapshot, actions in
            MasterPasswordContent(mode: mode, snapshot: snapshot, actions: actions)
        }
        .id(mode)
    }
}
