import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class DetailObservationIsolationTests: XCTestCase {
    @MainActor
    func testAutofillGeneratorRejectsReplacedAndStoppedSnapshots() async throws {
        let probe = DetailObservationProbe<GeneratorSnapshot>()
        let model = AutofillGeneratorModel(
            actions: GeneratorActions(
                invoke: { _ in }, setSwitch: { _, _ in }, setCounter: { _, _ in }, setText: { _, _ in },
                setLength: { _ in }), observeGenerator: { _, _, _, callback in probe.subscribe(callback) })
        let makeSnapshot = { (title: String) in
            GeneratorSnapshot(
                loaded: true, typeTitle: title, types: [], value: nil, suggestions: [], tip: nil,
                length: nil, filters: [], options: [], canOpenHistory: false
            )
        }
        try await checkReplacement(
            probe, first: makeSnapshot("Username"), second: makeSnapshot("Password"),
            empty: GeneratorSnapshot.companion.empty,
            start: { model.startAutofillGeneratorObservation(username: $0 == 0, password: $0 != 0, uris: []) },
            stop: model.stopAutofillGeneratorObservation,
            snapshot: { model.autofillGenerator }
        )
    }

    @MainActor
    private func checkReplacement<Snapshot: Equatable>(
        _ probe: DetailObservationProbe<Snapshot>, first: Snapshot, second: Snapshot, empty: Snapshot,
        start: (Int) -> Void, stop: () -> Void, snapshot: () -> Snapshot
    ) async throws {
        start(0)
        probe.callbacks[0](first)
        // Replace the producer before the already-enqueued MainActor callback runs.
        start(1)
        XCTAssertEqual(probe.cancellations, 1)
        try await settle()
        XCTAssertEqual(snapshot(), empty)
        probe.callbacks[1](second)
        try await settle()
        XCTAssertEqual(snapshot(), second)
        probe.callbacks[0](first)
        try await settle()
        XCTAssertEqual(snapshot(), second)
        probe.callbacks[1](first)
        stop()
        try await settle()
        XCTAssertEqual(snapshot(), empty)
        XCTAssertEqual(probe.cancellations, 2)
    }

    @MainActor
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(20))
    }
}

private final class DetailObservationProbe<Snapshot> {
    var callbacks: [(Snapshot) -> Void] = []
    var cancellations = 0

    func subscribe(_ callback: @escaping (Snapshot) -> Void) -> BridgeObservation {
        callbacks.append(callback)
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }
}
