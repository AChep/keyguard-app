import Foundation
import KeyguardShared

/// A subscription cancels exactly once, including when its owner releases it.
final class BridgeObservation {
    let id: UUID
    private var cancellation: (() -> Void)?
    var isCancelled: Bool { cancellation == nil }

    init(id: UUID = UUID(), cancel: @escaping () -> Void) {
        self.id = id
        cancellation = cancel
    }

    convenience init(_ cancellable: KeyguardCancellable, id: UUID = UUID()) {
        self.init(id: id, cancel: { cancellable.cancel() })
    }

    func cancel() {
        let action = cancellation
        cancellation = nil
        action?()
    }

    deinit { cancellation?() }
}
