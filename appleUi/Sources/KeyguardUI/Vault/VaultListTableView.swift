#if os(macOS)
import SwiftUI
import AppKit
import KeyguardShared

/// Virtualized macOS vault list backed by a diffable table view.
/// Structure changes update row ids; visible cells observe their own content.
struct VaultListTableView: NSViewRepresentable {
    let model: any VaultRowListModel
    let selection: VaultSelectionModel
    let config: VaultListConfig
    let colorScheme: ColorScheme

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model, selection: selection, config: config, colorScheme: colorScheme)
    }

    func makeNSView(context: Context) -> NSScrollView {
        context.coordinator.makeScrollView()
    }

    func updateNSView(_ nsView: NSScrollView, context: Context) {
        context.coordinator.sync(
            colorScheme: colorScheme, selectedRowId: config.selectedRowId, quickFilters: config.quickFilters,
            usesNativeEmptyState: config.usesNativeEmptyState)
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, NSTableViewDelegate {
        private let model: any VaultRowListModel
        private let selection: VaultSelectionModel

        /// `var` because the out-of-band `selectedRowId` (Quick Search) changes
        /// over the surface's lifetime; `sync` refreshes it so newly-dequeued and
        /// reconfigured cells carry the current highlight.
        private var config: VaultListConfig
        private var colorScheme: ColorScheme

        private weak var scrollView: NSScrollView?
        private weak var tableView: NSTableView?
        private var dataSource: NSTableViewDiffableDataSource<Int, String>!

        private var projection = VaultListProjection()
        private var entriesById: [String: VaultRowEntry] { projection.entriesById }
        private var appliedIds: [String] { projection.ids }

        /// Set while we push a model-driven selection INTO the table, so the
        /// resulting `tableViewSelectionDidChange` is not echoed back out.
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
            config: VaultListConfig,
            colorScheme: ColorScheme
        ) {
            self.model = model
            self.selection = selection
            self.config = config
            self.colorScheme = colorScheme
        }

        deinit {
            NotificationCenter.default.removeObserver(self)
        }

        // MARK: NSView construction

        func makeScrollView() -> NSScrollView {
            let table = VaultTableView()
            table.focusOnMouseDown = config.supportsMultiSelect
            table.showContextMenu = { [weak self] event in
                self?.showContextMenu(for: event)
            }
            table.headerView = nil
            table.style = .inset
            table.selectionHighlightStyle = .regular
            table.usesAutomaticRowHeights = true
            table.rowSizeStyle = .custom
            table.gridStyleMask = []
            table.intercellSpacing = NSSize(width: 0, height: 6)
            table.allowsColumnResizing = false
            table.allowsColumnReordering = false
            table.allowsColumnSelection = false
            table.allowsEmptySelection = true
            table.allowsMultipleSelection = config.supportsMultiSelect
            if config.highlightsSelectedRowId {
                table.selectionHighlightStyle = .none
            }
            table.delegate = self

            let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("VaultColumn"))
            column.resizingMask = .autoresizingMask
            table.addTableColumn(column)
            table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle

            let source = NSTableViewDiffableDataSource<Int, String>(tableView: table) {
                [weak self] table, _, _, identifier in
                // The cell provider runs on the main thread; `assumeIsolated` lets
                // it reach the `@MainActor` coordinator without a hop.
                MainActor.assumeIsolated {
                    self?.makeCell(in: table, identifier: identifier) ?? NSTableCellView()
                }
            }
            self.dataSource = source

            let scroll = NSScrollView()
            scroll.documentView = table
            scroll.hasVerticalScroller = true
            scroll.hasHorizontalScroller = false
            scroll.autohidesScrollers = true

            // Observe scrolling to report the topmost visible anchor row.
            scroll.contentView.postsBoundsChangedNotifications = true
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(contentBoundsDidChange(_:)),
                name: NSView.boundsDidChangeNotification,
                object: scroll.contentView
            )

            self.scrollView = scroll
            self.tableView = table
            return scroll
        }

        private func makeCell(in table: NSTableView, identifier: String) -> NSTableCellView {
            let cell: VaultRowHostingCell
            if let reused = table.makeView(withIdentifier: VaultRowHostingCell.identifier, owner: nil)
                as? VaultRowHostingCell
            {
                cell = reused
            } else {
                cell = VaultRowHostingCell()
                cell.identifier = VaultRowHostingCell.identifier
            }
            // A miss here means the frame carried an id we have no entry for; fall
            // back to an item row (the store's `box(for:)` logs a MISS separately).
            let entry = entriesById[identifier] ?? VaultRowEntry(id: identifier, kind: .item)
            cell.configure(entry: entry, model: model, config: config, colorScheme: colorScheme)
            return cell
        }

        // MARK: Structure / selection / scroll sync

        func sync(
            colorScheme newColorScheme: ColorScheme, selectedRowId newSelectedRowId: String?,
            quickFilters newQuickFilters: Bool, usesNativeEmptyState: Bool
        ) {
            guard let table = tableView else { return }

            let structure = model.store.structure
            let revision = structure.revision  // Observation dependency.
            let selectedRowIds = selection.selectedRowIds  // Observation dependency.
            let revealToken = selection.revealToken  // Observation dependency.

            let colorSchemeChanged = newColorScheme != colorScheme
            colorScheme = newColorScheme
            let selectedRowIdChanged = newSelectedRowId != config.selectedRowId
            config.selectedRowId = newSelectedRowId
            config.quickFilters = newQuickFilters
            config.usesNativeEmptyState = usesNativeEmptyState

            let structureChanged = syncStructureIfNeeded(structure: structure, revision: revision)

            if colorSchemeChanged || selectedRowIdChanged || structureChanged {
                reconfigureVisibleCells(in: table)
            }

            reflectSelection(selectedRowIds, in: table)
            restoreScrollIfNeeded(revision: revision, in: table)

            if config.highlightsSelectedRowId, selectedRowIdChanged, didRestoreScroll {
                scrollToSelectedRowId(in: table)
            }

            if revealToken != lastRevealToken {
                lastRevealToken = revealToken
                if let id = selection.revealRowId,
                    let row = dataSource.row(forItemIdentifier: id),
                    row >= 0, row < table.numberOfRows
                {
                    table.scrollRowToVisible(row)
                }
            }
        }

        /// Projects structure entries to diffable ids only when the locally
        /// structural inputs change. Selection-only SwiftUI updates keep using the
        /// cached `appliedIds` / `entriesById`.
        @discardableResult
        private func syncStructureIfNeeded(structure: VaultRowStore.Structure, revision: Int64) -> Bool {
            let changes = projection.update(
                entries: structure.entries,
                revision: revision,
                options: .init(
                    includesQuickFilters: config.quickFilters, usesNativeEmptyState: config.usesNativeEmptyState)
            )
            guard changes.idsChanged else { return changes.entriesChanged }
            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            snapshot.appendItems(projection.ids, toSection: 0)
            // O(changes) native row update: no per-row SwiftUI identity
            // reconcile; only newly-visible rows ask for a cell.
            dataSource.apply(snapshot, animatingDifferences: false)
            return true
        }

        private func scrollToSelectedRowId(in table: NSTableView) {
            guard let id = config.selectedRowId,
                let row = dataSource.row(forItemIdentifier: id),
                row >= 0, row < table.numberOfRows
            else { return }
            table.scrollRowToVisible(row)
        }

        private func reconfigureVisibleCells(in table: NSTableView) {
            let visible = table.rows(in: table.visibleRect)
            guard visible.length > 0 else { return }
            for row in visible.location..<(visible.location + visible.length) {
                guard let id = dataSource.itemIdentifier(forRow: row),
                    let entry = entriesById[id],
                    let cell = table.view(atColumn: 0, row: row, makeIfNecessary: false) as? VaultRowHostingCell
                else { continue }
                cell.configure(entry: entry, model: model, config: config, colorScheme: colorScheme)
            }
        }

        // Resolve actions at invocation time. An NSHostingView inside an
        // NSTableView does not reliably receive SwiftUI context-menu gestures.
        private func showContextMenu(for event: NSEvent) {
            guard config.contextMenu, let table = tableView else { return }
            let row = table.row(at: table.convert(event.locationInWindow, from: nil))
            guard row >= 0, let id = dataSource.itemIdentifier(forRow: row),
                entriesById[id]?.kind == .item
            else { return }
            let menuSelection = model.selection
            let selectedIds = menuSelection.contextualItemIds(
                for: model.store.box(for: id).row?.secretId, minimumCount: 2)
            Task { @MainActor [weak self, weak table] in
                guard let self, let table else { return }
                let actions: [VaultAction]
                if let selectedIds {
                    guard model.selection.selectedIds == selectedIds else { return }
                    actions = menuSelection.actions
                } else {
                    actions = await model.rowActions(rowId: id)
                }
                guard table.window != nil, entriesById[id]?.kind == .item else { return }
                let menu = NSMenu()
                for action in actions {
                    if action.startsSection && !menu.items.isEmpty {
                        menu.addItem(.separator())
                    }
                    let item = NSMenuItem(
                        title: action.title, action: #selector(invokeContextAction(_:)), keyEquivalent: "")
                    item.target = self
                    item.representedObject = ContextAction(rowId: id, actionId: action.id, selectedIds: selectedIds)
                    item.state = action.role == .toggleOn ? .on : .off
                    if let symbol = action.symbol {
                        item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
                    }
                    menu.addItem(item)
                }
                guard !menu.items.isEmpty else { return }
                NSMenu.popUpContextMenu(menu, with: event, for: table)
            }
        }

        private struct ContextAction {
            let rowId: String
            let actionId: String
            let selectedIds: Set<String>?
        }

        @objc private func invokeContextAction(_ sender: NSMenuItem) {
            guard let action = sender.representedObject as? ContextAction else { return }
            if let selectedIds = action.selectedIds {
                model.invokeSelectionAction(id: action.actionId, selectedIds: selectedIds)
            } else {
                model.performVaultRowAction(rowId: action.rowId, actionId: action.actionId)
            }
        }

        // MARK: Selection

        /// Pushes the model's `selectedRowIds` into the table; ids no longer present
        /// in the frame are dropped.
        private func reflectSelection(_ selectedRowIds: Set<String>, in table: NSTableView) {
            var desired = IndexSet()
            for id in selectedRowIds {
                if let row = dataSource.row(forItemIdentifier: id), row >= 0, row < table.numberOfRows {
                    desired.insert(row)
                }
            }
            guard desired != table.selectedRowIndexes else { return }
            applyingSelectionFromModel = true
            table.selectRowIndexes(desired, byExtendingSelection: false)
            applyingSelectionFromModel = false
        }

        /// Only `.item` rows are selectable (sections / markers / the quick-filter
        /// row are not).
        func tableView(_ tableView: NSTableView, shouldSelectRow row: Int) -> Bool {
            guard let id = dataSource.itemIdentifier(forRow: row),
                let entry = entriesById[id]
            else { return false }
            return entry.kind == .item
        }

        func tableViewSelectionDidChange(_ notification: Notification) {
            guard !applyingSelectionFromModel, let table = tableView else { return }
            let ids = Set(table.selectedRowIndexes.compactMap { dataSource.itemIdentifier(forRow: $0) })
            if config.highlightsSelectedRowId {
                if let id = ids.first {
                    model.selectRow(rowId: id)
                }
                if !table.selectedRowIndexes.isEmpty {
                    applyingSelectionFromModel = true
                    table.deselectAll(nil)
                    applyingSelectionFromModel = false
                }
                return
            }
            if config.rowTap == .copyPrimary {
                // A Recents-style read-only picker: a momentary tap, not a
                // persisted selection. Never mirror into `selectedRowIds`.
                if let id = ids.first {
                    model.copyPrimaryRow(rowId: id)
                }
                if !table.selectedRowIndexes.isEmpty {
                    applyingSelectionFromModel = true
                    table.deselectAll(nil)
                    applyingSelectionFromModel = false
                }
                return
            }
            if config.rowTap == .openOrToggle {
                // The Duplicates surface: selection is Kotlin-owned (group-scoped)
                // and shown via the row flags, so never persist a native selection.
                if let id = ids.first {
                    if model.selection.count > 0 {
                        model.toggleSelection(rowId: id)
                    } else {
                        model.openVaultRow(rowId: id)
                    }
                }
                if !table.selectedRowIndexes.isEmpty {
                    applyingSelectionFromModel = true
                    table.deselectAll(nil)
                    applyingSelectionFromModel = false
                }
                return
            }
            if selection.selectedRowIds != ids {
                selection.selectedRowIds = ids
            }
        }

        // MARK: Scroll restore / report

        /// The subscribe-time restore: on the first real structure frame, scroll to
        /// the persisted anchor row if present, else the top — then start reporting.
        private func restoreScrollIfNeeded(revision: Int64, in table: NSTableView) {
            guard revision != 0, !didRestoreScroll, !appliedIds.isEmpty else { return }
            didRestoreScroll = true

            var targetRow = 0
            if let anchorId = model.store.structure.scrollAnchor?.id,
                let row = dataSource.row(forItemIdentifier: anchorId),
                row >= 0, row < table.numberOfRows
            {
                targetRow = row
            }
            if targetRow >= 0, targetRow < table.numberOfRows, let scroll = scrollView {
                let rect = table.rect(ofRow: targetRow)
                scroll.contentView.scroll(to: NSPoint(x: 0, y: rect.minY))
                scroll.reflectScrolledClipView(scroll.contentView)
            }
            // Only start reporting AFTER the restore lands, so the rows realized
            // during initial load do not report "top" and clobber the anchor.
            scrollReporter.isEnabled = true
        }

        @objc private func contentBoundsDidChange(_ notification: Notification) {
            scrollReporter.didScroll()
        }

        private func visibleAnchor() -> String? {
            guard let table = tableView else { return nil }
            let visible = table.rows(in: table.visibleRect)
            guard visible.length > 0 else { return nil }
            return dataSource.itemIdentifier(forRow: visible.location)
        }

    }
}

