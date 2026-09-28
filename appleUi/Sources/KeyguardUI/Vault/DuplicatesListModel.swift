import Foundation
import Observation
import KeyguardShared

/// List model for the Duplicates screen. It uses the shared row renderers and
/// keeps group-scoped selection in the projected row flags.
@MainActor
@Observable
final class DuplicatesListModel: VaultRowListModel {
    /// Row content + structure; the list body and cells observe this.
    let store = VaultRowStore()

    /// The active multi-selection (`count == 0` = inactive); read by the bulk bar
    /// and the row context menu's bulk swap.
    private(set) var selection: VaultSelection = .empty

    var loaded: Bool { store.structure.revision != 0 }

    @ObservationIgnored private let session: DuplicatesSession
    /// The shared FIFO background→Main delta pump, bound to this model's `store`.
    @ObservationIgnored private lazy var pump = VaultDeltaPump(store: store)
    @ObservationIgnored private var subscriptions: [KeyguardCancellable] = []
    @ObservationIgnored private var started = false

    /// The well-known row-action id dispatched through `performVaultRowAction` to
    /// begin / toggle a row's selection membership (the context-menu "Select").
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

    /// Subscribes the two channels + starts the delta pump. Call on appear;
    /// balance with `stop()` on disappear. No-op while already started.
    func start() {
        guard !started else { return }
        started = true

        // The one-hop FIFO apply pipeline (shared helper): re-baselines the store,
        // captures the run generation, starts the apply task and hands back the
        // continuation the background delta callback yields into.
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

    /// Cancels both channels, stops the pump and clears state.
    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
        pump.stop()
        started = false
        selection = .empty
    }

    // MARK: - VaultRowListModel commands

    /// A tap when no selection is active opens the cipher (the `.openOrToggle`
    /// bridge routes here); the canonical Kotlin open path pushes the detail.
    func openVaultRow(rowId: String) {
        session.openVaultRow(rowId: rowId)
    }

    /// A tap while selecting, or the context-menu "Select" to begin — routes to
    /// the producer's group-scoped toggle (a cross-group toggle is a Kotlin no-op).
    func toggleSelection(rowId: String) {
        session.toggleSelection(rowId: rowId)
    }

    /// Dispatches a per-row action: the context-menu "Select" toggle, or the
    /// per-group "Merge" button (whose action id is the button row id).
    func performVaultRowAction(rowId: String, actionId: String) {
        if actionId == RowAction.toggleSelect {
            session.toggleSelection(rowId: rowId)
        } else {
            session.performVaultRowAction(rowId: rowId, actionId: actionId)
        }
    }

    /// One of the active multi-selection's bulk actions (favourite / rename /
    /// trash / send / merge / …), fired by its `FlatItemAction.id`.
    func invokeSelectionAction(id: String) {
        session.invokeSelectionAction(id: id)
    }

    func invokeSelectionAction(id: String, selectedIds: Set<String>) {
        guard selection.selectedIds == selectedIds else { return }
        session.invokeSelectionActionForItems(id: id, itemIds: selectedIds.sorted())
    }

    /// Clears the active multi-selection (the bulk bar's X).
    func clearSelection() {
        session.clearSelection()
    }

    /// The row's context menu, synthesized Swift-side: a single "Select" toggle
    /// (begins a selection, or toggles this row's membership). Fetched on demand by
    /// `VaultRowContextMenu` like the main list's `rowActions`.
    func rowActions(rowId: String) async -> [VaultAction] {
        [VaultAction(id: RowAction.toggleSelect, title: L10n.select, symbol: "checkmark.circle")]
    }
}
