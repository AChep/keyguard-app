import Foundation
import KeyguardShared

/// Shared observation mechanics; state and lifetime policy belong to each feature.
///
/// Assign observed values directly: the `@Observable` setter already skips
/// notifying for an equal value, while an `inout` pass always notifies.
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
        startObservation(handle) { deliver in
            subscribe { snapshot in
                deliver { $0[keyPath: property] = snapshot }
            }
        }
    }

    /// `deliver` may be called from any thread. It applies an update on the main
    /// actor only while this subscription is still the active one, so callbacks
    /// queued before a stop or a restart are dropped.
    func startObservation(
        _ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>,
        subscribe: (_ deliver: @escaping (_ update: @escaping @MainActor (Self) -> Void) -> Void) -> BridgeObservation
    ) {
        guard self[keyPath: handle] == nil else { return }
        let generation = UUID()
        let subscription = subscribe { [weak self] update in
            Task { @MainActor [weak self] in
                guard let self,
                    let active = self[keyPath: handle],
                    active.id == generation, !active.isCancelled
                else { return }
                update(self)
            }
        }
        self[keyPath: handle] = BridgeObservation(id: generation, cancel: { subscription.cancel() })
    }

    func stopObservation(_ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>) {
        self[keyPath: handle]?.cancel()
        self[keyPath: handle] = nil
    }

    func stopObservation<Snapshot>(
        _ handle: ReferenceWritableKeyPath<Self, BridgeObservation?>,
        resetting property: ReferenceWritableKeyPath<Self, Snapshot>,
        to empty: Snapshot
    ) {
        stopObservation(handle)
        self[keyPath: property] = empty
    }
}
