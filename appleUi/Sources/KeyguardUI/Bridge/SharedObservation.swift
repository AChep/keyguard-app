import Foundation

/// Keeps one subscription alive until the last window releases it.
@MainActor
final class SharedObservation {
    private var consumers = 0
    private var observation: BridgeObservation?

    func acquire(_ subscribe: () -> BridgeObservation) {
        consumers += 1
        guard observation == nil else { return }
        observation = subscribe()
    }

    /// Retries the source without changing how many presentations still need it.
    func restart(_ subscribe: () -> BridgeObservation) {
        guard consumers > 0 else { return }
        observation?.cancel()
        observation = subscribe()
    }

    /// Returns true only when the caller should clear the shared snapshot.
    @discardableResult
    func release() -> Bool {
        guard consumers > 0 else { return false }
        consumers -= 1
        guard consumers == 0 else { return false }
        reset()
        return true
    }

    func reset() {
        consumers = 0
        observation?.cancel()
        observation = nil
    }
}
