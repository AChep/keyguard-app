import Foundation
import Observation
import XCTest
@testable import KeyguardUI

final class BridgeObservationTests: XCTestCase {
    func testCancellationIsIdempotentAndRunsOnRelease() {
        var cancellations = 0
        var observation: BridgeObservation? = BridgeObservation(cancel: { cancellations += 1 })
        observation?.cancel()
        observation?.cancel()
        observation = nil
        XCTAssertEqual(cancellations, 1)
        observation = BridgeObservation(cancel: { cancellations += 1 })
        observation = nil
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testSharedObservationSurvivesOneWindowClosingAndCanRestart() {
        let shared = SharedObservation()
        var starts = 0
        var cancellations = 0
        let subscribe = {
            starts += 1
            return BridgeObservation(cancel: { cancellations += 1 })
        }
        XCTAssertFalse(shared.release())
        shared.acquire(subscribe)
        shared.acquire(subscribe)
        XCTAssertEqual(starts, 1)
        XCTAssertFalse(shared.release())
        XCTAssertEqual(cancellations, 0)
        XCTAssertTrue(shared.release())
        XCTAssertEqual(cancellations, 1)
        XCTAssertFalse(shared.release())
        shared.acquire(subscribe)
        XCTAssertEqual(starts, 2)
        shared.reset()
        XCTAssertEqual(cancellations, 2)
        XCTAssertFalse(shared.release())
    }

    @MainActor
    func testStoppedAndReplacedObservationsRejectQueuedSnapshots() async throws {
        let model = SnapshotObservationFixture()
        var callbacks: [(Int) -> Void] = []
        var cancellations = 0
        let subscribe: (@escaping (Int) -> Void) -> BridgeObservation = {
            callbacks.append($0)
            return BridgeObservation(cancel: { cancellations += 1 })
        }
        model.startObservation(\.observation, into: \.snapshot, subscribe: subscribe)
        model.startObservation(\.observation, into: \.snapshot, subscribe: subscribe)
        XCTAssertEqual(callbacks.count, 1)
        callbacks[0](10)
        model.stopObservation(\.observation, resetting: \.snapshot, to: 0)
        model.startObservation(\.observation, into: \.snapshot, subscribe: subscribe)
        callbacks[1](20)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 20)
        callbacks[0](30)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 20)
        model.stopObservation(\.observation, resetting: \.snapshot, to: 0)
        callbacks[1](40)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.snapshot, 0)
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testDeliveriesFromStoppedOrReplacedObservationsAreDropped() async throws {
        let model = SnapshotObservationFixture()
        var completions: [() -> Void] = []
        let start = {
            model.startObservation(\.observation) { deliver in
                completions.append { deliver { $0.completions += 1 } }
                return BridgeObservation(cancel: {})
            }
        }
        start()
        completions[0]()
        model.stopObservation(\.observation)
        start()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.completions, 0)
        completions[1]()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.completions, 1)
    }

    /// Bridge models assign observed values directly and rely on this.
    @MainActor
    func testEqualAssignmentsDoNotNotifyObservers() {
        let model = SnapshotObservationFixture()
        let notifications = NotificationCount()
        withObservationTracking {
            _ = model.snapshot
        } onChange: {
            notifications.value += 1
        }
        model.snapshot = 0
        model[keyPath: \.snapshot] = 0
        XCTAssertEqual(notifications.value, 0)
        model.snapshot = 1
        XCTAssertEqual(notifications.value, 1)
    }

    @MainActor
    func testCallbackDoesNotRetainItsOwner() {
        var model: SnapshotObservationFixture? = SnapshotObservationFixture()
        weak var weakModel = model
        var callback: ((Int) -> Void)?
        var cancellations = 0
        model?.startObservation(
            \.observation, into: \.snapshot,
            subscribe: {
                callback = $0
                return BridgeObservation(cancel: { cancellations += 1 })
            })
        model = nil
        XCTAssertNil(weakModel)
        XCTAssertEqual(cancellations, 1)
        callback?(1)
    }
}

@MainActor
@Observable
private final class SnapshotObservationFixture: SnapshotObserving {
    @ObservationIgnored var observation: BridgeObservation?
    var snapshot = 0
    var completions = 0
}

private final class NotificationCount: @unchecked Sendable {
    var value = 0
}
