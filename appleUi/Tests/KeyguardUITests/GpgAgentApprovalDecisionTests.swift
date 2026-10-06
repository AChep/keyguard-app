import Foundation
import XCTest
@testable import KeyguardUI

final class GpgAgentApprovalDecisionTests: XCTestCase {
    private let beforeExpiry = Date(timeIntervalSince1970: 10)
    private let expiry: Int64 = 20_000

    func testLockedApprovalIsRejectedUntilAnExplicitActionAfterUnlock() {
        var decision = GpgAgentApprovalDecision()
        XCTAssertFalse(
            decision.begin(approved: true, vaultUnlocked: false, expiresAtEpochMs: expiry, now: beforeExpiry))
        XCTAssertFalse(decision.isResolving)
        XCTAssertTrue(decision.begin(approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry, now: beforeExpiry))
    }

    func testExpiryBoundaryRejectsApprovalEvenWhenCountdownHasNotRedrawn() {
        var decision = GpgAgentApprovalDecision()
        XCTAssertFalse(
            decision.begin(
                approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry,
                now: Date(timeIntervalSince1970: 20)))
        XCTAssertFalse(
            decision.begin(
                approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry,
                now: Date(timeIntervalSince1970: 21)))
        XCTAssertFalse(decision.isResolving)
    }

    func testDenyWorksWhileLockedAndCannotBeReplacedByALaterApproval() {
        var decision = GpgAgentApprovalDecision()
        XCTAssertTrue(
            decision.begin(approved: false, vaultUnlocked: false, expiresAtEpochMs: expiry, now: beforeExpiry))
        XCTAssertFalse(decision.begin(approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry, now: beforeExpiry))
    }

    func testConcurrentRequestsKeepIndependentDecisionsAndIgnoreDuplicateClicks() {
        var signing = GpgAgentApprovalDecision()
        var decryption = GpgAgentApprovalDecision()
        XCTAssertTrue(signing.begin(approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry, now: beforeExpiry))
        XCTAssertFalse(signing.begin(approved: true, vaultUnlocked: true, expiresAtEpochMs: expiry, now: beforeExpiry))
        XCTAssertFalse(decryption.isResolving)
        XCTAssertTrue(
            decryption.begin(approved: false, vaultUnlocked: true, expiresAtEpochMs: expiry, now: beforeExpiry))
    }
}
