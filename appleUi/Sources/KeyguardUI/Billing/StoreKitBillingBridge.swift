import Foundation
import StoreKit
import KeyguardShared
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// One owner per process; observers only subscribe to snapshots, never own StoreKit tasks.
@MainActor
final class StoreKitBillingBridge: AppleBillingBridge {
    static let shared = StoreKitBillingBridge()
    private let purchaseProduct: @MainActor @Sendable (Product) async throws -> Product.PurchaseResult

    init(
        purchaseProduct: @escaping @MainActor @Sendable (Product) async throws -> Product.PurchaseResult = {
            try await $0.purchase()
        }
    ) {
        self.purchaseProduct = purchaseProduct
    }

    private var productIDs = Set<String>()
    private var infos: [AppleBillingProductInfo] = []
    private var entitlementState = StoreKitEntitlementState()
    private var entitlements: [AppleBillingEntitlement] { entitlementState.values }
    private var catalogLoaded = false
    private var entitlementsLoaded: Bool { entitlementState.loaded }
    private var catalogError: String?
    private var entitlementError: String? {
        entitlementState.verificationFailed ? L10n.premiumPurchaseUnverifiedText : nil
    }
    private var busy = false
    private var outcome: AppleBillingOutcome?
    private var message: String?
    private var pendingProductID: String?
    private var observers: [UUID: (AppleBillingState) -> Void] = [:]
    private var transactionTask: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?
    private var catalogTask: Task<Void, Never>?
    private var entitlementRevision = 0

    func start(productIDs: [String]) {
        self.productIDs.formUnion(productIDs)
        guard transactionTask == nil else { return }
        transactionTask = Task { [weak self] in
            for await update in Transaction.updates {
                guard !Task.isCancelled, let self else { break }
                await self.deliver(update)
            }
        }
        statusTask = Task { [weak self] in
            for await status in Product.SubscriptionInfo.Status.updates {
                guard !Task.isCancelled, let self else { break }
                self.deliverStatus(status)
            }
        }
        refresh()
    }

    /// Used when disposing an isolated billing owner (tests); the app singleton lives for the process.
    func stop() async {
        let tasks = [transactionTask, statusTask, catalogTask].compactMap { $0 }
        tasks.forEach { $0.cancel() }
        entitlementRevision += 1
        transactionTask = nil
        statusTask = nil
        catalogTask = nil
        observers.removeAll()
        for task in tasks { await task.value }
    }

    nonisolated func observeState(
        productIds: [String], onChange: @escaping (AppleBillingState) -> Void
    ) -> AppleBillingObservation {
        let id = UUID()
        // The observer is handed to the main actor, which then owns it for the life of
        // the registration; nothing reads it from anywhere else.
        nonisolated(unsafe) let onChange = onChange
        let registration = Task { @MainActor [weak self] in
            guard !Task.isCancelled, let self else { return }
            self.start(productIDs: productIds)
            self.observers[id] = onChange
            onChange(self.snapshot)
        }
        return StoreKitBillingObservation { [weak self] in
            registration.cancel()
            // Discard the removed observer: it would otherwise become the task's
            // (non-Sendable) result type.
            Task { @MainActor [weak self] in _ = self?.observers.removeValue(forKey: id) }
        }
    }

