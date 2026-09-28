import KeyguardShared
import XCTest
@testable import KeyguardUI

final class AddKeyGeneratorModelTests: XCTestCase {
    @MainActor
    func testCancellationRejectsQueuedKeyAndKeepsOtherSessionIndependent() async throws {
        var callbacks: [(AddKeyGeneratorSnapshot) -> Void] = []
        var sessionIds: [String] = []
        var cancelled = 0
        var applied: [String] = []
        func makeModel() -> AddKeyGeneratorModel {
            AddKeyGeneratorModel(
                itemId: "key", isGpg: true,
                coreProvider: { fatalError("No live vault in observation tests") },
                observe: { _, sessionId, onChange in
                    sessionIds.append(sessionId)
                    callbacks.append(onChange)
                    return BridgeObservation(cancel: { cancelled += 1 })
                },
                apply: {
                    applied.append($0); return true
                }
            )
        }
        let first = makeModel()
        first.start()
        first.start()
        let ready = AddKeyGeneratorSnapshot(generator: .companion.empty, canUseKey: true, userId: "First")
        callbacks[0](ready)
        first.stop()
        let second = makeModel()
        second.start()
        callbacks[0](ready)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(first.snapshot.canUseKey)
        XCTAssertFalse(second.snapshot.canUseKey)
        XCTAssertFalse(first.useKey())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(sessionIds.count, 2)
        XCTAssertNotEqual(sessionIds[0], sessionIds[1])

        callbacks[1](ready)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(second.useKey())
        XCTAssertFalse(second.useKey())
        XCTAssertEqual(applied, [sessionIds[1]])
        XCTAssertEqual(cancelled, 2)
    }

    @MainActor
    func testUnavailableDraftDoesNotDismissTheGenerator() async throws {
        var callback: ((AddKeyGeneratorSnapshot) -> Void)?
        var cancellations = 0
        let model = AddKeyGeneratorModel(
            itemId: "ssh", isGpg: false,
            coreProvider: { fatalError("No live vault in observation tests") },
            observe: { _, _, onChange in
                callback = onChange
                return BridgeObservation(cancel: { cancellations += 1 })
            },
            apply: { _ in false }
        )
        model.start()
        XCTAssertFalse(model.useKey())
        callback?(AddKeyGeneratorSnapshot(generator: .companion.empty, canUseKey: true, userId: nil))
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.useKey())
        XCTAssertEqual(cancellations, 0)
        model.stop()
        XCTAssertFalse(model.snapshot.canUseKey)
        XCTAssertEqual(cancellations, 1)
    }
}
