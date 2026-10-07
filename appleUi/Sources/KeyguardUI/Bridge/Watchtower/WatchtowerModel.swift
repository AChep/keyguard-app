import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class WatchtowerModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    func makeSession() -> WatchtowerSession { core.makeWatchtowerSession() }

    /// Only live while the Watchtower settings screen is on screen.
    private(set) var watchtowerSettings: WatchtowerSettingsSnapshot = WatchtowerSettingsSnapshot.companion.empty

    @ObservationIgnored private let watchtowerSettingsObservation = SharedObservation()

    @ObservationIgnored private var watchtowerSettingsSubscription: BridgeObservation?

    /// Call when the Watchtower settings screen appears; balance with
    /// `stopWatchtowerSettingsObservation()`.
    func startWatchtowerSettingsObservation() {
        watchtowerSettingsObservation.acquire {
            sharedSnapshotObservation(
                \.watchtowerSettingsSubscription, into: \.watchtowerSettings,
                empty: WatchtowerSettingsSnapshot.companion.empty,
                observe: core.observeWatchtowerSettings)
        }
    }

    func stopWatchtowerSettingsObservation() {
        watchtowerSettingsObservation.release()
    }

    func setCheckPwnedPasswords(_ value: Bool) { core.setCheckPwnedPasswords(value: value) }

    func setCheckPwnedServices(_ value: Bool) { core.setCheckPwnedServices(value: value) }

    func setCheckTwoFa(_ value: Bool) { core.setCheckTwoFa(value: value) }

    func setCheckPasskeys(_ value: Bool) { core.setCheckPasskeys(value: value) }

    func isValidHibpApiToken(_ token: String) -> Bool { core.isValidHibpApiToken(token: token) }

    func setHibpApiToken(_ token: String) -> Bool {
        guard isValidHibpApiToken(token) else { return false }
        core.setHibpApiToken(token: token)
        return true
    }

}
