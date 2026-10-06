import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AccountsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    func makeDetailSession(accountId: String) -> AccountDetailSession {
        core.makeAccountDetailSession(accountId: accountId)
    }

    /// Only live while the Settings screen is on screen.
    private(set) var accountList: AccountListSnapshot = AccountListSnapshot.companion.empty

    private(set) var syncStatus: SyncStatusSnapshot = SyncStatusSnapshot.companion.empty

    private let listObservation = SharedObservation()
    private let syncStatusObservation = SharedObservation()

    @ObservationIgnored private var syncStatusSubscription: BridgeObservation?

    @ObservationIgnored private var accountListSubscription: BridgeObservation?

    /// Call when the main shell appears; balance with `stopSyncStatusObservation()`.
    func startSyncStatusObservation() {
        syncStatusObservation.acquire {
            sharedSnapshotObservation(
                \.syncStatusSubscription, into: \.syncStatus, empty: SyncStatusSnapshot.companion.empty,
                observe: core.observeSyncStatus)
        }
    }

    func stopSyncStatusObservation() {
        syncStatusObservation.release()
    }

    /// Queues a sync of every account. Progress surfaces through the observed
    /// `syncStatus`; the shared worker de-dupes if a sync is already in flight.
    func syncVault() {
        core.syncVault()
    }

    /// Call when the Settings screen appears; balance with `stopAccountListObservation()`.
    func startAccountListObservation() {
        listObservation.acquire {
            sharedSnapshotObservation(
                \.accountListSubscription, into: \.accountList, empty: AccountListSnapshot.companion.empty,
                observe: core.observeAccountList)
        }
    }

    func stopAccountListObservation() {
        listObservation.release()
    }

    func invokeAccountListAction(id: String) {
        core.invokeAccountListAction(id: id)
    }
}
