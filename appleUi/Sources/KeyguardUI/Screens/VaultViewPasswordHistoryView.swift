import SwiftUI
import KeyguardShared

struct VaultViewPasswordHistoryView: View {
    @Environment(CipherDetailModel.self) private var cipherDetailModel

    let itemId: String

    private var snapshot: PasswordHistorySnapshot { cipherDetailModel.passwordHistory }

    /// The list's multi-selection, kept in lockstep with the shared producer's
    /// selection handle.
    @State private var selection = ListSelectionModel()

    var body: some View {
        content
            .navigationTitle(L10n.passwordhistoryHeaderTitle)
            #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { toolbar }
            .listSelection(
                selection,
                selectionCount: snapshot.selectionCount,
                producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
                isKnownId: { id in snapshot.items.contains { $0.id == id } },
                toggle: cipherDetailModel.togglePasswordHistorySelection(id:),
                clear: { cipherDetailModel.clearPasswordHistorySelection() }
            )
            .observing(
                start: { cipherDetailModel.startPasswordHistoryObservation(itemId: itemId) },
                stop: { cipherDetailModel.stopPasswordHistoryObservation() }
            )
    }

    @ViewBuilder
    private var content: some View {
        if !snapshot.loaded {
            LoadingIndicator()
        } else if snapshot.notFound {
            ContentUnavailableView {
                Label(L10n.itemNotFound, systemImage: "questionmark.folder")
            }
        } else if snapshot.items.isEmpty {
            ContentUnavailableView {
                Label(L10n.passwordhistoryEmptyTitle, systemImage: "clock.arrow.circlepath")
            } description: {
                Text(L10n.passwordhistoryEmptyText)
            }
        } else {
            list
        }
    }

    private var list: some View {
        List(selection: $selection.selectedRowIds) {
            ForEach(snapshot.items, id: \.id) { item in
                row(item)
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
            invoke: { cipherDetailModel.invokePasswordHistorySelectionAction(id: $0) },
            clear: { cipherDetailModel.clearPasswordHistorySelection() }
        )
    }

    private func row(_ item: PasswordHistoryItemSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            PasswordText(item.value)
                .font(item.monospace ? .body.monospaced() : .body)
                .textSelection(.enabled)
                .lineLimit(1)
                .truncationMode(.middle)
            if let date = item.date, !date.isEmpty {
                Text(date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    /// An entry's own dropdown actions (copy / remove / large-type / show-and-lock /
    /// check breaches), or — while a multi-selection is active — the bulk actions.
    @ViewBuilder
    private func rowContextMenu(_ item: PasswordHistoryItemSnapshot) -> some View {
        if showsBulkContextMenu {
            listActionMenuItems(actions: snapshot.selectionActions) {
                cipherDetailModel.invokePasswordHistorySelectionAction(id: $0)
            }
        } else {
            // The producer's dropdown already carries its section dividers, mapped
            // through `startsSection`; reuse the shared list-action renderer.
            listActionMenuItems(actions: item.actions) {
                cipherDetailModel.invokePasswordHistoryItemAction(id: $0)
            }
        }
    }

    /// The same per-entry actions surfaced as trailing swipe buttons.
    @ViewBuilder
    private func rowSwipeActions(_ item: PasswordHistoryItemSnapshot) -> some View {
        ForEach(item.actions, id: \.id) { action in
            Button {
                cipherDetailModel.invokePasswordHistoryItemAction(id: action.id)
            } label: {
                Text(action.title)
            }
        }
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
        if !snapshot.actions.isEmpty {
            ToolbarItem {
                Menu {
                    // The producer's top-level overflow ("Clear history") action,
                    // rendered through the shared list-action menu helper.
                    listActionMenuItems(actions: snapshot.actions) {
                        cipherDetailModel.invokePasswordHistoryAction(id: $0)
                    }
                } label: {
                    Label(L10n.actions, systemImage: "ellipsis.circle")
                }
            }
        }
    }

}
