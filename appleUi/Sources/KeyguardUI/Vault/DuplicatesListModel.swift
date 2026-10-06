import Foundation
import Observation
import KeyguardShared

/// List model for the Duplicates screen. It uses the shared row renderers and
/// keeps group-scoped selection in the projected row flags.
@MainActor
@Observable
final class DuplicatesListModel: VaultRowListModel {
    let store = VaultRowStore()

    private(set) var selection: VaultSelection = .empty

    var loaded: Bool { store.structure.revision != 0 }

    @ObservationIgnored private let session: DuplicatesSession
    @ObservationIgnored private lazy var pump = VaultDeltaPump(store: store)
    @ObservationIgnored private var subscriptions: [KeyguardCancellable] = []
    @ObservationIgnored private var started = false

    private enum RowAction {
        static let toggleSelect = "duplicates.toggleSelect"
    }

    /// Drives the renderers from a Kotlin-owned `DuplicatesSession`; `stop()`
    /// cancels the subscriptions but never `close()`s the session (the owning
    /// navigation-stack entry does that in its teardown).
    init(session: DuplicatesSession) {
        self.session = session
    }

    // MARK: - Lifecycle

    /// Call on appear; balance with `stop()` on disappear. No-op while already started.
    func start() {
        guard !started else { return }
        started = true

        let continuation = pump.start()

        // THE LIST. Background-delivered BY DESIGN (like the main list): convert
        // off-main, then one ordered hop to Main via the pump. Do not touch any
        // observable state directly here.
        subscriptions.append(
            session.observeListDelta { bridged in
                assert(
                    !Thread.isMainThread,
                    "observeListDelta must deliver off-main by design; converting on Main defeats the contract"
                )
                continuation.yield(VaultDelta(bridged: bridged))
            })

        // THE SELECTION CHANNEL. Main-delivered; assign directly under
        // `assumeIsolated` (it traps if a callback ever arrives off-main).
        subscriptions.append(
            session.observeSelection { [weak self] bridged in
                let value = VaultSelection(bridged: bridged)
                MainActor.assumeIsolated { self?.selection = value }
            })
    }

    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
        pump.stop()
        started = false
        selection = .empty
    }

    // MARK: - VaultRowListModel commands

    /// A tap with no active selection; the canonical Kotlin open path pushes the detail.
    func openVaultRow(rowId: String) {
        session.openVaultRow(rowId: rowId)
    }

    /// Routes to the producer's group-scoped toggle; a cross-group toggle is a Kotlin no-op.
    func toggleSelection(rowId: String) {
        session.toggleSelection(rowId: rowId)
    }

    /// The context-menu "Select" toggle, or the per-group "Merge" button, whose
    /// action id is the button row id.
    func performVaultRowAction(rowId: String, actionId: String) {
        if actionId == RowAction.toggleSelect {
            session.toggleSelection(rowId: rowId)
        } else {
            session.performVaultRowAction(rowId: rowId, actionId: actionId)
        }
    }

    func invokeSelectionAction(id: String) {
        session.invokeSelectionAction(id: id)
    }

    func invokeSelectionAction(id: String, selectedIds: Set<String>) {
        guard selection.selectedIds == selectedIds else { return }
        session.invokeSelectionActionForItems(id: id, itemIds: selectedIds.sorted())
    }

    func clearSelection() {
        session.clearSelection()
    }

    /// Synthesized Swift-side: a single "Select" toggle that begins a selection or
    /// toggles this row's membership.
    func rowActions(rowId: String) async -> [VaultAction] {
        [VaultAction(id: RowAction.toggleSelect, title: L10n.select, symbol: "checkmark.circle")]
    }
}
