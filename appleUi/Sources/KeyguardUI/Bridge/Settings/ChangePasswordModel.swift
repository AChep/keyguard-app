import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class ChangePasswordModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Change-master-password sheet state, produced by the shared Kotlin
    /// change-password producer running headless inside `KeyguardCore`. Only live
    /// while the change-password sheet is presented.
    private(set) var changePassword: ChangePasswordSnapshot = ChangePasswordSnapshot.companion.empty

    @ObservationIgnored private var changePasswordSubscription: BridgeObservation?

    /// Starts the headless change-password producer. `onClose` fires when the
    /// producer signals success (it pops its own screen), so the sheet can dismiss.
    func startChangePasswordObservation(onClose: @escaping () -> Void) {
        startObservation(\.changePasswordSubscription) { deliver in
            BridgeObservation(
                core.observeChangePassword(
                    onChange: { snapshot in
                        deliver { $0.changePassword = snapshot }
                    },
                    onClose: {
                        deliver { _ in onClose() }
                    }))
        }
    }

    func stopChangePasswordObservation() {
        stopObservation(
            \.changePasswordSubscription, resetting: \.changePassword, to: ChangePasswordSnapshot.companion.empty)
    }

    func setChangePasswordCurrent(_ text: String) { core.setChangePasswordCurrent(text: text) }

    func setChangePasswordNew(_ text: String) { core.setChangePasswordNew(text: text) }

    func setChangePasswordBiometric(_ enabled: Bool) { core.setChangePasswordBiometric(enabled: enabled) }

    func submitChangePassword() { core.submitChangePassword() }
}
