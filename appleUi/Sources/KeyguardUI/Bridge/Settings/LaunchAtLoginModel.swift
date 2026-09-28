import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class LaunchAtLoginModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// macOS launch-at-login state, produced by the shared Kotlin
    /// `GetLaunchAtLogin` use case (backed by `SMAppService`) running inside
    /// `KeyguardCore`. Only live while the General settings screen is on screen.
    private(set) var launchAtLogin: LaunchAtLoginSnapshot = LaunchAtLoginSnapshot.companion.empty

    @ObservationIgnored private var launchAtLoginSubscription: BridgeObservation?

    /// Starts observing the launch-at-login registration. Call when the General
    /// settings screen appears; balance with `stopLaunchAtLoginObservation()`.
    func startLaunchAtLoginObservation() {
        startObservation(\.launchAtLoginSubscription, into: \.launchAtLogin, observe: core.observeLaunchAtLogin)
    }

    func stopLaunchAtLoginObservation() {
        stopObservation(
            \.launchAtLoginSubscription, resetting: \.launchAtLogin, to: LaunchAtLoginSnapshot.companion.empty)
    }

    /// Registers / unregisters the app as a login item.
    func setLaunchAtLogin(_ enabled: Bool) {
        core.setLaunchAtLogin(enabled: enabled)
    }

    /// Opens System Settings ▸ Login Items (requires-approval case).
    func openLoginItemsSettings() {
        core.openLoginItemsSettings()
    }
}
