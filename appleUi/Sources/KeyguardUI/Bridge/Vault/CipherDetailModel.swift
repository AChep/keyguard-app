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

    private(set) var detail: VaultDetailSnapshot = VaultDetailSnapshot.companion.empty

    /// Live TOTP badges of the selected item, on their own channel so the
    /// per-second countdown invalidates only the badge rows.
    private(set) var detailTotp: VaultDetailTotpSnapshot?

    /// Only live while the password history sheet is presented.
    private(set) var passwordHistory: PasswordHistorySnapshot = PasswordHistorySnapshot.companion.empty

    @ObservationIgnored private var detailSubscription: BridgeObservation?

    @ObservationIgnored private var passwordHistorySubscription: BridgeObservation?

    /// The single long-lived root-detail observer; it swaps targets in place via
    /// `setDetailTarget`. Idempotent; call when the home screen appears and balance
    /// with `stopDetailObservation()`.
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

    func invokeVaultAction(id: String) {
        core.invokeVaultAction(id: id)
    }

    func toggleVaultFavorite() {
        core.toggleVaultFavorite()
    }

    /// Call when the password history sheet appears; balance with
    /// `stopPasswordHistoryObservation()`.
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

    func invokePasswordHistorySelectionAction(id: String) {
        core.invokePasswordHistorySelectionAction(id: id)
    }

    /// Runs a top-level password-history action ("Clear history").
    func invokePasswordHistoryAction(id: String) {
        core.invokePasswordHistoryAction(id: id)
    }

    func togglePasswordHistorySelection(id: String) {
        core.togglePasswordHistorySelection(itemId: id)
    }

    func clearPasswordHistorySelection() {
        core.clearPasswordHistorySelection()
    }
}
