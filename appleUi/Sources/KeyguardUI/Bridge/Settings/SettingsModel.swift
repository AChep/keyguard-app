import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class SettingsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// The settings categories list, projected from the shared Kotlin settings
    /// catalog by `KeyguardCore.loadSettingsList`. Static for a given build, so
    /// it is loaded once (one-shot) rather than observed.
    private(set) var settings: SettingsListSnapshot = SettingsListSnapshot.companion.empty

    private(set) var debugSettings: DebugSettingsSnapshot = DebugSettingsSnapshot.companion.empty

    @ObservationIgnored private var debugSettingsSubscription: BridgeObservation?

    func startDebugSettingsObservation() {
        startObservation(\.debugSettingsSubscription, into: \.debugSettings, observe: core.observeDebugSettings)
    }

    func stopDebugSettingsObservation() {
        stopObservation(
            \.debugSettingsSubscription, resetting: \.debugSettings, to: DebugSettingsSnapshot.companion.empty)
    }

    func setDebugPremium(_ enabled: Bool) {
        core.setDebugPremium(enabled: enabled)
    }

    /// Loads the settings categories from the shared catalog. Idempotent — the
    /// list is constant for a build, so it is only computed once.
    func loadSettings() async {
        guard settings.items.isEmpty else { return }
        if let snapshot = try? await core.loadSettingsList() {
            settings = snapshot
        }
    }
}
