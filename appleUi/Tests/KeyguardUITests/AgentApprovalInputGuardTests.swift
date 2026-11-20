#if os(macOS)
import XCTest
@testable import KeyguardUI

@MainActor
final class AgentApprovalInputGuardTests: XCTestCase {
    private func eventually(
        _ predicate: () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !predicate(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(1))
        }
        XCTAssertTrue(predicate(), "Timed out waiting for the input guard", file: file, line: line)
    }

    /// Gives a wait that should not exist the time to start.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(10))
    }

    private func arm(_ inputGuard: AgentApprovalInputGuard, _ sleep: ManualSleep) async throws {
        inputGuard.setFocused(true)
        try await eventually { sleep.started == 1 }
        sleep.end(0)
        try await eventually { inputGuard.armed }
    }

    func testHoldsBackUntilTheKeyPanelHasNoInputForTheHoldBack() async throws {
        let sleep = ManualSleep()
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { false })
        inputGuard.setFocused(true)
        try await eventually { sleep.started == 1 }
        XCTAssertFalse(inputGuard.armed)
        XCTAssertEqual(sleep.durations, [.seconds(1)])
        sleep.end(0)
        try await eventually { inputGuard.armed }
    }

    func testInputWhileHeldBackRestartsTheWait() async throws {
        let sleep = ManualSleep()
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { false })
        inputGuard.setFocused(true)
        try await eventually { sleep.started == 1 }
        inputGuard.onInput()
        try await eventually { sleep.started == 2 && sleep.cancelled == 1 }
        XCTAssertFalse(inputGuard.armed)
        sleep.end(1)
        try await eventually { inputGuard.armed }
    }

    func testInputWhileArmedKeepsTheActionsArmed() async throws {
        let sleep = ManualSleep()
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { false })
        try await arm(inputGuard, sleep)
        inputGuard.onInput()
        XCTAssertTrue(inputGuard.armed)
        try await settle()
        XCTAssertTrue(inputGuard.armed)
        XCTAssertEqual(sleep.started, 1)
    }

    func testUnfocusedPanelNeverArmsAndFocusingStartsTheWait() async throws {
        let sleep = ManualSleep()
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { false })
        inputGuard.restart()
        inputGuard.onInput()
        try await settle()
        XCTAssertEqual(sleep.started, 0)
        XCTAssertFalse(inputGuard.armed)

        try await arm(inputGuard, sleep)
        inputGuard.setFocused(false)
        XCTAssertFalse(inputGuard.armed)
        try await settle()
        XCTAssertEqual(sleep.started, 1)
        XCTAssertFalse(inputGuard.armed)
    }

    func testRestartHoldsAnArmedGuardBack() async throws {
        let sleep = ManualSleep()
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { false })
        try await arm(inputGuard, sleep)
        inputGuard.restart()
        XCTAssertFalse(inputGuard.armed)
        try await eventually { sleep.started == 2 }
        XCTAssertFalse(inputGuard.armed)
        sleep.end(1)
        try await eventually { inputGuard.armed }
    }

    func testMouseButtonDownWhenTheWaitEndsStartsAnotherWait() async throws {
        let sleep = ManualSleep()
        var pointerDown = true
        let inputGuard = AgentApprovalInputGuard(sleep: sleep.sleep, isPointerDown: { pointerDown })
        inputGuard.setFocused(true)
        try await eventually { sleep.started == 1 }
        sleep.end(0)
        try await eventually { sleep.started == 2 }
        XCTAssertFalse(inputGuard.armed)

        pointerDown = false
        sleep.end(1)
        try await eventually { inputGuard.armed }
        XCTAssertEqual(sleep.started, 2)
    }
}

/// A sleep that ends when the test says so, or with an error when its task is cancelled.
@MainActor
private final class ManualSleep {
    private var waits: [Int: CheckedContinuation<Void, any Error>] = [:]
    private(set) var durations: [Duration] = []
    private(set) var cancelled = 0

    var started: Int { durations.count }

    var sleep: @Sendable (Duration) async throws -> Void {
        { [self] duration in try await wait(duration) }
    }

    /// Ends the sleep that started at `index`.
    func end(_ index: Int) {
        waits.removeValue(forKey: index)?.resume()
    }

    private func wait(_ duration: Duration) async throws {
        let index = durations.count
        durations.append(duration)
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { waits[index] = $0 }
        } onCancel: {
            Task { @MainActor in self.cancel(index) }
        }
    }

    private func cancel(_ index: Int) {
        guard let wait = waits.removeValue(forKey: index) else { return }
        cancelled += 1
        wait.resume(throwing: CancellationError())
    }
}

#endif
