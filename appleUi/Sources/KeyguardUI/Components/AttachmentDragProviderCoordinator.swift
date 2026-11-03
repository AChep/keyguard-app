import Foundation
import KeyguardShared
import UniformTypeIdentifiers

/// Recipient requests start preparation. Source-scope closure cancels unverified work;
/// published content remains independent until Kotlin retires it.
enum AttachmentDragProvider {
    static func make(preparer: any AttachmentDragPreparing) -> NSItemProvider {
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        let type = UTType(attachmentName: preparer.name)
        let provider = NSItemProvider()
        provider.suggestedName = preparer.name
        provider.registerDataRepresentation(forTypeIdentifier: type.identifier, visibility: .all) { completion in
            coordinator.load(completion: completion)
        }
        return provider
    }
}

/// Owns one gesture's offer. The only reusable payload is prepared content, never returned Data.
/// The timer bounds only unpublished preparation: Kotlin retires published content.
final class AttachmentDragProviderCoordinator: @unchecked Sendable {
    private enum State {
        case idle
        case offered(AttachmentDragOffer)
        case terminal
    }

    private let lock = NSLock()
    private let maximumRequests: Int
    private var state = State.idle
    private var preparer: (any AttachmentDragPreparing)?
    private var requests: [UUID: Request] = [:]
    private var timeout: Task<Void, Never>?

    init(preparer: any AttachmentDragPreparing, lifetime: Duration = .seconds(5 * 60), maximumRequests: Int = 8) {
        precondition(maximumRequests > 0)
        self.maximumRequests = maximumRequests
        self.preparer = preparer
        let expiry = Task { [weak self] in
            do { try await Task.sleep(for: lifetime) } catch { return }
            self?.close()
        }
        let cancel = lock.withLock {
            if case .terminal = state { return true }
            timeout = expiry
            return false
        }
        if cancel { expiry.cancel() }
    }

    deinit { close() }

    func load(completion: @escaping (Data?, (any Error)?) -> Void) -> Progress {
        let request = Request(completion: completion)
        request.progress.cancellationHandler = { [weak self, weak request] in
            guard let request else { return }
            request.cancellation.cancel()
            self?.removeCancelled(request.id)
            // Retire unpublished preparation before the completion can reenter this provider.
            request.finish(.cancelled)
        }

        let action: LoadAction = lock.withLock {
            guard !request.isFinished, requests.count < maximumRequests else { return .reject }
            switch state {
            case .idle:
                guard let preparer else { return .reject }
                // This transport admits no blocking readers, so the offer changes only when it retires.
                let offer = AttachmentDragOffer(promising: preparer.name) { [weak self] in self?.close() }
                state = .offered(offer)
                self.preparer = nil
                requests[request.id] = request
                return .prepare(offer, preparer)
            case .offered(let offer):
                requests[request.id] = request
                return .serve(offer)
            case .terminal:
                return .reject
            }
        }
        switch action {
        case .prepare(let offer, let preparer):
            serve(request, from: offer)
            offer.prepare(with: preparer)
        case .serve(let offer):
            serve(request, from: offer)
        case .reject:
            request.finish(.failed)
        }
        return request.progress
    }

    /// Retirement prevents further admissions; reader leases are released by their own workers.
    func close() {
        let retired = lock.withLock {
            let offer: AttachmentDragOffer?
            if case .offered(let offered) = state { offer = offered } else { offer = nil }
            state = .terminal
            preparer = nil
            let retired = (offer, Array(requests.values), timeout)
            requests.removeAll()
            timeout = nil
            return retired
        }
        retired.2?.cancel()
        retired.0?.retire()
        retired.1.forEach { $0.cancel() }
    }

    private enum LoadAction {
        case prepare(AttachmentDragOffer, any AttachmentDragPreparing)
        case serve(AttachmentDragOffer)
        case reject
    }

    private func serve(_ request: Request, from offer: AttachmentDragOffer) {
        offer.whenSettled { [weak self] content in
            guard let self else { return }
            if let content {
                self.read(content: content, request: request)
            } else {
                self.reject(request)
            }
        }
    }

    private func read(content: any AttachmentDragContent, request: Request) {
        let timer = lock.withLock {
            let timer = timeout
            timeout = nil
            return timer
        }
        timer?.cancel()
        // Keep this coordinator alive only for the active load. Blocking bridge I/O stays off MainActor.
        Task.detached(priority: .userInitiated) { [self] in
            if !request.isFinished {
                request.finish(content.load(cancellation: request.cancellation))
            }
            removeFinished(request.id)
        }
    }

    /// A failed preparation fails its requests; a closing provider cancels the ones it removed.
    private func reject(_ request: Request) {
        let pending = lock.withLock { requests.removeValue(forKey: request.id) != nil }
        if pending { request.finish(.failed) }
    }

    private func removeCancelled(_ id: UUID) {
        let offer: AttachmentDragOffer? = lock.withLock {
            // A cancelled worker still owns its admission until it releases the backing lease.
            guard case .offered(let offer) = state, offer.isPreparing else { return nil }
            requests[id] = nil
            guard requests.isEmpty else { return nil }
            state = .terminal
            return offer
        }
        offer?.retire()
    }

    private func removeFinished(_ id: UUID) {
        lock.withLock { requests[id] = nil }
    }

    /// A Foundation completion may be called from any worker, but must be consumed exactly once.
    private final class Request: @unchecked Sendable {
        let id = UUID()
        let progress = Progress(totalUnitCount: 1)
        // Kotlin's flag is atomic, so either side may read or set it from any thread.
        let cancellation = AttachmentDragReadCancellationSnapshot()
        private let lock = NSLock()
        private var completion: ((Data?, (any Error)?) -> Void)?

        init(completion: @escaping (Data?, (any Error)?) -> Void) {
            self.completion = completion
        }

        var isFinished: Bool { lock.withLock { completion == nil } }

        func cancel() {
            cancellation.cancel()
            finish(.cancelled)
        }

        func finish(_ result: AttachmentDragLoadResult) {
            let callback = lock.withLock {
                let callback = completion
                completion = nil
                return callback
            }
            guard let callback else { return }
            switch result {
            case .data(let data):
                progress.completedUnitCount = 1
                callback(data, nil)
            case .cancelled:
                callback(nil, CocoaError(.userCancelled))
            case .failed:
                callback(nil, CocoaError(.fileReadUnknown))
            }
        }
    }
}
