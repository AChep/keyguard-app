#if os(iOS)
import SwiftUI
import UIKit
import KeyguardShared

/// Virtualized iOS vault list backed by a diffable collection view.
/// Structure changes update row ids; visible cells observe their own content.
struct VaultListCollectionView: UIViewRepresentable {
    let model: any VaultRowListModel
    let selection: VaultSelectionModel
    /// The app-lifetime model, read only for the sync-status header row.
    let accountsModel: AccountsModel
    let config: VaultListConfig
    let colorScheme: ColorScheme
    /// The `EditMode` gate: drives multi-select + the row context menu bulk swap.
    let editing: Bool
    /// Height of SwiftUI chrome not included in UIKit's automatic safe-area inset.
    let additionalBottomInset: CGFloat

    func makeCoordinator() -> Coordinator {
        Coordinator(
            model: model,
            selection: selection,
            accountsModel: accountsModel,
            config: config,
            colorScheme: colorScheme,
            editing: editing
        )
    }

    func makeUIView(context: Context) -> UICollectionView {
        context.coordinator.makeCollectionView()
    }

    func updateUIView(_ uiView: UICollectionView, context: Context) {
        uiView.contentInset.bottom = additionalBottomInset
        uiView.verticalScrollIndicatorInsets.bottom = additionalBottomInset
        context.coordinator.sync(
            colorScheme: colorScheme,
            editing: editing,
            selectedRowId: config.selectedRowId,
            syncHeader: config.syncHeader,
            usesNativeEmptyState: config.usesNativeEmptyState
        )
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate {

        /// The synthetic diffable item id for the pinned sync-status header row.
        static let syncHeaderId = "__v2_sync__"

        private let model: any VaultRowListModel
        private let selection: VaultSelectionModel
        private let accountsModel: AccountsModel
        private var config: VaultListConfig
        private var colorScheme: ColorScheme
        private var editing: Bool

        private weak var collectionView: UICollectionView?
        private var dataSource: UICollectionViewDiffableDataSource<Int, String>!

        private var projection = VaultListProjection()
        private var entriesById: [String: VaultRowEntry] { projection.entriesById }
        private var appliedIds: [String] { projection.ids }

        /// Set while we push a model-driven selection INTO the collection view, so a
        /// stray delegate callback is not echoed back out. (Programmatic
        /// select/deselect does not fire the delegate, so this is belt-and-braces.)
        private var applyingSelectionFromModel = false

        private var lastRevealToken = 0

        /// One-shot subscribe-time scroll restore guard, and the throttle state for
        /// scroll reporting.
        private var didRestoreScroll = false
        private lazy var scrollReporter = VaultScrollReporter(
            visibleAnchor: { [weak self] in self?.visibleAnchor() },
            report: { [weak self] id in self?.model.reportScroll(anchorId: id, offset: 0) }
        )

        init(
            model: any VaultRowListModel,
            selection: VaultSelectionModel,
            accountsModel: AccountsModel,
            config: VaultListConfig,
            colorScheme: ColorScheme,
            editing: Bool
        ) {
            self.model = model
            self.selection = selection
            self.accountsModel = accountsModel
            self.config = config
            self.colorScheme = colorScheme
            self.editing = editing
        }

        // MARK: UIView construction

        func makeCollectionView() -> UICollectionView {
            var config = UICollectionLayoutListConfiguration(appearance: .plain)
            // The rich rows carry their own spacing / decoration; a native separator
            // between every card (and cutting through section / marker rows) reads
            // wrong, so mirror the macOS table's `gridStyleMask = []`.
            config.showsSeparators = false
            config.backgroundColor = .clear
            let layout = UICollectionViewCompositionalLayout.list(using: config)

            let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
            cv.delegate = self
            cv.backgroundColor = .systemBackground
            cv.allowsSelection = true
            cv.allowsMultipleSelection = false
            // Multi-select drives the bulk bar while editing; the `.multiselect`
            // accessory + `isEditing` show the leading circles.
            cv.allowsMultipleSelectionDuringEditing = self.config.supportsMultiSelect
            cv.keyboardDismissMode = .onDrag
            cv.contentInsetAdjustmentBehavior = .automatic
            cv.automaticallyAdjustsScrollIndicatorInsets = true

            let registration = UICollectionView.CellRegistration<UICollectionViewListCell, String> {
                [weak self] cell, _, id in
                // Runs on the main thread; `assumeIsolated` reaches the `@MainActor`
                // coordinator without a hop.
                MainActor.assumeIsolated {
                    self?.configure(cell: cell, id: id)
                }
            }

            let source = UICollectionViewDiffableDataSource<Int, String>(collectionView: cv) {
                collectionView, indexPath, id in
                collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: id)
            }
            self.dataSource = source
            self.collectionView = cv
            return cv
        }

        /// Hosts the same `VaultRowHost` as the macOS table, so both render identical rows.
        private func configure(cell: UICollectionViewListCell, id: String) {
            // Reused marker cells must not retain a cipher's selection styling.
            cell.configurationUpdateHandler = nil
            cell.automaticallyUpdatesBackgroundConfiguration = false
            if id == Self.syncHeaderId {
                cell.contentConfiguration = UIHostingConfiguration {
                    VaultSyncStatusHeaderHost(accountsModel: accountsModel)
                }
                .margins(.vertical, 2)
                cell.accessories = []
                cell.backgroundConfiguration = .clear()
                return
            }
            // A miss here means the frame carried an id we have no entry for; fall
            // back to an item row (the store's `box(for:)` logs a MISS separately).
            let entry = entriesById[id] ?? VaultRowEntry(id: id, kind: .item)
            cell.contentConfiguration = UIHostingConfiguration {
                // Default hosting margins follow the native cell layout margins,
                // keeping icons, filters and markers aligned with navigation/search.
                // The shared host supplies only its vertical spacing on iOS.
                VaultRowHost(
                    store: model.store,
                    entry: entry,
                    model: model,
                    config: config,
                    colorScheme: colorScheme,
                    editing: editing,
                    horizontalPadding: 0
                )
            }
            .margins(.vertical, 3)
            if entry.kind == .item {
                // Only cipher rows take part in edit-mode multi-select.
                cell.accessories = [.multiselect(displayed: .whenEditing)]
                let browseSelected = config.selectedRowId == id
                cell.configurationUpdateHandler = { cell, state in
                    var background = UIBackgroundConfiguration.clear()
                    background.cornerRadius = 8
                    background.backgroundInsets = NSDirectionalEdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8)
                    if browseSelected || state.isSelected {
                        background.backgroundColor = cell.tintColor.withAlphaComponent(0.2)
                    } else if state.isHighlighted || state.isFocused {
                        background.backgroundColor = .tertiarySystemFill
                    }
                    cell.backgroundConfiguration = background
                }
                cell.setNeedsUpdateConfiguration()
            } else {
                cell.accessories = []
                cell.backgroundConfiguration = .clear()
            }
        }

