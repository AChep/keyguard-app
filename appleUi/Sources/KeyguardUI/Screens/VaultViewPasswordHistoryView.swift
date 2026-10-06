import SwiftUI
import KeyguardShared

struct VaultViewPasswordHistoryView: View {
    let snapshot: PasswordHistorySnapshot
    let invokeItem: (String) -> Void
    let invokeSelection: @MainActor @Sendable (String) -> Void
    let invokeAction: (String) -> Void
    let toggleSelection: (String) -> Void
    let clearSelection: @MainActor @Sendable () -> Void

    @State private var selection = ListSelectionModel()

    var body: some View {
        content
            .toolbar { toolbar }
            .listSelection(
                selection,
                selectionCount: snapshot.selectionCount,
                producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
                isKnownId: { id in snapshot.items.contains { $0.id == id } },
                toggle: toggleSelection,
                clear: clearSelection
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
            invoke: invokeSelection,
            clear: clearSelection
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

    @ViewBuilder
    private func rowContextMenu(_ item: PasswordHistoryItemSnapshot) -> some View {
        if showsBulkContextMenu {
            listActionMenuItems(actions: snapshot.selectionActions) {
                invokeSelection($0)
            }
        } else {
            listActionMenuItems(actions: item.actions) {
                invokeItem($0)
            }
        }
    }

    /// Danger is a tint, not the destructive role: that role animates the row out
    /// before the producer's confirmation dialog appears.
    @ViewBuilder
    private func rowSwipeActions(_ item: PasswordHistoryItemSnapshot) -> some View {
        ForEach(item.actions, id: \.id) { action in
            Button {
                invokeItem(action.id)
            } label: {
                Text(action.title)
            }
            .tint(action.danger ? Color(platform: .platformDanger) : nil)
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
                    listActionMenuItems(actions: snapshot.actions) {
                        invokeAction($0)
                    }
                } label: {
                    Label(L10n.actions, systemImage: "ellipsis.circle")
                }
            }
        }
    }

}
