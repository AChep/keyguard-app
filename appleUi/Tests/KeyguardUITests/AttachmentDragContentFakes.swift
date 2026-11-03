import Foundation
import KeyguardShared
@testable import KeyguardUI

/// Retires like Kotlin's content: `expire()` stands in for expiry or a vault lock.
final class AttachmentDragTestContent: AttachmentDragContent, @unchecked Sendable {
    let name: String
    private let data: Data
    private let writeError: CocoaError?
    private let beforeRead: @Sendable () -> Void
    private let lock = NSLock()
    private var available = true
    private var retirementActions: [@Sendable () -> Void] = []
    private var reads = 0
    private var writes = 0
    private var closes = 0
    private var handoffs = 0
    private var readOnMain = false

    init(
        name: String = "attachment.bin",
        data: Data = Data([1, 2, 3]),
        writeError: CocoaError? = nil,
        beforeRead: @escaping @Sendable () -> Void = {}
    ) {
        self.name = name
        self.data = data
        self.writeError = writeError
        self.beforeRead = beforeRead
    }

    var readCount: Int { lock.withLock { reads } }
    var writeCount: Int { lock.withLock { writes } }
    var closeCount: Int { lock.withLock { closes } }
    var handoffCount: Int { lock.withLock { handoffs } }
    var readOnMainThread: Bool { lock.withLock { readOnMain } }
    func isAvailable() -> Bool { lock.withLock { available } }
    func handoff() { lock.withLock { handoffs += 1 } }
    func expire() { retire(closing: false) }
    func close() { retire(closing: true) }

    func onRetirement(_ action: @escaping @Sendable () -> Void) {
        let registered = lock.withLock {
            if available { retirementActions.append(action) }
            return available
        }
        if !registered { action() }
    }

    func load(cancellation: AttachmentDragReadCancellationSnapshot) -> AttachmentDragLoadResult {
        lock.withLock {
            reads += 1
            readOnMain = readOnMain || Thread.isMainThread
        }
        beforeRead()
        if cancellation.cancelled { return .cancelled }
        return .data(data)
    }

    /// Lets the manual probe drop a real file; the production file policy is Kotlin's.
    func write(to destination: URL, cancellation: AttachmentDragReadCancellationSnapshot) throws {
        lock.withLock { writes += 1 }
        if cancellation.cancelled { throw CocoaError(.userCancelled) }
        if let writeError { throw writeError }
        guard isAvailable() else { throw CocoaError(.fileWriteUnknown) }
        try data.write(to: destination, options: .withoutOverwriting)
    }

    private func retire(closing: Bool) {
        let actions = lock.withLock {
            if closing { closes += 1 }
            available = false
            defer { retirementActions = [] }
            return retirementActions
        }
        actions.forEach { $0() }
    }
}

final class AttachmentDragTestPreparation: AttachmentDragPreparation, @unchecked Sendable {
    private let lock = NSLock()
    private var cancellations = 0
    private let onCancel: @Sendable () -> Void

    init(onCancel: @escaping @Sendable () -> Void = {}) { self.onCancel = onCancel }
    var cancelCount: Int { lock.withLock { cancellations } }
    func cancel() {
        lock.withLock { cancellations += 1 }
        onCancel()
    }
}

/// Completes when a test calls `complete`, or at once with a fresh `content()` when given one.
final class AttachmentDragTestPreparer: AttachmentDragPreparing, @unchecked Sendable {
    let name: String
    let promisedFileName: String?
    let handle: AttachmentDragTestPreparation
    private let lock = NSLock()
    private var preparations = 0
    private var callback: (@Sendable ((any AttachmentDragContent)?) -> Void)?
    private let content: (@Sendable () -> any AttachmentDragContent)?
    private let onPrepare: @Sendable () -> Void

    init(
        name: String = "attachment.bin",
        promisedFileName: String? = nil,
        content: (@Sendable () -> any AttachmentDragContent)? = nil,
        onPrepare: @escaping @Sendable () -> Void = {},
        onCancel: @escaping @Sendable () -> Void = {}
    ) {
        self.name = name
        self.promisedFileName = promisedFileName
        self.content = content
        self.onPrepare = onPrepare
        handle = AttachmentDragTestPreparation(onCancel: onCancel)
    }
    var prepareCount: Int { lock.withLock { preparations } }

    func prepare(
        completion: @escaping @Sendable ((any AttachmentDragContent)?) -> Void
    ) -> any AttachmentDragPreparation {
        lock.withLock {
            preparations += 1
            callback = completion
        }
        onPrepare()
        if let content { complete(content()) }
        return handle
    }

    func complete(_ content: (any AttachmentDragContent)?) {
        let completion = lock.withLock {
            let completion = callback
            callback = nil
            return completion
        }
        completion?(content)
    }
}

/// Only a detached fake read waits here; tests release every reader before returning.
final class AttachmentDragTestGate: @unchecked Sendable {
    private let condition = NSCondition()
    private var open = false

    func wait() {
        condition.lock()
        while !open { condition.wait() }
        condition.unlock()
    }

    func release() {
        condition.lock()
        open = true
        condition.broadcast()
        condition.unlock()
    }
}

/// Records values reported from any thread.
final class AttachmentDragTestResults<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [Value] = []

    var values: [Value] { lock.withLock { recorded } }
    func append(_ value: Value) { lock.withLock { recorded.append(value) } }
}