        // MARK: Structure / selection / scroll sync

        func sync(
            colorScheme newColorScheme: ColorScheme,
            editing newEditing: Bool,
            selectedRowId newSelectedRowId: String?,
            syncHeader newSyncHeader: Bool,
            usesNativeEmptyState: Bool
        ) {
            guard let cv = collectionView else { return }

            let structure = model.store.structure
            let revision = structure.revision  // Observation dependency.
            let selectedRowIds = selection.selectedRowIds  // Observation dependency.
            let revealToken = selection.revealToken  // Observation dependency.

            let colorSchemeChanged = newColorScheme != colorScheme
            colorScheme = newColorScheme
            let editingChanged = newEditing != editing
            editing = newEditing
            let selectedRowIdChanged = newSelectedRowId != config.selectedRowId
            config.selectedRowId = newSelectedRowId
            config.syncHeader = newSyncHeader
            config.usesNativeEmptyState = usesNativeEmptyState

            let showSyncHeader: Bool
            if config.syncHeader {
                let status = accountsModel.syncStatus  // Observation dependency.
                showSyncHeader = status.loaded && (status.errorCount > 0 || status.pendingCount > 0)
            } else {
                showSyncHeader = false
            }
            let structureChanged = syncStructureIfNeeded(
                structure: structure,
                revision: revision,
                showsSyncHeader: showSyncHeader
            )

            if cv.isEditing != editing {
                cv.isEditing = editing
            }

            if colorSchemeChanged || editingChanged || selectedRowIdChanged || structureChanged {
                reconfigureVisibleCells()
            }

            reflectSelection(selectedRowIds)
            restoreScrollIfNeeded(revision: revision)

            // Keep the out-of-band selected row visible. Only after the one-shot
            // restore has landed, so it never fights the initial anchor scroll.
            if config.highlightsSelectedRowId, selectedRowIdChanged, didRestoreScroll {
                scrollToSelectedRowId()
            }

            if revealToken != lastRevealToken {
                lastRevealToken = revealToken
                if let id = selection.revealRowId,
                    let indexPath = dataSource.indexPath(for: id)
                {
                    cv.scrollToItem(at: indexPath, at: .centeredVertically, animated: false)
                }
            }
        }

