import os

/// Shared by the model and native exporter. Abandoned presentations release on
/// their last reference; completed presentations can release explicitly once.
final class GpgToolsExportLease: Sendable {
    private let action: OSAllocatedUnfairLock<(@MainActor @Sendable () -> Void)?>

    init(release: @escaping @MainActor @Sendable () -> Void) {
        action = OSAllocatedUnfairLock(initialState: release)
    }

    func release() {
        let release = action.withLock { action in
            let release = action
            action = nil
            return release
        }
        if let release { Task { @MainActor in release() } }
    }

    deinit { release() }
}
