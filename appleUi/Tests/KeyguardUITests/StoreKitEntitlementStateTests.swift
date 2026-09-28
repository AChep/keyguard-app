import KeyguardShared
import XCTest
@testable import KeyguardUI

final class StoreKitEntitlementStateTests: XCTestCase {
    func testMixedRefreshPublishesVerifiedLifetimePurchase() {
        var state = StoreKitEntitlementState()
        state.refresh(verified: [entitlement("premium_lifetime")], unverifiedProductIDs: ["premium_3m"])
        XCTAssertTrue(state.loaded)
        XCTAssertFalse(state.verificationFailed)
        XCTAssertEqual(state.values.map(\.productId), ["premium_lifetime"])
    }

    func testUnverifiedOnlyFirstRefreshDoesNotGrantOrReportSuccessfulLoading() {
        var state = StoreKitEntitlementState()
        state.refresh(verified: [], unverifiedProductIDs: ["premium_3m"])
        XCTAssertTrue(state.verificationFailed)
        XCTAssertFalse(state.loaded)
        XCTAssertTrue(state.values.isEmpty)
    }

    func testFailedRefreshPreservesKnownValuesButKeepsFailureVisible() {
        var state = StoreKitEntitlementState()
        state.update(productID: "premium_lifetime", entitlement: entitlement("premium_lifetime"))
        state.refresh(verified: [], unverifiedProductIDs: ["premium_lifetime"])
        XCTAssertTrue(state.verificationFailed)
        XCTAssertTrue(state.loaded)
        XCTAssertEqual(state.values.map(\.productId), ["premium_lifetime"])
    }

    func testUnrelatedUnverifiedUpdateDoesNotHideVerifiedPurchase() {
        var state = StoreKitEntitlementState()
        state.update(productID: "premium_lifetime", entitlement: entitlement("premium_lifetime"))
        state.reject(productID: "premium_3m")
        XCTAssertFalse(state.verificationFailed)
        XCTAssertEqual(state.values.map(\.productId), ["premium_lifetime"])
    }

    func testRevocationDoesNotTurnAnUnresolvedOtherPurchaseIntoAConfirmedEmptyResult() {
        var state = StoreKitEntitlementState()
        state.refresh(verified: [entitlement("premium_lifetime")], unverifiedProductIDs: ["premium_3m"])
        state.update(productID: "premium_lifetime", entitlement: nil)
        XCTAssertTrue(state.verificationFailed)
        XCTAssertTrue(state.values.isEmpty)
        state.update(productID: "premium_3m", entitlement: nil)
        XCTAssertFalse(state.verificationFailed)
        XCTAssertTrue(state.values.isEmpty)
    }

    func testRecoveryDoesNotRepublishStaleValuesRetainedDuringFailure() {
        var state = StoreKitEntitlementState()
        state.update(productID: "premium_3m", entitlement: entitlement("premium_3m"))
        state.refresh(verified: [], unverifiedProductIDs: ["premium_3m"])
        state.update(productID: "premium_lifetime", entitlement: entitlement("premium_lifetime"))
        XCTAssertFalse(state.verificationFailed)
        XCTAssertEqual(state.values.map(\.productId), ["premium_lifetime"])
    }

    func testVerifiedResultTakesPrecedenceOverUnverifiedResultForSameProduct() {
        var state = StoreKitEntitlementState()
        state.refresh(verified: [entitlement("premium_lifetime")], unverifiedProductIDs: ["premium_lifetime"])
        XCTAssertFalse(state.verificationFailed)
        XCTAssertEqual(state.values.map(\.productId), ["premium_lifetime"])
    }

    func testSuccessfulEmptyRefreshClearsErrorsAndExpiredEntitlements() {
        var state = StoreKitEntitlementState()
        state.update(productID: "premium_3m", entitlement: entitlement("premium_3m"))
        state.reject(productID: "premium_3m")
        state.refresh(verified: [], unverifiedProductIDs: [])
        XCTAssertFalse(state.verificationFailed)
        XCTAssertTrue(state.loaded)
        XCTAssertTrue(state.values.isEmpty)
    }

    private func entitlement(_ productID: String) -> AppleBillingEntitlement {
        AppleBillingEntitlement(
            productId: productID, originalTransactionId: "test-transaction", environment: "Sandbox",
            signedTransactionInfo: "test-verified-jws", willRenew: false, renewalKnown: false)
    }
}
