import Foundation
import StoreKit
import StoreKitTest

/// Controls Apple's local test store. Purchases themselves still use the app UI.
@MainActor
final class StoreKitFixture {
    private var session: SKTestSession?
    private let productIDs = ["premium_3m", "premium_1y", "premium_lifetime"]

    func execute(_ action: String, command: [String: Any]) async throws -> [String: Any] {
        if action == "storekitStart" {
            guard let url = Bundle(for: NativeE2e.self).url(forResource: "Keyguard", withExtension: "storekit") else {
                throw FixtureError("Bundled local StoreKit configuration is missing")
            }
            let session = try SKTestSession(contentsOf: url)
            session.resetToDefaultState()
            session.clearTransactions()
            session.disableDialogs = false
            session.timeRate = .realTime
            self.session = session
            let products = try await Product.products(for: productIDs)
            return [
                "configuration": url.lastPathComponent,
                "configuredProductIds": productIDs,
                "runnerProducts": products.map { ["id": $0.id, "price": $0.displayPrice] },
                "transactions": transactions(session),
            ]
        }
        guard let session else { throw FixtureError("Start the local StoreKit fixture first") }
        switch action {
        case "storekitStatus":
            break
        case "storekitConfigure":
            if let value = command["disableDialogs"] as? Bool { session.disableDialogs = value }
            if let value = command["askToBuy"] as? Bool { session.askToBuyEnabled = value }
            if let value = command["failPurchase"] as? Bool {
                try await session.setSimulatedError(
                    value ? .generic(.notAvailableInStorefront) : nil, forAPI: .purchase)
            }
            if let value = command["failRestore"] as? Bool {
                try await session.setSimulatedError(
                    value ? .generic(.notAvailableInStorefront) : nil, forAPI: .appStoreSync)
            }
        case "storekitRefund":
            try session.refundTransaction(identifier: transactionID(command, session: session))
        case "storekitDisableAutoRenew":
            try session.disableAutoRenewForTransaction(identifier: transactionID(command, session: session))
        case "storekitApprove":
            try session.approveAskToBuyTransaction(identifier: transactionID(command, session: session))
        case "storekitDecline":
            try session.declineAskToBuyTransaction(identifier: transactionID(command, session: session))
        case "storekitRenew", "storekitExpire":
            guard let productID = command["productId"] as? String,
                ["premium_3m", "premium_1y"].contains(productID)
            else { throw FixtureError("Expected a configured subscription product") }
            if action == "storekitRenew" {
                try session.forceRenewalOfSubscription(productIdentifier: productID)
            } else {
                try session.expireSubscription(productIdentifier: productID)
            }
        case "storekitReset":
            session.clearTransactions()
            session.resetToDefaultState()
        default:
            throw FixtureError("Unknown local StoreKit action")
        }
        return [
            "transactions": transactions(session),
            "disableDialogs": session.disableDialogs,
            "askToBuy": session.askToBuyEnabled,
        ]
    }

    private func transactionID(_ command: [String: Any], session: SKTestSession) throws -> UInt {
        guard let value = command["transactionId"] as? Int, let identifier = UInt(exactly: value),
            session.allTransactions().contains(where: { $0.identifier == identifier })
        else { throw FixtureError("Expected an existing local test transaction identifier") }
        return identifier
    }

    private func transactions(_ session: SKTestSession) -> [[String: Any]] {
        session.allTransactions().map { transaction in
            var row: [String: Any] = [
                "id": transaction.identifier,
                "originalId": transaction.originalTransactionIdentifier,
                "productId": transaction.productIdentifier,
                "state": transaction.state.rawValue,
                "autoRenew": transaction.autoRenewingEnabled,
                "pendingApproval": transaction.pendingAskToBuyConfirmation,
                "hasPurchaseIssue": transaction.hasPurchaseIssue,
            ]
            if let expiry = transaction.expirationDate { row["expiresAt"] = expiry.timeIntervalSince1970 }
            if let cancelled = transaction.cancelDate { row["cancelledAt"] = cancelled.timeIntervalSince1970 }
            return row
        }
    }

    private struct FixtureError: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }
}
