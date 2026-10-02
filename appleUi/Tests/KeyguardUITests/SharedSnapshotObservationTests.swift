import Foundation
import Observation
import XCTest
@testable import KeyguardUI

final class SharedSnapshotObservationTests: XCTestCase {
    @MainActor
    func testClosingOnePresentationKeepsTheSnapshotAndUpdatesUntilTheLastCloses() async throws {
        let source = SharedSnapshotSource()
        let model = SharedSnapshotFixture(source: source)
        model.start()
        model.start()
        XCTAssertEqual(source.callbacks.count, 1)

        source.callbacks[0](10)
        try await Task.sleep(for: .milliseconds(20))
        model.stop()
        XCTAssertEqual(model.snapshot, 10)
        XCTAssertEqual(source.cancellations, 0)

        source.callbacks[0](20)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 20)
        model.stop()
        XCTAssertEqual(model.snapshot, 0)
        XCTAssertEqual(source.cancellations, 1)
        source.callbacks[0](30)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 0)
    }

    @MainActor
    func testRetryPreservesBothConsumersAndDropsOldCallbacks() async throws {
        let source = SharedSnapshotSource()
        let model = SharedSnapshotFixture(source: source)
        model.start()
        model.start()
        source.callbacks[0](10)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 10)

        source.callbacks[0](20)
        model.retry()
        XCTAssertEqual(model.snapshot, 0)
        XCTAssertEqual(source.callbacks.count, 2)
        XCTAssertEqual(source.cancellations, 1)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 0)

        source.callbacks[1](30)
        source.callbacks[0](40)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 30)
        model.stop()
        XCTAssertEqual(model.snapshot, 30)
        XCTAssertEqual(source.cancellations, 1)
        source.callbacks[1](50)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 50)
        model.stop()
        XCTAssertEqual(model.snapshot, 0)
        XCTAssertEqual(source.cancellations, 2)

        model.retry()
        XCTAssertEqual(source.callbacks.count, 2)
        model.stop()
        model.start()
        model.stop()
        XCTAssertEqual(source.callbacks.count, 3)
        XCTAssertEqual(source.cancellations, 3)
    }

    @MainActor
    func testReopeningDropsQueuedSnapshotsFromThePreviousVisit() async throws {
        let source = SharedSnapshotSource()
        let model = SharedSnapshotFixture(source: source)
        model.start()
        source.callbacks[0](10)
        model.stop()
        model.start()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 0)

        source.callbacks[1](20)
        source.callbacks[0](30)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 20)
        model.stop()
        XCTAssertEqual(source.cancellations, 2)
    }

    @MainActor
    func testReleasingTheModelCancelsItsSourceWithoutRetainingTheOwner() {
        let source = SharedSnapshotSource()
        var model: SharedSnapshotFixture? = SharedSnapshotFixture(source: source)
        weak var weakModel = model
        model?.start()
        model?.start()
        model = nil
        XCTAssertNil(weakModel)
        XCTAssertEqual(source.cancellations, 1)
        source.callbacks[0](10)
    }
}

@MainActor
@Observable
private final class SharedSnapshotFixture: SnapshotObserving {
    private let source: SharedSnapshotSource
    @ObservationIgnored private let shared = SharedObservation()
    @ObservationIgnored private var subscription: BridgeObservation?
    private(set) var snapshot = 0

    init(source: SharedSnapshotSource) {
        self.source = source
    }

    func start() { shared.acquire(makeObservation) }
    func stop() { shared.release() }
    func retry() { shared.restart(makeObservation) }

    private func makeObservation() -> BridgeObservation {
        sharedSnapshotObservation(\.subscription, into: \.snapshot, empty: 0, subscribe: source.subscribe)
    }
}

@MainActor
private final class SharedSnapshotSource {
    var callbacks: [(Int) -> Void] = []
    var cancellations = 0

    func subscribe(_ onChange: @escaping (Int) -> Void) -> BridgeObservation {
        callbacks.append(onChange)
        return BridgeObservation { self.cancellations += 1 }
    }
}