        /// Projects structure entries to diffable ids only when the locally
        /// structural inputs change. Selection-only SwiftUI updates keep using the
        /// cached `appliedIds` / `entriesById`.
        @discardableResult
        private func syncStructureIfNeeded(
            structure: VaultRowStore.Structure,
            revision: Int64,
            showsSyncHeader: Bool
        ) -> Bool {
            let changes = projection.update(
                entries: structure.entries,
                revision: revision,
                options: .init(
                    usesNativeEmptyState: config.usesNativeEmptyState,
                    leadingId: showsSyncHeader ? Self.syncHeaderId : nil)
            )
            guard changes.idsChanged else { return changes.entriesChanged }
            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            snapshot.appendItems(projection.ids, toSection: 0)
            // O(changes) native cell update: no per-row SwiftUI identity
            // reconcile; only newly-visible rows ask for a cell.
            dataSource.apply(snapshot, animatingDifferences: false)
            return changes.entriesChanged
        }

        private func scrollToSelectedRowId() {
            guard let cv = collectionView,
                let id = config.selectedRowId,
                let indexPath = dataSource.indexPath(for: id)
            else { return }
            cv.scrollToItem(at: indexPath, at: .centeredVertically, animated: false)
        }

        private func reconfigureVisibleCells() {
            guard let cv = collectionView else { return }
            let visibleIds = cv.indexPathsForVisibleItems.compactMap { dataSource.itemIdentifier(for: $0) }
            guard !visibleIds.isEmpty else { return }
            var snapshot = dataSource.snapshot()
            snapshot.reconfigureItems(visibleIds)
            dataSource.apply(snapshot, animatingDifferences: false)
        }

        // MARK: Selection

        private func reflectSelection(_ selectedRowIds: Set<String>) {
            guard let cv = collectionView else { return }
            let current = Set((cv.indexPathsForSelectedItems ?? []).compactMap { dataSource.itemIdentifier(for: $0) })
            guard current != selectedRowIds else { return }
            applyingSelectionFromModel = true
            defer { applyingSelectionFromModel = false }
            for indexPath in cv.indexPathsForSelectedItems ?? [] {
                if let id = dataSource.itemIdentifier(for: indexPath), !selectedRowIds.contains(id) {
                    cv.deselectItem(at: indexPath, animated: false)
                }
            }
            for id in selectedRowIds {
                if let indexPath = dataSource.indexPath(for: id) {
                    cv.selectItem(at: indexPath, animated: false, scrollPosition: [])
                }
            }
        }

        /// Only `.item` rows are selectable / highlightable (sections, markers, the
        /// quick-filter row and the sync header are not), matching the macOS
        /// `shouldSelectRow`.
        func collectionView(_ collectionView: UICollectionView, shouldSelectItemAt indexPath: IndexPath) -> Bool {
            isItem(at: indexPath)
        }

