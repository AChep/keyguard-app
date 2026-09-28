import SwiftUI
import KeyguardShared

@MainActor
@Observable
final class VaultSelectionModel {
    /// The list's multi-selection — the source of truth `List(selection:)` binds
    /// to. Only `.item` rows are selectable (every other row is
    /// `.selectionDisabled()`), so every id here is a cipher row.
    var selectedRowIds: Set<String> = []
    /// macOS only: whether a single item is selected AND resolved to a detail
    /// target. Drives the detail column; maintained by the list pane's sync.
    var detailShown = false

    var revealRowId: String?
    var revealToken = 0

    /// Requests a one-shot scroll to `rowId` (see `revealRowId`).
    func reveal(rowId: String) {
        revealRowId = rowId
        revealToken += 1
    }

    /// The last selection the list pane synced, to diff toggles. Not observed —
    /// read/written only inside the sync, never rendered.
    @ObservationIgnored var lastSelectedRowIds: Set<String> = []

    /// Mirrors a producer-driven selection change down into the list.
    ///
    /// The diff baseline is advanced *before* the write, which makes the write
    /// self-cancelling: the `onChange` it provokes diffs to nothing and forwards no
    /// toggles. That is why no suppression flag is needed here — a flag would have
    /// to be lifted on a later turn of the main queue, which is not ordered against
    /// SwiftUI's own change delivery.
    func reconcile(_ producerSelection: Set<String>) {
        guard selectedRowIds != producerSelection else {
            lastSelectedRowIds = producerSelection
            return
        }
        lastSelectedRowIds = producerSelection
        selectedRowIds = producerSelection
    }
}

/// Well-known `FlatItemAction.id`s the client dispatches by name (mirrors the
/// Kotlin `VaultActionSymbols` table).
enum VaultActions {
    /// The per-row "copy one-time password" action (the TOTP badge tap); see
    /// `VaultActionSymbols.kt` ("vaultList.item.copyOtp").
    static let copyOtp = "vaultList.item.copyOtp"
}

struct VaultListPane: View {
    let model: VaultListSessionModel
    @Bindable var selection: VaultSelectionModel
    /// The surface's interaction policy; defaults to the main list's current
    /// behavior so the existing call sites are unchanged.
    var config: VaultListConfig = .vaultMain

    @Environment(\.colorScheme) private var colorScheme
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(CipherDetailModel.self) private var cipherDetailModel
    #if os(iOS)
    @Environment(\.editMode) private var editMode
    #endif

    #if os(iOS)
    private var editing: Bool { editMode?.wrappedValue.isEditing ?? false }
    #endif

    var body: some View {
        platformList
            .onChange(of: selection.selectedRowIds) { _, newValue in
                syncSelection(newValue)
            }
            .background {
                VaultSelectionEchoWatcher(model: model, selection: selection)
            }
    }

    // MARK: - Selection

    private func syncSelection(_ newValue: Set<String>) {
        defer { selection.lastSelectedRowIds = newValue }
        for id in newValue.symmetricDifference(selection.lastSelectedRowIds) {
            model.toggleSelection(rowId: id)
        }
        #if os(macOS)
        // Single-select drives the inline detail pane only when the surface's
        // row-tap policy is `.select` (the main list). Behind the config flag so
        // a sibling surface can opt out of the detail column.
        if config.rowTap == .select {
            updateDetailObservation(newValue)
        }
        #endif
    }

    // MARK: - macOS

    #if os(macOS)
    private var platformList: some View {
        Group {
            if !model.header.loaded {
                LoadingIndicator()
            } else {
                VaultListRepresentable(
                    model: model,
                    selection: selection,
                    accountsModel: accountsModel,
                    config: config,
                    colorScheme: colorScheme
                )
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VaultMacBulkBar(model: model)
        }
    }

    private func updateDetailObservation(_ selected: Set<String>) {
        // Only `.item` rows are selectable, so one selected id is the detail gate.
        selection.detailShown = selected.count == 1
        if selected.count == 1,
            let id = selected.first,
            let row = model.store.box(for: id).row,
            let secretId = row.secretId,
            let accountId = row.accountId
        {
            model.setOpenedRow(rowId: id)
            cipherDetailModel.setDetailTarget(itemId: secretId, accountId: accountId)
        } else {
            cipherDetailModel.setDetailTarget(itemId: nil, accountId: nil)
        }
    }
    #endif

    // MARK: - iOS

    #if os(iOS)
    private var platformList: some View {
        VaultListRepresentable(
            model: model,
            selection: selection,
            accountsModel: accountsModel,
            config: config,
            colorScheme: colorScheme,
            editing: editing
        )
        .vaultListBottomBar {
            VaultIOSBulkBar(model: model)
        }
    }
    #endif
}

// MARK: - Detail pane (macOS)

#if os(macOS)
struct VaultDetailPane: View {
    let selection: VaultSelectionModel