// MARK: - Hosting cell

private final class VaultTableView: NSTableView {
    var focusOnMouseDown = false
    var showContextMenu: ((NSEvent) -> Void)?

    override func rightMouseDown(with event: NSEvent) {
        showContextMenu?(event)
    }

    override func mouseDown(with event: NSEvent) {
        // SwiftUI-hosted cells can change the native selection while leaving the
        // search field as first responder. Transfer keyboard focus before the
        // click so subsequent selection shortcuts operate on the vault rows.
        // Quick Search opts out to keep its search-field keyboard handling.
        if focusOnMouseDown {
            window?.makeFirstResponder(self)
        }
        if event.modifierFlags.contains(.control) {
            showContextMenu?(event)
            return
        }
        super.mouseDown(with: event)
    }
}

private final class VaultRowHostingCell: NSTableCellView {
    static let identifier = NSUserInterfaceItemIdentifier("VaultRowHostingCell")
    private var host: NSHostingView<VaultRowHost>?

    func configure(
        entry: VaultRowEntry,
        model: any VaultRowListModel,
        config: VaultListConfig,
        colorScheme: ColorScheme
    ) {
        var config = config
        // AppKit owns contextual clicks across the full native row bounds.
        config.contextMenu = false
        let root = VaultRowHost(
            store: model.store, entry: entry, model: model, config: config, colorScheme: colorScheme)
        if let host {
            host.rootView = root
            return
        }
        let view = NSHostingView(rootView: root)
        view.translatesAutoresizingMaskIntoConstraints = false
        view.sizingOptions = [.intrinsicContentSize]
        addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: leadingAnchor),
            view.trailingAnchor.constraint(equalTo: trailingAnchor),
            view.topAnchor.constraint(equalTo: topAnchor),
            view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        host = view
    }
}
#endif
