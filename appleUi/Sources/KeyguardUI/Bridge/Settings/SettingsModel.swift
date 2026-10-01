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

    private(set) var settings: SettingsListSnapshot = SettingsListSnapshot.companion.empty
    private(set) var searchIndex: SettingsSearchIndex?
    private(set) var searchLoadFailed = false

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

    /// Refresh localization and device capabilities on each visit to Settings.
    func loadSettings() async {
        searchLoadFailed = false
        let localization = AppLocalization.shared
        do {
            let snapshot = try await core.loadSettingsList()
            guard !Task.isCancelled else { return }
            settings = snapshot
            let index = try await core.loadSettingsSearch(
                categories: snapshot.items,
                biometricTitle: AppleBiometry.current.unlockTitle(bundle: localization.bundle),
                localeIdentifier: localization.locale.identifier
            )
            guard !Task.isCancelled else { return }
            searchIndex = index
        } catch {
            guard !Task.isCancelled else { return }
            searchIndex = nil
            searchLoadFailed = true
        }
    }
}
