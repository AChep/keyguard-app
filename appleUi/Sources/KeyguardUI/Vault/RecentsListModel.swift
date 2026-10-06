import Foundation
import Observation
import KeyguardShared

/// List model for the Recents sheet using the shared vault-list renderers.
@MainActor
@Observable
final class RecentsListModel: VaultRowListModel, SnapshotObserving {
    let store = VaultRowStore()

    private(set) var totpStates: [String: TotpFieldSnapshot] = [:]

    /// The tab bar titles (pre-localized) + current selection, on their own
    /// on-main channel so the segmented picker never rides the item projection.
    private(set) var tabs: [RecentsTabSnapshot] = []
    private(set) var selectedTabKey: String = ""
    /// `true` once the shared producer is live (an unlocked vault) — the sheet
    /// shows a spinner until then, then either the list or the empty state.
    private(set) var loaded = false

    @ObservationIgnored private let makeSession: () -> any RecentsSessionSource
    /// Copies a field ("password" / "username" / "otp") of a cipher.
    @ObservationIgnored private let onCopy: (_ secretId: String, _ accountId: String, _ field: String) -> Void
    /// Reveals a cipher in the vault list and dismisses the sheet.
    @ObservationIgnored private let onReveal: (_ secretId: String) -> Void

    @ObservationIgnored private lazy var pump = VaultDeltaPump(store: store)
    @ObservationIgnored private var session: (any RecentsSessionSource)?
    @ObservationIgnored private var subscription: BridgeObservation?

    private enum RowAction {
        static let copyPassword = "recents.copyPassword"
        static let copyUsername = "recents.copyUsername"
        static let copyOtp = VaultActions.copyOtp
        static let reveal = "recents.reveal"
    }

    convenience init(
        core: KeyguardCore,
        onCopy: @escaping (_ secretId: String, _ accountId: String, _ field: String) -> Void,
        onReveal: @escaping (_ secretId: String) -> Void
    ) {
        self.init(makeSession: { core.makeRecentsSession() }, onCopy: onCopy, onReveal: onReveal)
    }

    init(
        makeSession: @escaping () -> any RecentsSessionSource,
        onCopy: @escaping (_ secretId: String, _ accountId: String, _ field: String) -> Void,
        onReveal: @escaping (_ secretId: String) -> Void
    ) {
        self.makeSession = makeSession
        self.onCopy = onCopy
        self.onReveal = onReveal
    }

    // MARK: - Lifecycle

    /// Call on appear; balance with `stop()` on disappear. No-op while already started.
    func start() {
        guard subscription == nil else { return }
        let session = makeSession()
        self.session = session
        let continuation = pump.start()
        startObservation(\.subscription) { deliver in
            let observation = session.subscribe(
                onListDelta: { continuation.yield($0) },
                onTabs: { snapshot in
                    deliver {
                        $0.loaded = snapshot.loaded
                        $0.tabs = snapshot.tabs
                        $0.selectedTabKey = snapshot.selectedTabKey
                    }
                },
                onTotp: { states in deliver { $0.totpStates = states } }
            )
            return BridgeObservation {
                continuation.finish()
                observation.cancel()
            }
        }
    }

    func stop() {
        stopObservation(\.subscription)
        session = nil
        pump.stop()
        totpStates = [:]
        tabs = []
        selectedTabKey = ""
        loaded = false
    }

    /// Selects a Recents tab by its `RecentsTabSnapshot.key`. The shared producer
    /// persists the choice and re-emits the matching items.
    func setTab(key: String) {
        session?.setTab(key: key)
    }

    // MARK: - VaultRowListModel commands

    func copyPrimaryRow(rowId: String) {
        copy(rowId: rowId, field: "password")
    }

    /// The shared item row dispatches its TOTP badge tap here as `VaultActions.copyOtp`.
    func performVaultRowAction(rowId: String, actionId: String) {
        switch actionId {
        case RowAction.copyPassword:
            copy(rowId: rowId, field: "password")
        case RowAction.copyUsername:
            copy(rowId: rowId, field: "username")
        case RowAction.copyOtp:
            copy(rowId: rowId, field: "otp")
        case RowAction.reveal:
            reveal(rowId: rowId)
        default:
            vaultLog("RecentsListModel: unknown row action '\(actionId)' for row '\(rowId)'")
        }
    }

    /// A password badge tap copies the password (the other badge kinds have no
    /// tap in Recents — the producer nulls them).
    func performVaultBadgeTap(rowId: String, badgeId: String) {
        if badgeId.hasPrefix("password.") {
            copy(rowId: rowId, field: "password")
        }
    }

    /// Synthesized Swift-side: Recents actions are not Kotlin descriptors.
    func rowActions(rowId: String) async -> [VaultAction] {
        guard subscription != nil, loaded else { return [] }
        return [
            VaultAction(id: RowAction.copyPassword, title: L10n.copyPassword, symbol: "key", isCopy: true),
            VaultAction(id: RowAction.copyUsername, title: L10n.copyUsername, symbol: "person", isCopy: true),
            VaultAction(id: RowAction.copyOtp, title: L10n.copyOtpCode, symbol: "clock", isCopy: true),
            VaultAction(
                id: RowAction.reveal, title: L10n.vaultRecentsRevealAction, symbol: "macwindow", startsSection: true),
        ]
    }

    // MARK: - Internals

    /// A row with no content box (or a non-cipher row) is a silent no-op.
    private func copy(rowId: String, field: String) {
        guard subscription != nil, loaded, let row = store.boxes[rowId]?.row,
            let secretId = row.secretId,
            let accountId = row.accountId
        else { return }
        onCopy(secretId, accountId, field)
    }

    private func reveal(rowId: String) {
        guard subscription != nil, loaded, let secretId = store.boxes[rowId]?.row?.secretId else { return }
        onReveal(secretId)
    }
}
