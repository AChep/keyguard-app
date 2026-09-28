import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class SecuritySettingsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Security settings, produced by the shared Kotlin security settings use cases
    /// running inside `KeyguardCore`. Only live while the Security settings screen
    /// is on screen.
    private(set) var securitySettings: SecuritySettingsSnapshot = SecuritySettingsSnapshot.companion.empty

    @ObservationIgnored private var securitySettingsSubscription: BridgeObservation?

    func startSecuritySettingsObservation() {
        startObservation(
            \.securitySettingsSubscription, into: \.securitySettings, observe: core.observeSecuritySettings)
    }

    func stopSecuritySettingsObservation() {
        stopObservation(
            \.securitySettingsSubscription, resetting: \.securitySettings, to: SecuritySettingsSnapshot.companion.empty)
    }

    func setVaultPersist(_ value: Bool) { core.setVaultPersist(value: value) }

    func setVaultLockAfterReboot(_ value: Bool) { core.setVaultLockAfterReboot(value: value) }

    func setVaultLockTimeout(_ optionId: String) { core.setVaultLockTimeout(optionId: optionId) }

    func setClipboardAutoClear(_ optionId: String) { core.setClipboardAutoClear(optionId: optionId) }

    func setConcealFields(_ value: Bool) { core.setConcealFields(value: value) }

    func setWebsiteIcons(_ value: Bool) { core.setWebsiteIcons(value: value) }

    func setGravatar(_ value: Bool) { core.setGravatar(value: value) }

    /// Enabling shows the system Touch ID sheet before the setting persists;
    /// a cancelled prompt leaves the toggle off (the snapshot re-emits).
    func setBiometricUnlock(_ value: Bool) { core.setBiometricUnlock(value: value) }

    func setBiometricTimeout(_ optionId: String) { core.setBiometricTimeout(optionId: optionId) }

    func setFido2Unlock(_ value: Bool) { core.setFido2Unlock(value: value) }

    func setYubiKeyUnlock(_ value: Bool, slot: Int = 2, provision: Bool = false, overwrite: Bool = false) {
        core.setYubiKeyUnlock(value: value, slot: Int32(slot), provision: provision, overwrite: overwrite)
    }
    func inspectYubiKeySlot(_ slot: Int) async -> Bool? {
        await withCheckedContinuation { continuation in
            core.inspectYubiKeySlot(slot: Int32(slot)) { configured in
                continuation.resume(returning: configured?.boolValue)
            }
        }
    }
}
