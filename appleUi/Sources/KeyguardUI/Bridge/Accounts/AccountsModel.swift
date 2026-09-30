import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AccountsModel: SnapshotObserving {
    typealias DetailObserver = (String, @escaping (AccountDetailSnapshot) -> Void) -> BridgeObservation

    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observeDetail: DetailObserver

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            observeDetail: { BridgeObservation(core.observeAccountDetail(accountId: $0, onChange: $1)) }
        )
    }

    init(
        coreProvider: @escaping () -> KeyguardCore,
        observeDetail: @escaping DetailObserver
    ) {
        self.coreProvider = coreProvider
        self.observeDetail = observeDetail
    }

    /// The list of accounts shown on top of the Settings screen, produced by the
    /// shared Kotlin `accountListScreenStateProducer` running headless inside
    /// `KeyguardCore`. Only live while the Settings screen is on screen.
    private(set) var accountList: AccountListSnapshot = AccountListSnapshot.companion.empty

    private(set) var syncStatus: SyncStatusSnapshot = SyncStatusSnapshot.companion.empty

    /// Full detail of the selected account, produced by the shared Kotlin
    /// `accountStateProducer` running headless inside `KeyguardCore`. Only live
    /// while an account is selected in the Settings two-pane layout.
    private var accountDetailState = ObservedDetail<AccountDetailSnapshot, String>(
        snapshot: AccountDetailSnapshot.companion.empty)

    var accountDetail: AccountDetailSnapshot { accountDetailState.snapshot }
    var accountDetailIdentity: String? { accountDetailState.identity }

    private let syncStatusObservation = SharedObservation()

    @ObservationIgnored private var syncStatusSubscription: BridgeObservation?

    @ObservationIgnored private var accountListSubscription: BridgeObservation?

    @ObservationIgnored private var accountDetailSubscription: BridgeObservation?

    /// Starts observing the aggregated sync status shown at the bottom of the
    /// main sidebar. Call when the main shell appears; balance with
    /// `stopSyncStatusObservation()` on disappear.
    func startSyncStatusObservation() {
        syncStatusObservation.acquire {
            startObservation(\.syncStatusSubscription, into: \.syncStatus, observe: core.observeSyncStatus)
            return BridgeObservation { [weak self] in
                self?.stopObservation(
                    \.syncStatusSubscription, resetting: \.syncStatus, to: SyncStatusSnapshot.companion.empty)
            }
        }
    }

    func stopSyncStatusObservation() {
        syncStatusObservation.release()
    }

    /// Queues a sync of every account (menu-bar "Sync vault"). Progress surfaces
    /// through the observed `syncStatus`; the shared worker de-dupes if a sync is
    /// already in flight.
    func syncVault() {
        core.syncVault()
    }

    /// Starts running the shared account list producer. Call when the Settings
    /// screen appears; balance with `stopAccountListObservation()` on disappear.
    func startAccountListObservation() {
        startObservation(\.accountListSubscription, into: \.accountList, observe: core.observeAccountList)
    }

    func stopAccountListObservation() {
        stopObservation(\.accountListSubscription, resetting: \.accountList, to: AccountListSnapshot.companion.empty)
    }

    /// Starts running the shared account detail producer for the given account.
    /// Call when an account is selected; balance with `stopAccountDetailObservation()`.
    func startAccountDetailObservation(accountId: String) {
        stopAccountDetailObservation()
        let identity = accountId
        startObservation(\.accountDetailSubscription, into: \.accountDetailState) { onChange in
            observeDetail(accountId) { snapshot in
                onChange(ObservedDetail(snapshot: snapshot, identity: identity))
            }
        }
    }

    func stopAccountDetailObservation() {
        stopObservation(
            \.accountDetailSubscription, resetting: \.accountDetailState,
            to: ObservedDetail(snapshot: AccountDetailSnapshot.companion.empty))
    }

    /// Invokes an account detail item / header context action by its snapshot id.
    func invokeAccountAction(id: String) {
        core.invokeAccountAction(id: id)
    }

    func invokeAccountListAction(id: String) {
        core.invokeAccountListAction(id: id)
    }
}
