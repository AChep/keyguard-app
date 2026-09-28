import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class CipherDetailModel: SnapshotObserving {
    typealias PasswordHistoryObserver = (String, @escaping (PasswordHistorySnapshot) -> Void) -> BridgeObservation

    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observePasswordHistory: PasswordHistoryObserver

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            observePasswordHistory: { BridgeObservation(core.observePasswordHistory(itemId: $0, onChange: $1)) }
        )
    }

    init(
        coreProvider: @escaping () -> KeyguardCore,
        observePasswordHistory: @escaping PasswordHistoryObserver
    ) {
        self.coreProvider = coreProvider
        self.observePasswordHistory = observePasswordHistory
    }

    /// Current selected-vault-item detail.
    private(set) var detail: VaultDetailSnapshot = VaultDetailSnapshot.companion.empty

    /// Live TOTP badges of the selected item, on their own channel so the
    /// per-second countdown invalidates only the badge rows (see `totpState`).
    private(set) var detailTotp: VaultDetailTotpSnapshot?

    /// Password history of a single cipher, produced by the shared Kotlin
    /// `vaultViewPasswordHistoryScreenStateProducer` running headless inside
    /// `KeyguardCore`. Only live while the password history sheet is presented.
    private(set) var passwordHistory: PasswordHistorySnapshot = PasswordHistorySnapshot.companion.empty

    @ObservationIgnored private var detailSubscription: BridgeObservation?

    @ObservationIgnored private var passwordHistorySubscription: BridgeObservation?

    /// Starts the single long-lived root-detail observer. Idempotent; call when the
    /// home screen appears (it swaps targets in place via `setDetailTarget`), and
    /// balance with `stopDetailObservation()` on disappear.
    func startDetailObservation() {
        guard detailSubscription == nil else { return }
        detailSubscription = BridgeObservation(
            core.observeCipherDetail(
                onChange: { [weak self] snapshot in
                    guard let self, self.detail != snapshot else { return }
                    self.detail = snapshot
                },
                onTotpChange: { [weak self] totp in
                    self?.detailTotp = totp
                }
            ))
    }

    /// The live badge of TOTP row `rowId` of cipher `cipherId`, or `nil` while the
    /// channel still carries another cipher's codes.
    func totpState(cipherId: String, rowId: String) -> TotpFieldSnapshot? {
        guard let detailTotp, detailTotp.cipherId == cipherId else { return nil }
        return detailTotp.states[rowId]
    }

    func setDetailTarget(itemId: String?, accountId: String?) {
        core.setDetailTarget(itemId: itemId, accountId: accountId)
    }

    func stopDetailObservation() {
        detailSubscription?.cancel()
        detailSubscription = nil
        core.setDetailTarget(itemId: nil, accountId: nil)
        detail = VaultDetailSnapshot.companion.empty
        detailTotp = nil
    }

    /// Invokes a vault detail item action (copy / open / toggle / retry) by its
    /// snapshot id. The closure runs inside the shared producer.
    func invokeVaultAction(id: String) {
        core.invokeVaultAction(id: id)
    }

    /// Toggles the favourite flag of the currently observed cipher.
    func toggleVaultFavorite() {
        core.toggleVaultFavorite()
    }

    /// Starts running the shared password history producer for the given cipher.
    /// Call when the password history sheet appears; balance with
    /// `stopPasswordHistoryObservation()` on dismiss.
    func startPasswordHistoryObservation(itemId: String) {
        stopPasswordHistoryObservation()
        startObservation(\.passwordHistorySubscription, into: \.passwordHistory) { onChange in
            observePasswordHistory(itemId, onChange)
        }
    }

    func stopPasswordHistoryObservation() {
        stopObservation(
            \.passwordHistorySubscription, resetting: \.passwordHistory, to: PasswordHistorySnapshot.companion.empty)
    }

    func invokePasswordHistoryItemAction(id: String) {
        core.invokePasswordHistoryItemAction(id: id)
    }

    /// Runs a bulk action of the active password-history multi-selection (Delete).
    func invokePasswordHistorySelectionAction(id: String) {
        core.invokePasswordHistorySelectionAction(id: id)
    }

    /// Runs a top-level password-history action (the "Clear history" action).
    func invokePasswordHistoryAction(id: String) {
        core.invokePasswordHistoryAction(id: id)
    }

    /// Toggles whether the password-history row with `id` is part of the multi-selection.
    func togglePasswordHistorySelection(id: String) {
        core.togglePasswordHistorySelection(itemId: id)
    }

    /// Clears the active password-history multi-selection.
    func clearPasswordHistorySelection() {
        core.clearPasswordHistorySelection()
    }
}
