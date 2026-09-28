import Foundation
import XCTest
@testable import KeyguardUI

final class SshAgentApprovalDecisionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 10)

    func testActivationForRemovedRequestCannotApproveNextRequest() {
        var first = SshAgentApprovalDecision(requestID: "A", expiresAtEpochMs: 20_000)
        XCTAssertNil(first.begin(approved: true, currentRequestID: "B", now: now))
        XCTAssertFalse(first.isResolving)
        var second = SshAgentApprovalDecision(requestID: "B", expiresAtEpochMs: 30_000)
        XCTAssertEqual(second.begin(approved: true, currentRequestID: "B", now: now), "B")
    }

    func testDisconnectedAndEmptyQueueRejectsStaleActivation() {
        var decision = SshAgentApprovalDecision(requestID: "A", expiresAtEpochMs: 20_000)
        XCTAssertNil(decision.begin(approved: true, currentRequestID: nil, now: now))
        XCTAssertNil(decision.begin(approved: false, currentRequestID: "B", now: now))
    }

    func testExpiryBoundaryRejectsApprovalBeforeQueueOrCountdownUpdates() {
        var decision = SshAgentApprovalDecision(requestID: "A", expiresAtEpochMs: 20_000)
        XCTAssertNil(decision.begin(approved: true, currentRequestID: "A", now: Date(timeIntervalSince1970: 20)))
        XCTAssertFalse(decision.isResolving)
    }

    func testRequestCanOnlyBeResolvedOnce() {
        var decision = SshAgentApprovalDecision(requestID: "A", expiresAtEpochMs: 20_000)
        XCTAssertEqual(decision.begin(approved: false, currentRequestID: "A", now: now), "A")
        XCTAssertNil(decision.begin(approved: true, currentRequestID: "A", now: now))
        XCTAssertTrue(decision.isResolving)
    }
}
