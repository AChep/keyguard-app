import Foundation
import Observation
import KeyguardShared

/// The devices of one sync group. Removing and leaving ask first through the
/// app dialogs; adding a device pushes its own screen.
@MainActor
@Observable
final class KeyguardDevicesModel: SnapshotObserving {
    private let makeSource: () -> any KeyguardDevicesSource
    @ObservationIgnored private var source: (any KeyguardDevicesSource)?
    @ObservationIgnored private var subscription: BridgeObservation?

    init(makeSource: @escaping () -> any KeyguardDevicesSource) {
        self.makeSource = makeSource
    }

    private(set) var snapshot = KeyguardDevicesSnapshot.companion.empty

    /// Call when the screen appears; balance with `stop()`.
    func start() {
        guard source == nil else { return }
        let source = makeSource()
        self.source = source
        startObservation(\.subscription, into: \.snapshot) { source.subscribe(onChange: $0) }
    }

    func stop() {
        stopObservation(\.subscription, resetting: \.snapshot, to: KeyguardDevicesSnapshot.companion.empty)
        source?.close()
        source = nil
    }

    func remove(id: String) {
        source?.remove(id: id)
    }

    func leave() {
        source?.leave()
    }
}
