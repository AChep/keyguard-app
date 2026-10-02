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

    @ObservationIgnored private let subscriptionsObservation = SharedObservation()

    @ObservationIgnored private var subscriptionsSubscription: BridgeObservation?

    func startSubscriptionsObservation() {
        subscriptionsObservation.acquire {
            sharedSnapshotObservation(
                \.subscriptionsSubscription, into: \.subscriptions, empty: SubscriptionsSnapshot.companion.empty,
                observe: core.observeSubscriptions)
        }
    }

    func stopSubscriptionsObservation() {
        subscriptionsObservation.release()
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