    var body: some View {
        if selection.detailShown {
            CipherDetailView()
        } else {
            ContentUnavailableView {
                Label(L10n.vaultViewNoItemSelectedTitle, systemImage: "sidebar.right")
            } description: {
                Text(L10n.vaultViewSelectItemHint)
            }
        }
    }
}
#endif

// MARK: - Selection echo watcher

private struct VaultSelectionEchoWatcher: View {
    let model: VaultListSessionModel
    @Bindable var selection: VaultSelectionModel

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .onChange(of: model.selection.count) { _, count in
                // The producer cleared its selection on its own (a bulk action ran,
                // the bar's X was tapped, items disappeared).
                if count == 0 { selection.reconcile([]) }
            }
    }
}

// MARK: - Bulk-action bars

#if os(macOS)
/// The floating bulk-action bar for macOS (shown at ≥2 selected). Reads
/// `model.selection` in its OWN view so the selection channel invalidates only the
/// bar, not the list rows (Fix #4).
private struct VaultMacBulkBar: View {
    let model: VaultListSessionModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if model.selection.count >= 2 {
                VaultSelectionActionBar(
                    count: model.selection.count,
                    actions: model.selection.actions,
                    invoke: { model.invokeSelectionAction(id: $0) },
                    clear: { model.clearSelection() }
                )
                .padding(.bottom, 12)
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .spring(duration: 0.3), value: model.selection.count >= 2)
    }
}
#endif

#if os(iOS)
/// The floating bulk-action bar for iOS (shown while editing with ≥1 selected).
/// Isolated for the same reason as its macOS twin (Fix #4).
private struct VaultIOSBulkBar: View {
    let model: VaultListSessionModel
    @Environment(\.editMode) private var editMode
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var active: Bool {
        (editMode?.wrappedValue.isEditing ?? false) && model.selection.count >= 1
    }

    var body: some View {
        ZStack {
            if active {
                VaultSelectionActionBar(
                    count: model.selection.count,
                    actions: model.selection.actions,
                    invoke: { model.invokeSelectionAction(id: $0) },
                    clear: { model.clearSelection() }
                )
                .padding(.bottom, 12)
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .spring(duration: 0.3), value: active)
    }
}
#endif

// MARK: - Row context menu

struct VaultRowContextMenu: ViewModifier {
    let model: any VaultRowListModel
    let rowId: String
    let rowRevision: Int64?
    /// iOS edit-mode gate for the bulk-action swap; always `false` on macOS.
    let editing: Bool
    /// Whether this surface shows a row context menu at all (the main list does;
    /// `VaultListConfig.contextMenu`). When `false` the menu + its prefetch are skipped.
    var enabled: Bool = true
    @State private var rowActionsLoad: VaultRowActionsLoad?

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content
                .contextMenu {
                    if let selectedIds = contextualSelectedIds {
                        vaultSelectionContextMenuItems(actions: model.selection.actions) {
                            model.invokeSelectionAction(id: $0, selectedIds: selectedIds)
                        }
                    } else {
                        vaultActionMenuItems(currentRowActions) {
                            model.performVaultRowAction(rowId: rowId, actionId: $0)
                        }
                    }
                }
                // Refresh when a cell is recycled or its row changes (for example,
                // favourite state changes the available action while keeping its id).
                .task(id: request) {
                    let load = nextRowActionsLoad()
                    rowActionsLoad = load

                    let actions = await model.rowActions(rowId: load.request.rowId)
                    guard !Task.isCancelled else { return }
                    rowActionsLoad = rowActionsLoad?.resolving(actions, for: load)
                }
        } else {
            content
        }
    }

    private var request: VaultRowContextMenuRequest {
        VaultRowContextMenuRequest(rowId: rowId, revision: rowRevision)
    }

    private var currentRowActions: [VaultAction] {
        rowActionsLoad?.actions(for: request) ?? []
    }

    private func nextRowActionsLoad() -> VaultRowActionsLoad {
        VaultRowActionsLoad(
            request: request,
            generation: (rowActionsLoad?.generation ?? 0) + 1
        )
    }

    private var contextualSelectedIds: Set<String>? {
        #if os(iOS)
        guard editing else { return nil }
        let minimumCount = 1
        #else
        let minimumCount = 2
        #endif
        return model.selection.contextualItemIds(
            for: model.store.box(for: rowId).row?.secretId, minimumCount: minimumCount)
    }

}
