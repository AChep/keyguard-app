import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class SubscriptionsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    private(set) var subscriptions: SubscriptionsSnapshot = SubscriptionsSnapshot.companion.empty

    @ObservationIgnored private var subscriptionsSubscription: BridgeObservation?

    func startSubscriptionsObservation() {
        startObservation(\.subscriptionsSubscription, into: \.subscriptions, observe: core.observeSubscriptions)
    }

    func stopSubscriptionsObservation() {
        stopObservation(
            \.subscriptionsSubscription, resetting: \.subscriptions, to: SubscriptionsSnapshot.companion.empty)
    }

    /// Launches the StoreKit purchase flow for a subscription or product id.
    func purchase(id: String) {
        core.purchaseSubscription(id: id)
    }

    /// Restores past purchases (`AppStore.sync()`).
    func restorePurchases() {
        core.restorePurchases()
    }

    func manageSubscriptions() {
        core.manageSubscriptions()
    }

    func refreshBilling() { core.refreshBilling() }

    func syncAppleLicense() { core.syncAppleLicense() }

    func linkAppleLicense(_ value: String) { core.linkAppleLicense(value: value) }

    func removeAppleLicense() { core.removeAppleLicense() }

    func refreshAppleLicense() { core.refreshAppleLicense() }
}
