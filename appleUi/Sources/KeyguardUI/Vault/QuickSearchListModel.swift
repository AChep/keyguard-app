import Foundation
import Observation
import KeyguardShared

/// List model for Quick Search results. Selection stays in the Quick Search
/// controller; rows are rendered through the shared vault-list components.
@MainActor
@Observable
final class QuickSearchListModel: VaultRowListModel {
    let store = VaultRowStore()

    private(set) var totpStates: [String: TotpFieldSnapshot] = [:]

    @ObservationIgnored private let core: KeyguardCore
    /// Copies a field ("password" / "otp") of a cipher.
    @ObservationIgnored private let onCopy: (_ secretId: String, _ accountId: String, _ field: String) -> Void

    @ObservationIgnored private lazy var pump = VaultDeltaPump(store: store)
    @ObservationIgnored private var subscriptions: [KeyguardCancellable] = []
    @ObservationIgnored private var started = false

    init(
        core: KeyguardCore,
        onCopy: @escaping (_ secretId: String, _ accountId: String, _ field: String) -> Void
    ) {
        self.core = core
        self.onCopy = onCopy
    }

    // MARK: - Lifecycle

    func start() {
        guard !started else { return }
        started = true

        let continuation = pump.start()

        // THE LIST. Background-delivered BY DESIGN (like the main list / Recents):
        // convert off-main, then one ordered hop to Main via the pump. Do not touch
        // any observable state directly here.
        subscriptions.append(
            core.observeQuickSearchListDelta { bridged in
                assert(
                    !Thread.isMainThread,
                    "observeQuickSearchListDelta must deliver off-main by design; converting on Main defeats the contract"
                )
                continuation.yield(VaultDelta(bridged: bridged))
            })

        // THE SMALL CHANNEL. Main-delivered; assign directly under `assumeIsolated`
        // (it traps if a callback ever arrives off-main).
        subscriptions.append(
            core.observeQuickSearchTotp { [weak self] states in
                MainActor.assumeIsolated { self?.totpStates = states }
            })
    }

    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
        pump.stop()
        started = false
        totpStates = [:]
    }

    // MARK: - VaultRowListModel commands

    /// A row tap selects the result — the producer updates its `selectedItemId`
    /// (fed back as `config.selectedRowId`) and populates the detail pane. It
    /// does NOT open.
    func selectRow(rowId: String) {
        core.selectQuickSearchItem(id: rowId)
    }

    /// The shared item row dispatches its TOTP badge tap here as `VaultActions.copyOtp`.
    func performVaultRowAction(rowId: String, actionId: String) {
        if actionId == VaultActions.copyOtp {
            copy(rowId: rowId, field: "otp")
        }
    }

    /// A password badge tap copies the password; other badge kinds are inert.
    func performVaultBadgeTap(rowId: String, badgeId: String) {
        if badgeId.hasPrefix("password.") {
            copy(rowId: rowId, field: "password")
        }
    }

    // MARK: - Internals

    /// A row with no content box (or a non-cipher row) is a silent no-op.
    private func copy(rowId: String, field: String) {
        guard let row = store.box(for: rowId).row,
            let secretId = row.secretId,
            let accountId = row.accountId
        else { return }
        onCopy(secretId, accountId, field)
    }
}
