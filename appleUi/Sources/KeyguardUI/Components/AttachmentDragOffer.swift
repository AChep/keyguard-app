import Foundation

/// One gesture's prepared attachment, shared by every recipient of that gesture. It may start
/// on a promised name alone; recipients then wait until its preparation settles. Kotlin retires
/// handed-off content on expiry or vault lock, which retires the offer; admitted readers finish.
/// A transport that sees the delivery complete retires it sooner.
final class AttachmentDragOffer: @unchecked Sendable {
    let name: String
    private let condition = NSCondition()
    private var state: State
    private var readers = 0
    private var waiters: [@Sendable ((any AttachmentDragContent)?) -> Void] = []
    private let changed: @Sendable () -> Void

    private enum State {
        case preparing((any AttachmentDragPreparation)?)
        case ready(any AttachmentDragContent)
        case retired
    }

    /// `changed` reports a retirement or a finished reader, on any thread.
    init(content: any AttachmentDragContent, changed: @escaping @Sendable () -> Void) {
        name = content.name
        state = .ready(content)
        self.changed = changed
    }

    init(promising name: String, changed: @escaping @Sendable () -> Void) {
        self.name = name
        state = .preparing(nil)
        self.changed = changed
    }

    var isPreparing: Bool {
        condition.withLock {
            if case .preparing = state { return true }
            return false
        }
    }

    var isRetired: Bool {
        condition.withLock {
            if case .retired = state { return true }
            return false
        }
    }

    var hasReaders: Bool {
        condition.withLock { readers != 0 }
    }

    func prepare(with preparer: any AttachmentDragPreparing) {
        // prepare may complete synchronously. Never publish its returned handle after completion.
        let handle = preparer.prepare { [weak self] content in
            guard let self else {
                content?.close()
                return
            }
            self.fulfill(content)
        }
        let cancel = condition.withLock {
            switch state {
            case .preparing:
                state = .preparing(handle)
                return false
            case .ready:
                return false
            case .retired:
                return true
            }
        }
        if cancel { handle.cancel() }
    }

    /// Hands ready content to the OS, once per offer. From then on Kotlin's retirement retires
    /// this offer, at once if the content already expired.
    func handoff() {
        let content: (any AttachmentDragContent)? = condition.withLock {
            guard case .ready(let content) = state else { return nil }
            return content
        }
        guard let content else { return }
        // Never call into the content under the lock: its retirement retires this offer.
        content.handoff()
        content.onRetirement { [weak self] in self?.retire() }
    }

    /// Runs `action` with the content once preparation settles, or with nil once retired.
    func whenSettled(_ action: @escaping @Sendable ((any AttachmentDragContent)?) -> Void) {
        let settled: (any AttachmentDragContent)?? = condition.withLock {
            switch state {
            case .preparing:
                waiters.append(action)
                return .none
            case .ready(let content):
                return .some(content)
            case .retired:
                return .some(nil)
            }
        }
        if let settled { action(settled) }
    }

    /// Blocks a worker until preparation settles, then admits it as a reader.
    func admit() -> (any AttachmentDragContent)? {
        condition.lock()
        defer { condition.unlock() }
        while case .preparing = state { condition.wait() }
        guard case .ready(let content) = state else { return nil }
        readers += 1
        return content
    }

    func readerFinished() {
        condition.withLock { readers -= 1 }
        changed()
    }

    func retire() {
        let (previous, pending) = condition.withLock {
            let previous = state
            state = .retired
            condition.broadcast()
            defer { waiters = [] }
            return (previous, waiters)
        }
        switch previous {
        case .preparing(let handle): handle?.cancel()
        case .ready(let content): content.close()
        case .retired: return
        }
        // Waiters settle before the owner hears of the retirement.
        pending.forEach { $0(nil) }
        changed()
    }

    private func fulfill(_ content: (any AttachmentDragContent)?) {
        guard let content, content.isAvailable() else {
            content?.close()
            retire()
            return
        }
        let accepted = condition.withLock {
            guard case .preparing = state else { return false }
            state = .ready(content)
            condition.broadcast()
            return true
        }
        guard accepted else {
            content.close()
            return
        }
        handoff()
        let pending = condition.withLock {
            defer { waiters = [] }
            return waiters
        }
        pending.forEach { $0(content) }
    }
}
