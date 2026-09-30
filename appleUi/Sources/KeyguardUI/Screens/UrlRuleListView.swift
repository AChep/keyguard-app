import SwiftUI
import KeyguardShared

struct UrlRuleListView: View {
    @Environment(UrlRulesModel.self) private var model

    let title: String
    let emptyText: String
    let subtitleLabel: String
    let detailLabel: String
    let snapshotKeyPath: KeyPath<UrlRulesModel, UrlRuleListSnapshot>

    // Read the observable model inside the destination, so updates do not depend
    // on the presenting settings screen remaining in the navigation hierarchy.
    private var snapshot: UrlRuleListSnapshot { model[keyPath: snapshotKeyPath] }
    let start: () -> Void
    let stop: () -> Void
    /// Opens the producer's create-new form.
    let onNew: () -> Void
    /// Runs a per-row dropdown action (edit / duplicate / delete) by its opaque id.
    let invokeItemAction: (String) -> Void
    /// Runs a bulk action of the active multi-selection (Delete) by its opaque id.
    let invokeSelectionAction: (String) -> Void
    /// Toggles whether the row with the given id is part of the multi-selection.
    let toggleSelection: (String) -> Void
    /// Clears the active multi-selection.
    let clearSelection: () -> Void

    /// The list's multi-selection, kept in lockstep with the shared producer's
    /// selection handle.
    @State private var selection = ListSelectionModel()
    @State private var presentedRule: ListDetailSheetItem?
    @State private var pendingActionId: String?

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(title, systemImage: "link")
            } description: {
                Text(emptyText)
            }
        } content: {
            list
        }
        .navigationTitle(title)
        .toolbar { toolbar }
        .listSelection(
            selection,
            selectionCount: snapshot.selectionCount,
            producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
            isKnownId: { id in snapshot.items.contains { $0.id == id } },
            toggle: toggleSelection,
            clear: clearSelection
        )
        .sheet(item: $presentedRule, onDismiss: invokePendingAction) { rule in
            if let item = snapshot.items.first(where: { $0.id == rule.id }) {
                UrlRuleDetailSheet(
                    title: title,
                    item: item,
                    subtitleLabel: subtitleLabel,
                    detailLabel: detailLabel
                ) { actionId in
                    // The shared editor and delete confirmation present at the
                    // app root. Wait until this sheet closes before opening them.
                    pendingActionId = actionId
                    presentedRule = nil
                }
            }
        }
        .onChange(of: snapshot.items.map(\.id)) { _, ids in
            if let presentedRule, !ids.contains(presentedRule.id) {
                pendingActionId = nil
                self.presentedRule = nil
            }
        }
        .observing(start: start, stop: stop)
    }

    private var list: some View {
        List(selection: editing ? $selection.selectedRowIds : nil) {
            ForEach(snapshot.items, id: \.id) { item in
                rowEntry(item)
                    .tag(item.id)
                    // Always attach the menu and gate only its content so that
                    // toggling selection does not restructure every row.
                    .contextMenu { rowContextMenu(item) }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        rowSwipeActions(item)
                    }
            }
        }
        .selectionBar(
            count: snapshot.selectionCount,
            actions: snapshot.selectionActions,
            invoke: { invokeSelectionAction($0) },
            clear: { clearSelection() }
        )
    }

    @ViewBuilder
    private func rowEntry(_ item: UrlRuleItemSnapshot) -> some View {
        if editing {
            row(item)
        } else {
            Button {
                presentedRule = ListDetailSheetItem(id: item.id)
            } label: {
                row(item)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var editing: Bool {
        selection.isEditing(selectionCount: snapshot.selectionCount)
    }

    private func row(_ item: UrlRuleItemSnapshot) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if !item.title.isEmpty {
                    Text(item.title)
                        .font(.body)
                }
                Text(item.active ? L10n.enabled : L10n.disabled)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if !item.subtitle.isEmpty {
                    Text(item.subtitle)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if !item.detail.isEmpty {
                    Text(item.detail)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .touchTarget()
        .accessibilityElement(children: .combine)
    }

    /// A row's own dropdown actions (edit / duplicate / delete), or — while a
    /// multi-selection is active — the bulk selection actions.
    @ViewBuilder
    private func rowContextMenu(_ item: UrlRuleItemSnapshot) -> some View {
        if showsBulkContextMenu {
            listActionMenuItems(actions: snapshot.selectionActions) {
                invokeSelectionAction($0)
            }
        } else {
            #if os(macOS)
            Button {
                toggleSelection(item.id)
            } label: {
                Label(L10n.select, systemImage: "checkmark.circle")
            }
            Divider()
            #endif
            ForEach(item.actions, id: \.id) { action in
                if action.startsSection {
                    Divider()
                }
                ListItemActionButton(action: action, invoke: invokeItemAction)
            }
        }
    }

    /// The same per-row actions surfaced as trailing swipe buttons.
    @ViewBuilder
    private func rowSwipeActions(_ item: UrlRuleItemSnapshot) -> some View {
        if !editing {
            ForEach(item.actions, id: \.id) { action in
                ListItemActionButton(action: action, invoke: invokeItemAction)
            }
        }
    }

    private func invokePendingAction() {
        guard let actionId = pendingActionId else { return }
        pendingActionId = nil
        guard snapshot.items.contains(where: { item in item.actions.contains { $0.id == actionId } }) else { return }
        invokeItemAction(actionId)
    }

    private var showsBulkContextMenu: Bool {
        selection.showsBulkActions(selectionCount: snapshot.selectionCount)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        #if os(iOS)
        if !snapshot.items.isEmpty {
            ToolbarItem(placement: .topBarLeading) {
                EditButton()
            }
        }
        #endif
        if snapshot.hasPrimaryAction {
            ToolbarItem {
                Button {
                    onNew()
                } label: {
                    Label(L10n.add, systemImage: "plus")
                }
            }
        }
    }
}
