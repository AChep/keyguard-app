import Foundation
import KeyguardShared

/// Applies background vault-list deltas to a row store in FIFO order on the
/// main actor. Each run has its own store generation.
@MainActor
final class VaultDeltaPump {
    private let store: VaultRowStore

    private var continuation: AsyncStream<VaultDelta>.Continuation?
    private var task: Task<Void, Never>?

    init(store: VaultRowStore) {
        self.store = store
    }

    @discardableResult
    func start() -> AsyncStream<VaultDelta>.Continuation {
        // A fresh run gets a fresh generation: anything still in flight from a
        // previous run (converted but not yet applied) drops at the store.
        stop()
        let initialGeneration = store.generation

        // The one-hop FIFO apply pipeline. `AsyncStream` buffers in order
        // (unbounded — the source already coalesces bursts) and the single pump
        // task applies each delta atomically on the MainActor.
        let (stream, continuation) = AsyncStream.makeStream(of: VaultDelta.self)
        self.continuation = continuation
        task = Task { @MainActor [weak self] in
            var generation = initialGeneration
            for await delta in stream {
                guard let self, !Task.isCancelled else { return }
                let applied = self.store.apply(delta, generation: generation)
                if applied {
                    // An applied in-band isReset frame bumped the store's
                    // generation; re-capture so the run's next deltas pass.
                    generation = self.store.generation
                }
            }
        }
        return continuation
    }

    /// Ends the run: finishes the stream, cancels the apply task and
    /// re-baselines the store (the generation bump drops any queued delta).
    func stop() {
        continuation?.finish()
        continuation = nil
        task?.cancel()
        task = nil
        store.reset()
    }
}
