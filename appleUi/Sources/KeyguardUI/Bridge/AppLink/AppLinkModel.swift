import Foundation
import Observation
import KeyguardShared

/// The linked devices settings: whether this app is linked to the apps on the user's
/// other devices, this device, and what it syncs with them.
@MainActor
@Observable
final class AppLinkModel: SnapshotObserving {
    private let makeSource: () -> any AppLinkSource
    @ObservationIgnored private var source: (any AppLinkSource)?
    @ObservationIgnored private var subscription: BridgeObservation?

    init(makeSource: @escaping () -> any AppLinkSource) {
        self.makeSource = makeSource
    }

    private(set) var snapshot = AppLinkSnapshot.companion.empty

    /// Call when the screen appears; balance with `stop()`.
    func start() {
        guard source == nil else { return }
        let source = makeSource()
        self.source = source
        startObservation(\.subscription, into: \.snapshot) { source.subscribe(onChange: $0) }
    }

    func stop() {
        stopObservation(\.subscription, resetting: \.snapshot, to: AppLinkSnapshot.companion.empty)
        source?.close()
        source = nil
    }

    /// Turns syncing the category `key` on this device on or off.
    func setCategory(key: String, value: Bool) {
        source?.setCategory(key: key, value: value)
    }

    /// Asks for a new name of this device, through the app dialogs.
    func rename() {
        source?.rename()
    }
}
