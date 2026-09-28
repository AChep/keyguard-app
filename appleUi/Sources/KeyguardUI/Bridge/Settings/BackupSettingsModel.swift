import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class BackupSettingsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Automatic Backups settings, produced by the shared Kotlin backups state
    /// producer running headless inside `KeyguardCore`. Only live while the
    /// Automatic Backups settings screen is on screen.
    private(set) var backupSettings: BackupSettingsSnapshot = BackupSettingsSnapshot.companion.empty

    @ObservationIgnored private var backupSettingsSubscription: BridgeObservation?

    func startBackupSettingsObservation() {
        startObservation(\.backupSettingsSubscription, into: \.backupSettings, observe: core.observeBackupSettings)
    }

    func stopBackupSettingsObservation() {
        stopObservation(
            \.backupSettingsSubscription, resetting: \.backupSettings, to: BackupSettingsSnapshot.companion.empty)
    }

    func setBackupIncludeAttachments(_ value: Bool) { core.setBackupIncludeAttachments(value: value) }

    func setBackupPassword(_ text: String) { core.setBackupPassword(text: text) }

    func setBackupStoreKind(_ kind: String) { core.setBackupStoreKind(kind: kind) }

    func setBackupStoreLocalPath(_ path: String) { core.setBackupStoreLocalPath(path: path) }

    func setBackupStoreWebDav(url: String, username: String, password: String) {
        core.setBackupStoreWebDav(url: url, username: username, password: password)
    }

    func pickBackupLocation() { core.pickBackupLocation() }

    func enableBackup() { core.enableBackup() }

    func beginBackupSetup() { core.beginBackupSetup() }

    func cancelBackupSetup() { core.cancelBackupSetup() }

    func restoreBackupSetupPassword() { core.restoreBackupSetupPassword() }

    func setBackupSetupRetention(_ maxSnapshots: Int32) { core.setBackupSetupRetention(maxSnapshots: maxSnapshots) }

    func triggerBackupNow() { core.triggerBackupNow() }

    func setBackupRetention(_ maxSnapshots: Int32) { core.setBackupRetention(maxSnapshots: maxSnapshots) }

    func disableBackup() { core.disableBackup() }
}
