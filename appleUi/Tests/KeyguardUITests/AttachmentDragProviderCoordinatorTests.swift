import Foundation
import XCTest
@testable import KeyguardUI

final class AttachmentDragProviderCoordinatorTests: XCTestCase {
    func testCoalescesPreparationAndReadsEveryRepresentationIndependentlyOnWorkers() async {
        let preparer = AttachmentDragTestPreparer()
        let content = AttachmentDragTestContent()
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        let first = expectation(description: "first data")
        let second = expectation(description: "second data")
        _ = coordinator.load { data, error in
            XCTAssertEqual(data, Data([1, 2, 3]))
            XCTAssertNil(error)
            first.fulfill()
        }
        _ = coordinator.load { data, error in
            XCTAssertEqual(data, Data([1, 2, 3]))
            XCTAssertNil(error)
            second.fulfill()
        }
        XCTAssertEqual(preparer.prepareCount, 1)
        preparer.complete(content)
        await fulfillment(of: [first, second], timeout: 2)

        let repeated = expectation(description: "repeated data")
        _ = coordinator.load { data, error in
            XCTAssertEqual(data, Data([1, 2, 3]))
            XCTAssertNil(error)
            repeated.fulfill()
        }
        await fulfillment(of: [repeated], timeout: 2)
        XCTAssertEqual(preparer.prepareCount, 1)
        XCTAssertEqual(content.readCount, 3)
        XCTAssertEqual(content.handoffCount, 1)
        XCTAssertFalse(content.readOnMainThread)
    }

