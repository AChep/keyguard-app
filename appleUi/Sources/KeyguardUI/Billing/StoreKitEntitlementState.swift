import KeyguardShared

/// Verification failures concern individual products. A verified purchase of
/// another product still grants access, including during restore.
struct StoreKitEntitlementState {
    private(set) var values: [AppleBillingEntitlement] = []
    private(set) var loaded = false
    private var verifiedProductIDs = Set<String>()
    private var unverifiedProductIDs = Set<String>()

    var verificationFailed: Bool {
        verifiedProductIDs.isEmpty && !unverifiedProductIDs.isEmpty
    }

    mutating func refresh(verified: [AppleBillingEntitlement], unverifiedProductIDs: Set<String>) {
        verifiedProductIDs = Set(verified.map(\.productId))
        self.unverifiedProductIDs = unverifiedProductIDs.subtracting(verifiedProductIDs)
        // Preserve the last known values while an unverified-only refresh fails.
        // The error remains visible and Kotlin keeps its existing premium cache.
        guard !verificationFailed else { return }
        values = verified
        loaded = true
    }

    mutating func reject(productID: String) {
        unverifiedProductIDs.insert(productID)
        verifiedProductIDs.remove(productID)
        if !verificationFailed {
            values.removeAll { $0.productId == productID }
        }
    }

    mutating func update(productID: String, entitlement: AppleBillingEntitlement?) {
        unverifiedProductIDs.remove(productID)
        verifiedProductIDs.remove(productID)
        // An earlier failed refresh may have retained stale values for display.
        // Only carry independently verified products into a successful update.
        values.removeAll { !verifiedProductIDs.contains($0.productId) }
        if let entitlement {
            verifiedProductIDs.insert(productID)
            values.append(entitlement)
        }
        loaded = true
    }
}
