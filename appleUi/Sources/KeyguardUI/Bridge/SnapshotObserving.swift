import Foundation
import KeyguardShared

/// Shared observation mechanics; state and lifetime policy belong to each feature.
@MainActor
protocol SnapshotObserving: AnyObject {}

extension SnapshotObserving {
    func startObservation<Snapshot>(
        _ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>,
        into property: ReferenceWritableKeyPath<Self, Snapshot>,
        observe: (@escaping (Snapshot) -> Void) -> KeyguardCancellable
    ) {
        startObservation(handle, into: property) { callback in
            BridgeObservation(observe(callback))
        }
    }

    /// The closure boundary also permits lifecycle tests without constructing a vault.
    func startObservation<Snapshot>(
        _ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>,
        into property: ReferenceWritableKeyPath<Self, Snapshot>,
        subscribe: (@escaping (Snapshot) -> Void) -> BridgeObservation
    ) {
        guard self[keyPath: handle] == nil else { return }
        let generation = UUID()
        let subscription = subscribe { [weak self] snapshot in
            Task { @MainActor [weak self] in
                guard let self,
                    let active = self[keyPath: handle],
                    active.id == generation, !active.isCancelled
                else { return }
                self[keyPath: property] = snapshot
            }
        }
        self[keyPath: handle] = BridgeObservation(id: generation, cancel: { subscription.cancel() })
    }

    func stopObservation<Snapshot>(
        _ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>,
        resetting property: ReferenceWritableKeyPath<Self, Snapshot>,
        to empty: Snapshot
    ) {
        self[keyPath: handle]?.cancel()
        self[keyPath: handle] = nil
        self[keyPath: property] = empty
    }

    func assignIfChanged<Value: Equatable>(_ storage: inout Value, _ value: Value) {
        if storage != value { storage = value }
    }
}
