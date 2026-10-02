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

    /// Only live while the Automatic Backups settings screen is on screen.
    private(set) var backupSettings: BackupSettingsSnapshot = BackupSettingsSnapshot.companion.empty

    @ObservationIgnored private let backupSettingsObservation = SharedObservation()

    @ObservationIgnored private var backupSettingsSubscription: BridgeObservation?

    func startBackupSettingsObservation() {
        backupSettingsObservation.acquire(makeBackupSettingsObservation)
    }

    func stopBackupSettingsObservation() {
        backupSettingsObservation.release()
    }

    func retryBackupSettingsObservation() {
        guard backupSettings.initializationFailed else { return }
        backupSettingsObservation.restart(makeBackupSettingsObservation)
    }

    private func makeBackupSettingsObservation() -> BridgeObservation {
        sharedSnapshotObservation(
            \.backupSettingsSubscription, into: \.backupSettings, empty: BackupSettingsSnapshot.companion.empty,
            observe: core.observeBackupSettings)
    }

    func makeBackupSetupSession() -> BackupSetupSession { core.makeBackupSetupSession() }

    func isValidBackupWebDavURL(_ url: String) -> Bool { core.isValidBackupWebDavUrl(url: url) }

    func triggerBackupNow() { core.triggerBackupNow() }

    func setBackupRetention(_ maxSnapshots: Int32) { core.setBackupRetention(maxSnapshots: maxSnapshots) }

    func disableBackup() { core.disableBackup() }
}