    func testCancellingOnePendingLoadPreservesOtherLoadAndPreparation() async {
        let preparer = AttachmentDragTestPreparer()
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        let cancelled = expectation(description: "one cancelled")
        let remaining = expectation(description: "other succeeds")
        let progress = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            cancelled.fulfill()
        }
        _ = coordinator.load { data, error in
            XCTAssertNotNil(data)
            XCTAssertNil(error)
            remaining.fulfill()
        }
        // Progress runs its cancellation handler asynchronously.
        progress.cancel()
        await fulfillment(of: [cancelled], timeout: 2)
        XCTAssertEqual(preparer.handle.cancelCount, 0)
        preparer.complete(AttachmentDragTestContent())
        await fulfillment(of: [remaining], timeout: 2)
    }

    func testLastPendingCancellationCancelsPreparationAndDisposesLateContentWithoutRepreparing() async {
        let backendCancelled = expectation(description: "preparation cancelled")
        let preparer = AttachmentDragTestPreparer(onCancel: { backendCancelled.fulfill() })
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        let cancelled = expectation(description: "cancelled")
        let progress = coordinator.load { _, error in
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            cancelled.fulfill()
        }
        progress.cancel()
        await fulfillment(of: [cancelled, backendCancelled], timeout: 2)
        XCTAssertEqual(preparer.handle.cancelCount, 1)
        let late = AttachmentDragTestContent()
        preparer.complete(late)
        XCTAssertEqual(late.closeCount, 1)
        XCTAssertEqual(late.handoffCount, 0)
        let rejected = expectation(description: "terminal provider")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertNotNil(error)
            rejected.fulfill()
        }
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(preparer.prepareCount, 1)
    }

    func testCancellationCompletionCannotPublishLateContentOrReenterAnUnpublishedProvider() async {
        let preparer = AttachmentDragTestPreparer()
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        let late = AttachmentDragTestContent()
        let rejected = expectation(description: "reentrant load remains terminal")
        let progress = coordinator.load { _, error in
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            preparer.complete(late)
            _ = coordinator.load { data, error in
                XCTAssertNil(data)
                XCTAssertNotNil(error)
                rejected.fulfill()
            }
        }
        progress.cancel()
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(preparer.handle.cancelCount, 1)
        XCTAssertEqual(late.closeCount, 1)
        XCTAssertEqual(late.readCount, 0)
        XCTAssertEqual(preparer.prepareCount, 1)
    }

    func testActiveCancellationCompletesOnceAndDoesNotCancelOtherWorker() async {
        let gate = AttachmentDragTestGate()
        defer { gate.release() }
        let started = expectation(description: "both workers started")
        started.expectedFulfillmentCount = 2
        let content = AttachmentDragTestContent(beforeRead: {
            started.fulfill()
            gate.wait()
        })
        let coordinator = AttachmentDragProviderCoordinator(preparer: AttachmentDragTestPreparer(content: { content }))
        defer { coordinator.close() }
        let cancelled = expectation(description: "cancel exactly once")
        cancelled.assertForOverFulfill = true
        let successful = expectation(description: "independent worker completes")
        let first = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            cancelled.fulfill()
        }
        _ = coordinator.load { data, error in
            XCTAssertNotNil(data)
            XCTAssertNil(error)
            successful.fulfill()
        }
        await fulfillment(of: [started], timeout: 2)
        // Progress runs its cancellation handler asynchronously.
        first.cancel()
        await fulfillment(of: [cancelled], timeout: 2)
        gate.release()
        await fulfillment(of: [successful], timeout: 2)
        XCTAssertEqual(content.readCount, 2)
    }

    func testExpiredContentFailsFutureLoadsWithoutRepreparingOrReading() async {
        let content = AttachmentDragTestContent()
        let preparer = AttachmentDragTestPreparer(content: { content })
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        let initial = expectation(description: "initial load")
        _ = coordinator.load { _, error in
            XCTAssertNil(error)
            initial.fulfill()
        }
        await fulfillment(of: [initial], timeout: 2)
        content.expire()
        let expired = expectation(description: "expired offer")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertNotNil(error)
            expired.fulfill()
        }
        await fulfillment(of: [expired], timeout: 2)
        XCTAssertEqual(content.readCount, 1)
        XCTAssertEqual(preparer.prepareCount, 1)
        XCTAssertEqual(content.closeCount, 1)
    }

    func testPendingRequestLimitRejectsBeforeStartingAnotherPreparation() async {
        let preparer = AttachmentDragTestPreparer()
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer)
        defer { coordinator.close() }
        for _ in 0..<8 { _ = coordinator.load { _, _ in } }
        let rejected = expectation(description: "reader quota")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadUnknown)
            rejected.fulfill()
        }
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(preparer.prepareCount, 1)
    }

    func testCancelledWorkersKeepTheirAdmissionUntilTheyFinish() async {
        let gate = AttachmentDragTestGate()
        defer { gate.release() }
        let started = expectation(description: "worker started")
        let content = AttachmentDragTestContent(beforeRead: {
            started.fulfill()
            gate.wait()
        })
        let coordinator = AttachmentDragProviderCoordinator(
            preparer: AttachmentDragTestPreparer(content: { content }), maximumRequests: 1)
        defer { coordinator.close() }
        let completed = expectation(description: "request cancelled exactly once")
        let progress = coordinator.load { _, error in
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            completed.fulfill()
        }
        await fulfillment(of: [started], timeout: 2)
        progress.cancel()
        let rejected = expectation(description: "cancelled workers still count")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadUnknown)
            rejected.fulfill()
        }
        await fulfillment(of: [completed, rejected], timeout: 2)
        XCTAssertEqual(content.readCount, 1)
        gate.release()
    }

    func testTimeoutCancelsUnpublishedPreparationAndRejectsLaterLoad() async throws {
        let preparer = AttachmentDragTestPreparer()
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer, lifetime: .milliseconds(20))
        defer { coordinator.close() }
        let cancelled = expectation(description: "expiry cancellation")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .userCancelled)
            cancelled.fulfill()
        }
        await fulfillment(of: [cancelled], timeout: 2)
        XCTAssertEqual(preparer.handle.cancelCount, 1)
        let rejected = expectation(description: "expired provider remains terminal")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertNotNil(error)
            rejected.fulfill()
        }
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(preparer.prepareCount, 1)
    }

    func testPublishedContentOutlivesTheTimerUntilKotlinRetiresIt() async throws {
        let content = AttachmentDragTestContent()
        let preparer = AttachmentDragTestPreparer(content: { content })
        let coordinator = AttachmentDragProviderCoordinator(preparer: preparer, lifetime: .milliseconds(20))
        defer { coordinator.close() }
        let initial = expectation(description: "initial load")
        _ = coordinator.load { data, _ in
            XCTAssertNotNil(data)
            initial.fulfill()
        }
        await fulfillment(of: [initial], timeout: 2)
        try await Task.sleep(for: .milliseconds(100))
        let later = expectation(description: "load after the unpublished lifetime")
        _ = coordinator.load { data, _ in
            XCTAssertNotNil(data)
            later.fulfill()
        }
        await fulfillment(of: [later], timeout: 2)
        XCTAssertEqual(content.closeCount, 0)
        content.expire()
        XCTAssertEqual(content.closeCount, 1)
        let rejected = expectation(description: "retired provider")
        _ = coordinator.load { data, error in
            XCTAssertNil(data)
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadUnknown)
            rejected.fulfill()
        }
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertEqual(content.readCount, 2)
    }
}
