import XCTest
import StoreKit
import StoreKitTest
import KeyguardShared
@testable import KeyguardUI

/// Run all StoreKit suites serially: their sessions share one simulated App Store.
@MainActor
final class StoreKitBillingTests: XCTestCase {
    private var session: SKTestSession!
    private var bridge: StoreKitBillingBridge!
    private var observation: AppleBillingObservation?
    private var state: AppleBillingState?

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "Keyguard")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        bridge = StoreKitBillingBridge()
        observation = bridge.observeState(productIds: ["premium_3m", "premium_1y", "premium_lifetime"]) { [weak self] in
            self?.state = $0
        }
        try await eventually { self.state?.catalogLoaded == true && self.state?.entitlementsLoaded == true }
    }

    override func tearDown() async throws {
        observation?.cancel()
        observation = nil
        await bridge.stop()
        bridge = nil
        session.clearTransactions()
        session = nil
        state = nil
    }

    func testPurchasePublishesWithoutReopeningPaywallAndRestoreRetainsIt() async throws {
        let result = await purchase("premium_lifetime")
        XCTAssertEqual(result, .success)
        try await eventually { self.owns("premium_lifetime") }
        XCTAssertFalse(try XCTUnwrap(state?.entitlements.first).signedTransactionInfo.isEmpty)
        let restored: AppleBillingOutcome = await withCheckedContinuation { continuation in
            bridge.restore { continuation.resume(returning: $0) }
        }
        XCTAssertEqual(restored, .success)
        XCTAssertTrue(owns("premium_lifetime"))
    }

    func testRefundRemovesLifetimeEntitlement() async throws {
        let result = await purchase("premium_lifetime")
        XCTAssertEqual(result, .success)
        let transaction = try XCTUnwrap(session.allTransactions().first)
        try session.refundTransaction(identifier: transaction.identifier)
        try await waitForStoreKit {
            guard case .verified(let value) = await Transaction.latest(for: "premium_lifetime") else { return false }
            return value.revocationDate != nil
        }
        bridge.refresh()
        try await eventually { !self.owns("premium_lifetime") }
    }

    func testSubscriptionCancellationKeepsAccessUntilExpiry() async throws {
        let result = await purchase("premium_1y")
        XCTAssertEqual(result, .success)
        let transaction = try XCTUnwrap(session.allTransactions().first)
        try session.disableAutoRenewForTransaction(identifier: transaction.identifier)
        let products = try await Product.products(for: ["premium_1y"])
        let group = try XCTUnwrap(products.first?.subscription?.subscriptionGroupID)
        try await waitForStoreKit {
            let statuses = try await Product.SubscriptionInfo.status(for: group)
            return statuses.contains { status in
                guard case .verified(let info) = status.renewalInfo else { return false }
                return !info.willAutoRenew
            }
        }
        bridge.refresh()
        try await eventually {
            self.state?.entitlements.contains { $0.productId == "premium_1y" && $0.renewalKnown && !$0.willRenew }
                == true
        }
        try session.expireSubscription(productIdentifier: "premium_1y")
        try await waitForStoreKit {
            guard case .verified(let value) = await Transaction.latest(for: "premium_1y"),
                let expiry = value.expirationDate
            else { return false }
            return expiry <= .now
        }
        bridge.refresh()
        try await eventually { !self.owns("premium_1y") }
    }

    func testSubscriptionRenewalPreservesPortablePurchaseIdentity() async throws {
        let result = await purchase("premium_1y")
        XCTAssertEqual(result, .success)
        let before = try XCTUnwrap(state?.entitlements.first)
        let transaction = try XCTUnwrap(session.allTransactions().first)
        try session.forceRenewalOfSubscription(productIdentifier: "premium_1y")
        try await waitForStoreKit {
            guard case .verified(let value) = await Transaction.latest(for: "premium_1y") else { return false }
            return value.id != UInt64(transaction.identifier)
        }
        bridge.refresh()
        try await eventually {
            self.state?.entitlements.contains {
                $0.productId == "premium_1y" && $0.signedTransactionInfo != before.signedTransactionInfo
            } == true
        }
        let after = try XCTUnwrap(state?.entitlements.first)
        XCTAssertEqual(after.productId, "premium_1y")
        XCTAssertEqual(after.originalTransactionId, before.originalTransactionId)
    }

    func testPendingPurchaseOnlyUnlocksAfterApproval() async throws {
        session.askToBuyEnabled = true
        let result = await purchase("premium_3m")
        XCTAssertEqual(result, .pending)
        XCTAssertFalse(owns("premium_3m"))
        let transaction = try XCTUnwrap(session.allTransactions().first)
        try session.approveAskToBuyTransaction(identifier: transaction.identifier)
        try await waitForStoreKit {
            guard case .verified = await Transaction.latest(for: "premium_3m") else { return false }
            return true
        }
        bridge.refresh()
        try await eventually { self.owns("premium_3m") }
    }

    func testMissingProductDoesNotHidePurchasedEntitlements() async throws {
        let result = await purchase("premium_lifetime")
        XCTAssertEqual(result, .success)
        let extra = bridge.observeState(productIds: ["missing_product"]) { [weak self] in self?.state = $0 }
        defer { extra.cancel() }
        bridge.refresh()
        try await eventually { self.state?.catalogError != nil }
        XCTAssertTrue(owns("premium_lifetime"))
        XCTAssertEqual(state?.products.count, 3)
    }

    func testCancelledPurchaseIsSilent() async throws {
        // StoreKitTest's error simulator produces an undocumented server error
        // for userCancelled on some runtimes. Exercise the public purchase result.
        observation?.cancel()
        await bridge.stop()
        bridge = StoreKitBillingBridge(purchaseProduct: { _ in .userCancelled })
        observation = bridge.observeState(productIds: ["premium_1y"]) { [weak self] in self?.state = $0 }
        let cancelled = await purchase("premium_1y")
        XCTAssertEqual(cancelled, .cancelled)
        XCTAssertNil(state?.message)
        XCTAssertFalse(owns("premium_1y"))
    }

    func testFailedPurchaseIsVisible() async throws {
        try await session.setSimulatedError(.generic(.notAvailableInStorefront), forAPI: .purchase)
        let failed = await purchase("premium_1y")
        XCTAssertEqual(failed, .failure)
        XCTAssertNotNil(state?.message)
        XCTAssertFalse(owns("premium_1y"))
    }

    func testRestoreFailureKeepsKnownEntitlements() async throws {
        let purchased = await purchase("premium_lifetime")
        XCTAssertEqual(purchased, .success)
        try await session.setSimulatedError(.generic(.notAvailableInStorefront), forAPI: .appStoreSync)
        let result: AppleBillingOutcome = await withCheckedContinuation { continuation in
            bridge.restore { continuation.resume(returning: $0) }
        }
        XCTAssertEqual(result, .failure)
        XCTAssertTrue(owns("premium_lifetime"))
    }

    private func purchase(_ id: String) async -> AppleBillingOutcome {
        await withCheckedContinuation { continuation in
            bridge.purchase(productId: id) { continuation.resume(returning: $0) }
        }
    }

    private func owns(_ id: String) -> Bool {
        state?.entitlements.contains { $0.productId == id } == true
    }

    private enum TestFailure: Error { case timeout }

    // StoreKitTest acknowledges mutations before StoreKit's cached read APIs
    // expose them. Wait for the simulated store to commit, then assert the bridge.
    private func waitForStoreKit(_ condition: @MainActor () async throws -> Bool) async throws {
        let deadline = Date.now.addingTimeInterval(40)
        while Date.now < deadline {
            if try await condition() { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw TestFailure.timeout
    }

    private func eventually(_ condition: @MainActor () -> Bool) async throws {
        let deadline = Date.now.addingTimeInterval(15)
        while !condition() && Date.now < deadline { try await Task.sleep(for: .milliseconds(50)) }
        guard condition() else { throw TestFailure.timeout }
    }
}
