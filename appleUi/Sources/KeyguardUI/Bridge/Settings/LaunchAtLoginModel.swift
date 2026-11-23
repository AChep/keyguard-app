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

    /// macOS only (backed by `SMAppService`). Only live while a launch-at-login
    /// section is on screen.
    private(set) var launchAtLogin: LaunchAtLoginSnapshot = LaunchAtLoginSnapshot.companion.empty

    @ObservationIgnored private let launchAtLoginObservation = SharedObservation()

    @ObservationIgnored private var launchAtLoginSubscription: BridgeObservation?

    /// Call when a launch-at-login section appears; balance with
    /// `stopLaunchAtLoginObservation()`.
    func startLaunchAtLoginObservation() {
        launchAtLoginObservation.acquire {
            sharedSnapshotObservation(
                \.launchAtLoginSubscription, into: \.launchAtLogin, empty: LaunchAtLoginSnapshot.companion.empty,
                observe: core.observeLaunchAtLogin)
        }
    }

    func stopLaunchAtLoginObservation() {
        launchAtLoginObservation.release()
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        core.setLaunchAtLogin(enabled: enabled)
    }

    func refresh() {
        core.refreshLaunchAtLogin()
    }

    /// Opens System Settings ▸ Login Items (requires-approval case).
    func openLoginItemsSettings() {
        core.openLoginItemsSettings()
    }
}
