import Foundation
import Observation

/// Owns one refresh at a time. A newer invalidation supersedes an unfinished read.
@MainActor
@Observable
final class AutofillIndexCoordinator<Snapshot> {
    enum State: Equatable {
        case idle, disabled, updating, current, locked, failed
    }

    private(set) var state: State = .idle
    private(set) var isEnabled = false
    @ObservationIgnored private let enabled: @MainActor () async throws -> Bool
    @ObservationIgnored private let load: @MainActor () async throws -> Snapshot?
    @ObservationIgnored private let replace: @MainActor (Snapshot, @escaping @MainActor () -> Bool) async throws -> Bool
    @ObservationIgnored private let reportFailure: @MainActor (Error) -> Void
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var dirty = false
    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var available = true

    init(
        enabled: @escaping @MainActor () async throws -> Bool,
        load: @escaping @MainActor () async throws -> Snapshot?,
        replace: @escaping @MainActor (Snapshot, @escaping @MainActor () -> Bool) async throws -> Bool,
        reportFailure: @escaping @MainActor (Error) -> Void
    ) {
        self.enabled = enabled
        self.load = load
        self.replace = replace
        self.reportFailure = reportFailure
    }

    func refresh(available: Bool = true) {
        generation += 1
        self.available = available
        dirty = true
        schedule()
    }

    func setForeground(_ value: Bool) {
        foreground = value
        if value { refresh(available: available) } else { generation += 1 }
    }

    func fail() {
        generation += 1
        dirty = false
        state = .failed
    }

    private func schedule() {
        guard task == nil, dirty, foreground else { return }
        task = Task { [weak self] in
            guard let self else { return }
            await drain()
            task = nil
            schedule()
        }
    }

    private func drain() async {
        while dirty && foreground {
            dirty = false
            let current = generation
            state = .updating
            do {
                isEnabled = try await enabled()
                guard current == generation, foreground else { continue }
                guard isEnabled else { state = .disabled; continue }
                guard available else { state = .locked; continue }
                guard let snapshot = try await load() else {
                    if current == generation { state = .locked }
                    continue
                }
                guard current == generation, foreground, available else { continue }
                let written = try await replace(snapshot) { [weak self] in
                    guard let self else { return false }
                    return self.generation == current && self.foreground && self.available
                }
                guard current == generation, foreground else { continue }
                if written {
                    state = .current
                } else {
                    dirty = true
                    // Another process changed the vault while the snapshot was read.
                    try await Task.sleep(for: .milliseconds(100))
                }
            } catch {
                guard current == generation else { continue }
                reportFailure(error)
                state = .failed
            }
        }
    }
}
