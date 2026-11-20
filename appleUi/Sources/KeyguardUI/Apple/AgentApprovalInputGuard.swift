#if os(macOS)
import AppKit
import Observation

/// Holds the Approve and Deny actions of an approval panel back until the panel has
/// been key with no input for a second, so keys and clicks meant for another app
/// can't answer a request.
///
/// A key press or click while the actions are held back restarts the wait. Input
/// after they arm changes nothing, so a deliberate press still works. Resigning key
/// holds them back again. A mouse button still down when the wait ends starts another
/// wait, so a held press can't answer on release.
@MainActor
@Observable
final class AgentApprovalInputGuard {
    private static let holdBack: Duration = .seconds(1)

    /// Whether the actions accept input.
    private(set) var armed = false

    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let isPointerDown: () -> Bool
    @ObservationIgnored private var focused = false
    @ObservationIgnored private var wait: Task<Void, Never>?

    /// - Parameters:
    ///   - sleep: Waits for a duration; injectable for tests.
    ///   - isPointerDown: Whether any mouse button is down; injectable for tests.
    init(
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        isPointerDown: @escaping () -> Bool = { NSEvent.pressedMouseButtons != 0 }
    ) {
        self.sleep = sleep
        self.isPointerDown = isPointerDown
    }

    /// A key press or click in the panel. Restarts the wait while held back.
    func onInput() {
        guard !armed else { return }
        startWait()
    }

    /// Holds the actions back and restarts the wait, for a new request or after an unlock.
    func restart() {
        armed = false
        startWait()
    }

    /// The panel became or resigned key. The actions stay held back while it isn't key.
    func setFocused(_ focused: Bool) {
        self.focused = focused
        restart()
    }

    private func startWait() {
        wait?.cancel()
        wait = nil
        guard focused else { return }
        wait = Task { [weak self, sleep] in
            guard (try? await sleep(Self.holdBack)) != nil, !Task.isCancelled, let self else { return }
            if isPointerDown() {
                startWait()
            } else {
                armed = true
            }
        }
    }
}

#endif