        func collectionView(_ collectionView: UICollectionView, shouldHighlightItemAt indexPath: IndexPath) -> Bool {
            isItem(at: indexPath)
        }

        func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
            guard let id = dataSource.itemIdentifier(for: indexPath), isItem(id: id) else {
                collectionView.deselectItem(at: indexPath, animated: false)
                return
            }
            if editing {
                syncSelectionToModel()
            } else if config.highlightsSelectedRowId {
                // An out-of-band single-selection surface: a tap selects the row
                // (the visible highlight rides `config.selectedRowId`), never a
                // persisted list selection.
                collectionView.deselectItem(at: indexPath, animated: false)
                model.selectRow(rowId: id)
            } else {
                // Browse-mode tap policy per the surface's config; only `.select`
                // keeps the tap selection.
                switch config.rowTap {
                case .open:
                    collectionView.deselectItem(at: indexPath, animated: false)
                    model.openVaultRow(rowId: id)
                case .select:
                    // A single-row highlight surface keeps the tap selection.
                    break
                case .copyPrimary:
                    // A Recents-style read-only picker: a momentary tap, no
                    // persisted selection.
                    collectionView.deselectItem(at: indexPath, animated: false)
                    model.copyPrimaryRow(rowId: id)
                case .openOrToggle:
                    collectionView.deselectItem(at: indexPath, animated: false)
                    if model.selection.count > 0 {
                        model.toggleSelection(rowId: id)
                    } else {
                        model.openVaultRow(rowId: id)
                    }
                }
            }
        }

        func collectionView(_ collectionView: UICollectionView, didDeselectItemAt indexPath: IndexPath) {
            if editing { syncSelectionToModel() }
        }

        private func syncSelectionToModel() {
            guard !applyingSelectionFromModel, let cv = collectionView else { return }
            let ids = Set((cv.indexPathsForSelectedItems ?? []).compactMap { dataSource.itemIdentifier(for: $0) })
            if selection.selectedRowIds != ids {
                selection.selectedRowIds = ids
            }
        }

        private func isItem(at indexPath: IndexPath) -> Bool {
            guard let id = dataSource.itemIdentifier(for: indexPath) else { return false }
            return isItem(id: id)
        }

        private func isItem(id: String) -> Bool {
            // The sync-header id has no entry, so it is (correctly) non-selectable.
            guard let entry = entriesById[id] else { return false }
            return entry.kind == .item
        }

        // MARK: Scroll restore / report

        private func restoreScrollIfNeeded(revision: Int64) {
            guard revision != 0, !didRestoreScroll, !appliedIds.isEmpty else { return }
            didRestoreScroll = true
            let anchorId = model.store.structure.scrollAnchor?.id
            Task { @MainActor [weak self] in
                guard let self, let cv = self.collectionView else { return }
                cv.layoutIfNeeded()
                let targetId = anchorId ?? self.appliedIds.first
                if let targetId, let indexPath = self.dataSource.indexPath(for: targetId) {
                    cv.scrollToItem(at: indexPath, at: .top, animated: false)
                }
                // Only start reporting AFTER the restore lands, so the rows realized
                // during initial load do not report "top" and clobber the anchor.
                self.scrollReporter.isEnabled = true
            }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            scrollReporter.didScroll()
        }

        private func visibleAnchor() -> String? {
            guard let cv = collectionView else { return nil }
            for indexPath in cv.indexPathsForVisibleItems.sorted() {
                if let id = dataSource.itemIdentifier(for: indexPath), id != Self.syncHeaderId {
                    return id
                }
            }
            return nil
        }

    }
}

/// Hosts the shared `SyncStatusFooter` inside the pinned header cell, reading
/// `accountsModel.syncStatus` via Observation so the row's text updates in place; whether
/// the header row EXISTS at all is decided separately by the coordinator's `sync`.
private struct VaultSyncStatusHeaderHost: View {
    let accountsModel: AccountsModel

    var body: some View {
        SyncStatusFooter(status: accountsModel.syncStatus)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
#endif
