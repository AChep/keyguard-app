import SwiftUI
import KeyguardShared

struct GeneratorHistoryView: View {
    @Environment(GeneratorHistoryModel.self) private var generatorHistoryModel

    @State private var selection = ListSelectionModel()
    @State private var presentedItem: ListDetailSheetItem?
    @State private var pendingActionId: String?

    private var snapshot: GeneratorHistorySnapshot { generatorHistoryModel.generatorHistory }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            empty
        } content: {
            list
        }
        .navigationTitle(L10n.generatorhistoryHeaderTitle)
        .toolbar { toolbar }
        .listSelection(
            selection,
            selectionCount: snapshot.selectionCount,
            producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
            isKnownId: { id in snapshot.items.contains { $0.id == id } },
            toggle: generatorHistoryModel.toggleGeneratorHistorySelection(id:),
            clear: { generatorHistoryModel.clearGeneratorHistorySelection() }
        )
        .sheet(item: $presentedItem, onDismiss: invokePendingAction) { presentedItem in
            if let item = snapshot.items.first(where: { $0.id == presentedItem.id }) {
                GeneratorHistoryDetailSheet(item: item, invokeAction: requestDetailAction)
                    .appToastOverlay()
            }
        }
        .onChange(of: snapshot.items.map(\.id)) { _, ids in
            if let presentedItem, !ids.contains(presentedItem.id) {
                pendingActionId = nil
                self.presentedItem = nil
            }
        }
        .observing(
            start: { generatorHistoryModel.startGeneratorHistoryObservation() },
            stop: { generatorHistoryModel.stopGeneratorHistoryObservation() }
        )
    }

    private var empty: some View {
        ContentUnavailableView {
            Label(L10n.generatorhistoryEmptyLabel, systemImage: "clock.arrow.circlepath")
        } description: {
            Text(L10n.generatorhistoryEmptyText)
        }
    }

    private var list: some View {
        List(selection: editing ? $selection.selectedRowIds : nil) {
            ForEach(
                snapshotListSections(
                    snapshot.items, id: { $0.id },
                    sectionTitle: {
                        $0.kind == GeneratorHistoryItemKind.section ? $0.title : nil
                    })
            ) { section in
                Section {
                    ForEach(section.items, id: \.id) { item in
                        rowEntry(item)
                            .tag(item.id)
                            // Keep menu identity stable when selection changes.
                            .contextMenu { rowContextMenu(item) }
                    }
                } header: {
                    if let title = section.title { Text(title) }
                }
            }
        }
        .selectionBar(
            count: snapshot.selectionCount,
            actions: snapshot.selectionActions,
            invoke: { generatorHistoryModel.invokeGeneratorHistorySelectionAction(id: $0) },
            clear: { generatorHistoryModel.clearGeneratorHistorySelection() }
        )
    }

    @ViewBuilder
    private func rowEntry(_ item: GeneratorHistoryItemSnapshot) -> some View {
        if editing {
            row(item)
        } else {
            Button {
                presentedItem = ListDetailSheetItem(id: item.id)
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

    private func row(_ item: GeneratorHistoryItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon(for: item.type))
                .foregroundStyle(.tint)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                PasswordText(item.title, colorize: item.colorize)
                    .font(.body.monospaced())
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let date = item.date, !date.isEmpty {
                    Text(date)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .touchTarget()
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func rowContextMenu(_ item: GeneratorHistoryItemSnapshot) -> some View {
        if showsBulkContextMenu {
            ForEach(snapshot.selectionActions, id: \.id) { action in
                ListItemActionButton(
                    action: action,
                    invoke: generatorHistoryModel.invokeGeneratorHistorySelectionAction(id:)
                )
            }
        } else {
            #if os(macOS)
            Button {
                generatorHistoryModel.toggleGeneratorHistorySelection(id: item.id)
            } label: {
                Label(L10n.select, systemImage: "checkmark.circle")
            }
            Divider()
            #endif
            ForEach(item.actions, id: \.id) { action in
                if action.startsSection {
                    Divider()
                }
                ListItemActionButton(
                    action: action,
                    invoke: generatorHistoryModel.invokeGeneratorHistoryItemAction(id:)
                )
            }
        }
    }

    private func requestDetailAction(_ actionId: String) {
        guard let item = snapshot.items.first(where: { $0.id == presentedItem?.id }),
            let action = item.actions.first(where: { $0.id == actionId })
        else { return }
        if action.isCopy {
            // Keep the value visible and use the producer's sensitive clipboard
            // handling. The sheet hosts the resulting copy notification.
            generatorHistoryModel.invokeGeneratorHistoryItemAction(id: actionId)
        } else {
            // Large Type, breach checks, exports, and removal can present at the
            // app root. Close details before invoking any of those actions.
            pendingActionId = actionId
            presentedItem = nil
        }
    }

    private func invokePendingAction() {
        guard let actionId = pendingActionId else { return }
        pendingActionId = nil
        guard snapshot.items.contains(where: { item in item.actions.contains { $0.id == actionId } }) else { return }
        generatorHistoryModel.invokeGeneratorHistoryItemAction(id: actionId)
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
        if !snapshot.options.isEmpty {
            ToolbarItem {
                Menu {
                    listActionMenuItems(actions: snapshot.options) {
                        generatorHistoryModel.invokeGeneratorHistoryOption(id: $0)
                    }
                } label: {
                    Label(L10n.more, systemImage: "ellipsis.circle")
                }
            }
        }
    }

    /// The shared snapshot reports the type as an uppercase enum name, or nil when ambiguous.
    private func icon(for type: String?) -> String {
        switch type {
        case "PASSWORD": return "key"
        case "USERNAME": return "person"
        case "EMAIL": return "envelope"
        case "EMAIL_RELAY": return "envelope.badge.shield.half.filled"
        case "SSH_KEY": return "terminal"
        case "GPG_KEY": return "key.horizontal"
        default: return "doc.on.clipboard"
        }
    }
}
