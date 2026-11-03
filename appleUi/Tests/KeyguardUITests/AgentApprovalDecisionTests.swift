import Foundation
import XCTest
@testable import KeyguardUI

final class AgentApprovalDecisionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 10)
    private let expiry: Int64 = 20_000

    func testActivationForRemovedRequestCannotApproveNextRequest() {
        var first = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertNil(first.begin(approved: true, vaultUnlocked: true, currentRequestID: "B", now: now))
        XCTAssertFalse(first.isResolving)
        var second = AgentApprovalDecision(requestID: "B", expiresAtEpochMs: 30_000)
        XCTAssertEqual(second.begin(approved: true, vaultUnlocked: true, currentRequestID: "B", now: now), "B")
    }

    func testDisconnectedAndEmptyQueueRejectsStaleActivation() {
        var decision = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertNil(decision.begin(approved: true, vaultUnlocked: true, currentRequestID: nil, now: now))
        XCTAssertNil(decision.begin(approved: false, vaultUnlocked: true, currentRequestID: "B", now: now))
    }

    func testLockedApprovalIsRejectedUntilAnExplicitActionAfterUnlock() {
        var decision = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertNil(decision.begin(approved: true, vaultUnlocked: false, currentRequestID: "A", now: now))
        XCTAssertFalse(decision.isResolving)
        XCTAssertEqual(decision.begin(approved: true, vaultUnlocked: true, currentRequestID: "A", now: now), "A")
    }

    func testExpiryBoundaryRejectsApprovalEvenWhenCountdownHasNotRedrawn() {
        var decision = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertNil(
            decision.begin(
                approved: true, vaultUnlocked: true, currentRequestID: "A", now: Date(timeIntervalSince1970: 20)))
        XCTAssertNil(
            decision.begin(
                approved: true, vaultUnlocked: true, currentRequestID: "A", now: Date(timeIntervalSince1970: 21)))
        XCTAssertFalse(decision.isResolving)
    }

    func testDenyWorksWhileLockedAndCannotBeReplacedByALaterApproval() {
        var decision = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertEqual(decision.begin(approved: false, vaultUnlocked: false, currentRequestID: "A", now: now), "A")
        XCTAssertNil(decision.begin(approved: true, vaultUnlocked: true, currentRequestID: "A", now: now))
        XCTAssertTrue(decision.isResolving)
    }

    func testResetAllowsAnotherActionAfterAnAbandonedResolution() {
        var decision = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        XCTAssertEqual(decision.begin(approved: true, vaultUnlocked: true, currentRequestID: "A", now: now), "A")
        decision.reset()
        XCTAssertFalse(decision.isResolving)
        XCTAssertEqual(decision.begin(approved: false, vaultUnlocked: true, currentRequestID: "A", now: now), "A")
    }

    func testQueuedRequestsKeepIndependentDecisionsAndIgnoreDuplicateClicks() {
        var signing = AgentApprovalDecision(requestID: "A", expiresAtEpochMs: expiry)
        var decryption = AgentApprovalDecision(requestID: "B", expiresAtEpochMs: expiry)
        XCTAssertEqual(signing.begin(approved: true, vaultUnlocked: true, currentRequestID: "A", now: now), "A")
        XCTAssertNil(signing.begin(approved: true, vaultUnlocked: true, currentRequestID: "A", now: now))
        XCTAssertFalse(decryption.isResolving)
        XCTAssertEqual(decryption.begin(approved: false, vaultUnlocked: true, currentRequestID: "B", now: now), "B")
    }
}
