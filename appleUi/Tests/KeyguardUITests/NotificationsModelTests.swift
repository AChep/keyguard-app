import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class NotificationsModelTests: XCTestCase {
    @MainActor
    func testReplacingToastRearmsDurationAndRejectsOldDismissal() async throws {
        var receive: ((MessageSnapshot) -> Void)?
        var scheduled: [@MainActor () -> Void] = []
        var durations: [TimeInterval] = []
        var cancellations = 0
        let model = NotificationsModel(
            observeMessages: {
                receive = $0
                return BridgeObservation(cancel: {})
            },
            scheduleDismiss: { duration, action in
                durations.append(duration)
                scheduled.append(action)
                return BridgeObservation(cancel: { cancellations += 1 })
            })
        model.start()
        receive?(message(title: "First", duration: 1000))
        try await Task.sleep(for: .milliseconds(20))
        receive?(message(title: "Replacement", duration: 3000))
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.toasts.map(\.title), ["Replacement"])
        XCTAssertEqual(durations, [1, 3])
        XCTAssertEqual(cancellations, 1)
        scheduled[0]()
        XCTAssertEqual(model.toasts.count, 1)
        scheduled[1]()
        XCTAssertTrue(model.toasts.isEmpty)
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testReleaseCancelsMessageObservationAndDismissalWork() async throws {
        var receive: ((MessageSnapshot) -> Void)?
        var starts = 0
        var cancellations = 0
        var model: NotificationsModel? = NotificationsModel(
            observeMessages: {
                starts += 1
                receive = $0
                return BridgeObservation(cancel: { cancellations += 1 })
            }, scheduleDismiss: { _, _ in BridgeObservation(cancel: { cancellations += 1 }) })
        weak var weakModel = model
        model?.start()
        model?.start()
        XCTAssertEqual(starts, 1)
        receive?(message(title: "Toast", duration: 1000))
        try await Task.sleep(for: .milliseconds(20))
        model = nil
        XCTAssertNil(weakModel)
        XCTAssertEqual(cancellations, 2)
    }

    private func message(title: String, duration: Int64) -> MessageSnapshot {
        MessageSnapshot(id: "same", type: "ERROR", title: title, text: nil, durationMillis: duration)
    }
}
