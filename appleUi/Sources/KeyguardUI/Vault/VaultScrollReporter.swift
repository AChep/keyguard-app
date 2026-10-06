import Foundation

/// Native adapters enable reporting after restoration and resolve their own top row.
@MainActor
final class VaultScrollReporter {
    typealias Schedule = (@escaping @MainActor @Sendable () -> Void) -> (() -> Void)

    var isEnabled = false
    private let now: () -> Date
    private let schedule: Schedule
    private let visibleAnchor: () -> String?
    private let report: (String) -> Void
    private var lastReportTime = Date.distantPast
    private var lastReportedId: String?
    private var cancelPending: (() -> Void)?

    init(
        now: @escaping () -> Date = Date.init,
        schedule: @escaping Schedule = { callback in
            let task = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(200))
                guard !Task.isCancelled else { return }
                callback()
            }
            return { task.cancel() }
        },
        visibleAnchor: @escaping () -> String?,
        report: @escaping (String) -> Void
    ) {
        self.now = now
        self.schedule = schedule
        self.visibleAnchor = visibleAnchor
        self.report = report
    }

    // Isolated so the pending main-actor work item can be cancelled from here.
    isolated deinit { cancelPending?() }

    func didScroll() {
        guard isEnabled else { return }
        cancelPending?()
        cancelPending = nil
        if now().timeIntervalSince(lastReportTime) >= 0.2 {
            fire()
        } else {
            cancelPending = schedule { [weak self] in self?.fire() }
        }
    }

    private func fire() {
        guard isEnabled else { return }
        lastReportTime = now()
        guard let id = visibleAnchor(), id != lastReportedId else { return }
        lastReportedId = id
        report(id)
    }
}
