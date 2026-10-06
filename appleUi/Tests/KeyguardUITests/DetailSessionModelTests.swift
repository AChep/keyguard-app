import Foundation
import XCTest
@testable import KeyguardUI

final class DetailSessionModelTests: XCTestCase {
    @MainActor
    func testPresentationsKeepSnapshotsActionsAndLifetimeIndependent() async throws {
        let firstSource = SessionProbe()
        let secondSource = SessionProbe()
        let first = model { _ in firstSource }
        let second = model { _ in secondSource }
        first.setTarget(target("A"))
        second.setTarget(target("B"))
        let firstOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        firstSource.publish?("A")
        secondSource.publish?("B")
        try await settle()
        XCTAssertEqual(first.detail, "A")
        XCTAssertEqual(second.detail, "B")
        first.perform(owner: firstOwner) { $0.actions.append("copy") }
        second.perform(owner: secondOwner) { $0.actions.append("edit") }
        first.stop()
        first.perform(owner: firstOwner) { $0.actions.append("late") }
        secondSource.publish?("updated B")
        second.perform(owner: secondOwner) { $0.actions.append("share") }
        try await settle()
        XCTAssertNil(first.detail)
        XCTAssertEqual(second.detail, "updated B")
        XCTAssertEqual(firstSource.actions, ["copy"])
        XCTAssertEqual(secondSource.actions, ["edit", "share"])
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(secondSource.cancellations, 0)
        second.stop()
    }

    @MainActor
    func testReplacementRejectsQueuedFramesAndOldActionsIncludingAcrossAccounts() async throws {
        var sources: [SessionProbe] = []
        let model = model { _ in
            let source = SessionProbe()
            sources.append(source)
            return source
        }
        model.setTarget(target("same", account: "A"))
        let oldOwner = try XCTUnwrap(model.identity)
        sources[0].publish?("queued A")
        model.setTarget(target("same", account: "B"))
        let newOwner = try XCTUnwrap(model.identity)
        model.setTarget(target("same", account: "B"))
        XCTAssertEqual(sources.count, 2)
        XCTAssertEqual(sources[0].cancellations, 1)
        XCTAssertNotEqual(oldOwner, newOwner)
        try await settle()
        XCTAssertNil(model.detail)
        sources[1].publish?("B")
        try await settle()
        model.perform(owner: oldOwner) { $0.actions.append("stale edit") }
        model.perform(owner: newOwner) { $0.actions.append("copy B") }
        sources[0].publish?("late A")
        try await settle()
        XCTAssertEqual(model.detail, "B")
        XCTAssertEqual(sources[1].actions, ["copy B"])
        sources[1].publish?("queued B")
        model.setTarget(nil)
        try await settle()
        XCTAssertNil(model.detail)
        XCTAssertNil(model.identity)
        XCTAssertEqual(sources[1].cancellations, 1)
    }

    @MainActor
    func testSameEntityPresentationsStillHaveSeparateSessions() async throws {
        var sources: [SessionProbe] = []
        let makeSource: (ItemDetailTarget) -> SessionProbe = { _ in
            let source = SessionProbe()
            sources.append(source)
            return source
        }
        let first = model(makeSource)
        let second = model(makeSource)
        first.setTarget(target("same"))
        second.setTarget(target("same"))
        XCTAssertEqual(sources.count, 2)
        XCTAssertNotEqual(first.identity, second.identity)
        first.stop()
        sources[1].publish?("still open")
        try await settle()
        XCTAssertEqual(second.detail, "still open")
        XCTAssertEqual(sources[1].cancellations, 0)
        second.stop()
    }

    @MainActor
    func testSnapshotUpdatesRetainIdentityAndReappearingCreatesAFreshSession() async throws {
        var sources: [SessionProbe] = []
        let model = model { _ in
            let source = SessionProbe()
            sources.append(source)
            return source
        }
        model.setTarget(target("A"))
        let owner = try XCTUnwrap(model.identity)
        for snapshot in ["initial", "updated", "locked", "unlocked"] {
            sources[0].publish?(snapshot)
            try await settle()
            XCTAssertEqual(model.detail, snapshot)
            XCTAssertEqual(model.identity, owner)
        }
        model.stop()
        model.stop()
        model.setTarget(target("A"))
        XCTAssertEqual(sources.count, 2)
        XCTAssertEqual(sources[0].cancellations, 1)
        XCTAssertNotEqual(model.identity, owner)
        XCTAssertNil(model.detail)
        model.stop()
    }

    @MainActor
    func testSynchronousInitialSnapshotIsDelivered() async throws {
        let source = SessionProbe()
        let model = DetailSessionModel<String, String, SessionProbe>(
            makeSession: { _ in source },
            subscribe: { source, callback in
                callback("initial")
                return source.subscribe(callback)
            })
        model.setTarget("account")
        try await settle()
        XCTAssertEqual(model.detail, "initial")
        model.stop()
    }

    @MainActor
    func testReleasingAPresentationCancelsItsProducer() {
        let source = SessionProbe()
        var model: DetailSessionModel<ItemDetailTarget, String, SessionProbe>? = self.model { _ in source }
        model?.setTarget(target("A"))
        model = nil
        XCTAssertEqual(source.cancellations, 1)
    }

    @MainActor
    private func model(
        _ makeSession: @escaping (ItemDetailTarget) -> SessionProbe
    ) -> DetailSessionModel<ItemDetailTarget, String, SessionProbe> {
        DetailSessionModel(makeSession: makeSession, subscribe: { $0.subscribe($1) })
    }

    private func target(_ itemId: String, account: String = "account") -> ItemDetailTarget {
        ItemDetailTarget(itemId: itemId, accountId: account)
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(20))
    }
}

@MainActor
private final class SessionProbe {
    var publish: ((String) -> Void)?
    var actions: [String] = []
    var cancellations = 0

    func subscribe(_ callback: @escaping (String) -> Void) -> BridgeObservation {
        publish = callback
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }
}