    nonisolated func refresh() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.loadCatalog()
            await self.refreshEntitlements()
        }
    }

    nonisolated func purchase(productId: String, onResult: @escaping (AppleBillingOutcome) -> Void) {
        // Ownership of the Kotlin callback moves to the main-actor task below.
        nonisolated(unsafe) let onResult = onResult
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard self.beginOperation() else { onResult(.cancelled); return }
            defer { self.endOperation() }
            do {
                guard self.productIDs.contains(productId),
                    !self.entitlements.contains(where: { $0.productId == productId }),
                    let product = try await Product.products(for: [productId]).first
                else {
                    self.complete(.failure, L10n.premiumPurchaseProductUnavailableText, onResult)
                    return
                }
                switch try await self.purchaseProduct(product) {
                case .success(let result):
                    if await self.deliver(result) {
                        self.complete(.success, L10n.premiumPurchaseSuccessText, onResult)
                    } else {
                        self.complete(.failure, L10n.premiumPurchaseUnverifiedText, onResult)
                    }
                case .userCancelled:
                    self.complete(.cancelled, nil, onResult)
                case .pending:
                    self.pendingProductID = productId
                    self.complete(.pending, L10n.premiumPurchasePendingText, onResult)
                @unknown default:
                    self.complete(.failure, L10n.premiumPurchaseFailedText, onResult)
                }
            } catch StoreKitError.userCancelled {
                self.complete(.cancelled, nil, onResult)
            } catch {
                self.complete(.failure, error.localizedDescription, onResult)
            }
        }
    }

    nonisolated func restore(onResult: @escaping (AppleBillingOutcome) -> Void) {
        // Ownership of the Kotlin callback moves to the main-actor task below.
        nonisolated(unsafe) let onResult = onResult
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard self.beginOperation() else { onResult(.cancelled); return }
            defer { self.endOperation() }
            do {
                try await AppStore.sync()
                await self.refreshEntitlements()
                self.loadCatalog()
                if let error = self.entitlementError {
                    self.complete(.failure, error, onResult)
                } else {
                    self.complete(
                        .success,
                        self.entitlements.isEmpty
                            ? L10n.premiumPurchaseRestoreEmptyText : L10n.premiumPurchaseRestoreSuccessText, onResult)
                }
            } catch StoreKitError.userCancelled {
                self.complete(.cancelled, nil, onResult)
            } catch {
                self.complete(.failure, error.localizedDescription, onResult)
            }
        }
    }

    nonisolated func manageSubscriptions() {
        Task { @MainActor [weak self] in
            guard let self, self.beginOperation() else { return }
            defer { self.endOperation() }
            do {
                #if os(iOS)
                guard
                    let scene = UIApplication.shared.connectedScenes
                        .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene
                else {
                    self.complete(.failure, L10n.premiumPurchaseManageFailedText) { _ in }
                    return
                }
                try await AppStore.showManageSubscriptions(in: scene)
                #else
                guard let url = URL(string: KeyguardUrls.shared.MAC_APP_STORE_SUBSCRIPTIONS),
                    NSWorkspace.shared.open(url)
                else {
                    self.complete(.failure, L10n.premiumPurchaseManageFailedText) { _ in }
                    return
                }
                #endif
                await self.refreshEntitlements()
            } catch {
                self.complete(.failure, error.localizedDescription) { _ in }
            }
        }
    }

    @discardableResult
    private func deliver(_ result: VerificationResult<Transaction>) async -> Bool {
        guard case .verified(let transaction) = result else {
            if case .unverified(let transaction, _) = result, productIDs.contains(transaction.productID) {
                entitlementRevision += 1
                entitlementState.reject(productID: transaction.productID)
                publish()
            }
            return false
        }
        guard productIDs.contains(transaction.productID) else { return false }
        // An expired subscription may still be in billing grace. Resolve its
        // current status before changing access, rather than briefly revoking it.
        if transaction.revocationDate == nil, !transaction.isUpgraded,
            let expiration = transaction.expirationDate, expiration <= .now
        {
            await refreshEntitlements()
            await transaction.finish()
            loadCatalog()
            return true
        }
        // Deliver the verified purchase itself. currentEntitlements can lag behind
        // the purchase callback, and an older refresh must not undo this grant.
        entitlementRevision += 1
        let previous = entitlements.first { $0.productId == transaction.productID }
        var entitlement: AppleBillingEntitlement?
        if transaction.revocationDate == nil && !transaction.isUpgraded
            && (transaction.expirationDate.map { $0 > .now } ?? true)
        {
            entitlement = AppleBillingEntitlement(
                productId: transaction.productID,
                originalTransactionId: String(transaction.originalID),
                environment: transaction.environment.rawValue,
                signedTransactionInfo: result.jwsRepresentation,
                willRenew: previous?.willRenew ?? false,
                renewalKnown: previous?.renewalKnown ?? false
            )
        }
        entitlementState.update(productID: transaction.productID, entitlement: entitlement)
        resolvePendingPurchase()
        publish()
        await transaction.finish()
        loadCatalog()  // Refresh introductory-offer eligibility independently of access.
        return true
    }

    private func loadCatalog() {
        guard catalogTask == nil else { return }
        catalogTask = Task { [weak self] in
            guard let self else { return }
            defer { self.catalogTask = nil }
            do {
                let loaded = try await Product.products(for: Array(self.productIDs))
                var infos: [AppleBillingProductInfo] = []
                for product in loaded {
                    guard product.type == .autoRenewable || product.type == .nonConsumable else { continue }
                    var trial: String?
                    if let subscription = product.subscription,
                        let intro = subscription.introductoryOffer,
                        intro.paymentMode == .freeTrial,
                        await subscription.isEligibleForIntroOffer
                    {
                        trial = intro.period.iso8601(multiplier: intro.periodCount)
                    }
                    infos.append(
                        AppleBillingProductInfo(
                            id: product.id, displayName: product.displayName, description: product.description,
                            displayPrice: product.displayPrice, isSubscription: product.type == .autoRenewable,
                            subscriptionPeriodIso: product.subscription?.subscriptionPeriod.iso8601(),
                            introTrialPeriodIso: trial
                        ))
                }
                self.infos = infos
                self.catalogError =
                    self.productIDs.isSubset(of: Set(infos.map(\.id)))
                    ? nil : L10n.premiumPurchaseProductUnavailableText
            } catch {
                self.catalogError = error.localizedDescription
            }
            self.catalogLoaded = true
            self.publish()
        }
    }

    private func deliverStatus(_ status: Product.SubscriptionInfo.Status) {
        guard case .verified(let transaction) = status.transaction,
            productIDs.contains(transaction.productID),
            case .verified(let renewal) = status.renewalInfo
        else { return }
        entitlementRevision += 1
        var entitlement: AppleBillingEntitlement?
        if (status.state == .subscribed || status.state == .inGracePeriod),
            transaction.revocationDate == nil, !transaction.isUpgraded
        {
            entitlement = AppleBillingEntitlement(
                productId: transaction.productID,
                originalTransactionId: String(transaction.originalID),
                environment: transaction.environment.rawValue,
                signedTransactionInfo: status.transaction.jwsRepresentation,
                willRenew: renewal.willAutoRenew,
                renewalKnown: true
            )
        }
        entitlementState.update(productID: transaction.productID, entitlement: entitlement)
        resolvePendingPurchase()
        publish()
    }

    private func refreshEntitlements() async {
        entitlementRevision += 1
        let revision = entitlementRevision
        var current: [String: (Transaction, String)] = [:]
        var groups = Set<String>()
        var unverifiedProductIDs = Set<String>()
        for await result in Transaction.currentEntitlements {
            switch result {
            case .verified(let transaction):
                guard productIDs.contains(transaction.productID), transaction.revocationDate == nil,
                    !transaction.isUpgraded
                else { continue }
                current[transaction.productID] = (transaction, result.jwsRepresentation)
                if let group = transaction.subscriptionGroupID { groups.insert(group) }
            case .unverified(let transaction, _):
                if productIDs.contains(transaction.productID) { unverifiedProductIDs.insert(transaction.productID) }
            }
        }
        // Query each recognized product's latest signed transaction as well. This
        // preserves ownership when the aggregate sequence has not caught up with
        // a just-completed purchase/restore, without relying on product metadata.
        for id in productIDs {
            guard let result = await Transaction.latest(for: id) else { continue }
            guard case .verified(let transaction) = result else {
                unverifiedProductIDs.insert(id)
                continue
            }
            unverifiedProductIDs.remove(id)
            if let group = transaction.subscriptionGroupID { groups.insert(group) }
            if transaction.revocationDate != nil || transaction.isUpgraded {
                current.removeValue(forKey: id)
            } else if transaction.productType == .nonConsumable
                || (transaction.productType == .autoRenewable && transaction.expirationDate.map { $0 > .now } == true)
            {
                current[id] = (transaction, result.jwsRepresentation)
            }
        }
        var renewal: [String: Bool] = [:]
        for group in groups {
            guard let statuses = try? await Product.SubscriptionInfo.status(for: group) else { continue }
            for status in statuses {
                guard case .verified(let info) = status.renewalInfo,
                    case .verified(let transaction) = status.transaction,
                    productIDs.contains(transaction.productID)
                else { continue }
                unverifiedProductIDs.remove(transaction.productID)
                renewal[transaction.productID] = info.willAutoRenew
                if (status.state == .subscribed || status.state == .inGracePeriod),
                    transaction.revocationDate == nil, !transaction.isUpgraded
                {
                    current[transaction.productID] = (transaction, status.transaction.jwsRepresentation)
                } else {
                    current.removeValue(forKey: transaction.productID)
                }
            }
        }
        guard revision == entitlementRevision else { return }
        entitlementState.refresh(
            verified: current.values.map { transaction, jws in
                AppleBillingEntitlement(
                    productId: transaction.productID,
                    originalTransactionId: String(transaction.originalID),
                    environment: transaction.environment.rawValue,
                    signedTransactionInfo: jws,
                    willRenew: renewal[transaction.productID] ?? false,
                    renewalKnown: renewal[transaction.productID] != nil
                )
            },
            unverifiedProductIDs: unverifiedProductIDs
        )
        if !entitlementState.verificationFailed { resolvePendingPurchase() }
        publish()
    }

    private func resolvePendingPurchase() {
        guard let id = pendingProductID, entitlements.contains(where: { $0.productId == id }) else { return }
        pendingProductID = nil
        outcome = .success
        message = L10n.premiumPurchaseSuccessText
    }

    private func beginOperation() -> Bool {
        guard !busy else { return false }
        busy = true
        outcome = nil
        message = nil
        publish()
        return true
    }

    private func endOperation() { busy = false; publish() }

    private func complete(_ result: AppleBillingOutcome, _ text: String?, _ callback: (AppleBillingOutcome) -> Void) {
        outcome = result
        message = text
        publish()
        callback(result)
    }

    private var snapshot: AppleBillingState {
        AppleBillingState(
            products: infos, catalogLoaded: catalogLoaded, catalogError: catalogError,
            entitlements: entitlements, entitlementsLoaded: entitlementsLoaded,
            entitlementError: entitlementError, busy: busy, outcome: outcome, message: message
        )
    }

    private func publish() {
        let value = snapshot
        for observer in Array(observers.values) { observer(value) }
    }
}

@MainActor
func installBillingBridge() {
    // Direct-distribution builds do not sell redundant purchases or start StoreKit.
    guard Bundle.main.object(forInfoDictionaryKey: "KeyguardDistribution") as? String != "direct" else { return }
    let bridge = StoreKitBillingBridge.shared
    bridge.start(productIDs: AppleBillingProducts.shared.ALL_IDS)
    AppleBillingBridgeKt.registerAppleBillingBridge(bridge: bridge)
}
