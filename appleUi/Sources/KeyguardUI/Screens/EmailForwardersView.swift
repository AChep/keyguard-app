import SwiftUI
import KeyguardShared

struct EmailForwardersView: View {
    @Environment(EmailRelayModel.self) private var emailRelayModel
    @Environment(NavigationModel.self) private var navigationModel

    let entry: ScreenEntrySnapshot

    /// Each entry is a blank form the "add" menu opens.
    @State private var services: [EmailRelayFormSnapshot] = []
    @State private var sheet: EmailForwarderSheet?
    @State private var pendingDetailAction: (itemId: String, actionId: String)?
    @State private var pendingDelete: EmailRelayActionRequestSnapshot?
    @State private var loadError: String?
    @State private var editingRelayId: String?

    @State private var selection = ListSelectionModel()

    private var snapshot: EmailRelayListSnapshot {
        navigationModel.navStacks.values.joined().first { $0.instanceId == entry.instanceId }?.emailRelayList
            ?? EmailRelayListSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if snapshot.status == EmailRelayLoadStatus.failed {
                ContentUnavailableView {
                    Label(L10n.errorFailedUnknown, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button(L10n.retry) { navigationModel.retryEntryList(instanceId: entry.instanceId) }
                }
            } else {
                SnapshotContent(loaded: snapshot.status == EmailRelayLoadStatus.ready, isEmpty: snapshot.items.isEmpty)
                {
                    empty
                } content: {
                    list
                }
            }
        }
        .navigationTitle(L10n.emailrelayListSectionTitle)
        .toolbar { toolbar }
        .task { await loadServices() }
        .task(id: editingRelayId) {
            guard let id = editingRelayId else { return }
            await loadForm(id: id)
            if !Task.isCancelled { editingRelayId = nil }
        }
        .listSelection(
            selection,
            selectionCount: snapshot.selectionCount,
            producerSelection: Set(snapshot.items.filter(\.selected).map(\.id)),
            isKnownId: { id in snapshot.items.contains { $0.id == id } },
            toggle: { navigationModel.toggleEntryListSelection(instanceId: entry.instanceId, itemId: $0) },
            clear: { navigationModel.clearEntryListSelection(instanceId: entry.instanceId) }
        )
        .sheet(item: $sheet, onDismiss: invokePendingDetailAction) { sheet in
            switch sheet {
            case let .detail(id):
                if let item = snapshot.items.first(where: { $0.id == id }) {
                    EmailForwarderDetailSheet(item: item) { actionId in
                        pendingDetailAction = (itemId: id, actionId: actionId)
                        self.sheet = nil
                    }
                }
            case let .form(form):
                EmailForwarderFormView(form: form)
            }
        }
        .onChange(of: snapshot.items.map(\.id)) { _, ids in
            if case let .detail(id)? = sheet, !ids.contains(id) {
                pendingDetailAction = nil
                sheet = nil
            }
        }
        .alert(
            pendingDelete?.items.count == 1
                ? L10n.emailrelayDeleteOneConfirmationTitle : L10n.emailrelayDeleteManyConfirmationTitle,
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { item in
            Button(L10n.delete, role: .destructive) {
                emailRelayModel.deleteEmailRelays(ids: item.items.map(\.id))
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: { item in
            Text(item.items.map(\.name).joined(separator: "\n"))
        }
        .alert(
            L10n.errorFailedUnknown,
            isPresented: Binding(
                get: { loadError != nil },
                set: { if !$0 { loadError = nil } }
            )
        ) {
        } message: {
            Text(loadError ?? "")
        }
    }

    private var empty: some View {
        ContentUnavailableView {
            Label(L10n.emailrelayEmptyLabel, systemImage: "envelope")
        } description: {
            Text(L10n.emailrelayListAddServiceHint)
        }
    }

    private var list: some View {
        List(selection: editing ? $selection.selectedRowIds : nil) {
            ForEach(snapshot.items, id: \.id) { item in
                rowEntry(item)
                    .tag(item.id)
                    .contextMenu { rowContextMenu(item) }
                    .disabled(editingRelayId != nil)
            }
        }
        .selectionBar(
            count: snapshot.selectionCount,
            actions: snapshot.selectionActions,
            invoke: { requestAction($0) },
            clear: { navigationModel.clearEntryListSelection(instanceId: entry.instanceId) }
        )
    }

    @ViewBuilder
    private func rowEntry(_ item: EmailRelayListItemSnapshot) -> some View {
        if editing {
            row(item)
        } else {
            Button {
                sheet = .detail(id: item.id)
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

    private func row(_ item: EmailRelayListItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "envelope")
                .foregroundStyle(.tint)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                if !item.service.isEmpty {
                    Text(item.service)
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
    private func rowContextMenu(_ item: EmailRelayListItemSnapshot) -> some View {
        if showsBulkContextMenu {
            ForEach(snapshot.selectionActions, id: \.id) { action in
                ListItemActionButton(action: action) { requestAction($0) }
            }
        } else {
            #if os(macOS)
            Button {
                navigationModel.toggleEntryListSelection(instanceId: entry.instanceId, itemId: item.id)
            } label: {
                Label(L10n.select, systemImage: "checkmark.circle")
            }
            Divider()
            #endif
            ForEach(item.actions, id: \.id) { action in
                ListItemActionButton(action: action) { actionId in
                    requestAction(actionId, itemId: item.id)
                }
            }
        }
    }

    private func invokePendingDetailAction() {
        guard let pending = pendingDetailAction else { return }
        pendingDetailAction = nil
        guard let item = snapshot.items.first(where: { $0.id == pending.itemId }),
            item.actions.contains(where: { $0.id == pending.actionId })
        else { return }
        // The editor and deletion alert are presented only after details close.
        requestAction(pending.actionId, itemId: pending.itemId)
    }

    private func requestAction(_ actionId: String, itemId: String? = nil) {
        navigationModel.requestEntryEmailRelayAction(instanceId: entry.instanceId, actionId: actionId, itemId: itemId) {
            request in
            guard let request, let first = request.items.first else { return }
            if request.kind == EmailRelayActionKind.edit {
                editingRelayId = first.id
            } else if request.kind == EmailRelayActionKind.duplicate {
                emailRelayModel.duplicateEmailRelay(id: first.id)
            } else if request.kind == EmailRelayActionKind.delete_ {
                pendingDelete = request
            }
        }
    }

    private var showsBulkContextMenu: Bool {
        selection.showsBulkActions(selectionCount: snapshot.selectionCount)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if snapshot.selectionCount > 0 && snapshot.canSelectAll {
            ToolbarItem {
                Button(L10n.selectionSelectAllAction) {
                    navigationModel.selectAllEntryListItems(instanceId: entry.instanceId)
                }
            }
        }
        #if os(iOS)
        ToolbarItem(placement: .topBarLeading) {
            if !snapshot.items.isEmpty {
                EditButton()
            }
        }
        #endif
        ToolbarItem {
            Menu {
                if services.isEmpty {
                    Text(L10n.emailrelayListNoServicesTitle)
                } else {
                    ForEach(services, id: \.type) { service in
                        Button(service.serviceName) {
                            sheet = .form(service)
                        }
                    }
                }
            } label: {
                Label(L10n.emailrelayAddAction, systemImage: "plus")
            }
            .disabled(editingRelayId != nil)
        }
    }

    private func loadServices() async {
        do {
            let loaded = try await emailRelayModel.loadEmailRelayServices()
            guard !Task.isCancelled else { return }
            services = loaded
        } catch {
            guard !Task.isCancelled else { return }
            loadError = error.localizedDescription
        }
    }

    private func loadForm(id: String) async {
        do {
            let form = try await emailRelayModel.loadEmailRelay(id: id)
            guard !Task.isCancelled else { return }
            guard let form else {
                loadError = L10n.errorNotFound
                return
            }
            sheet = .form(form)
        } catch {
            guard !Task.isCancelled else { return }
            loadError = error.localizedDescription
        }
    }
}

struct EmailForwarderFormView: View {
    @Environment(EmailRelayModel.self) private var emailRelayModel
    @Environment(\.dismiss) private var dismiss

    let form: EmailRelayFormSnapshot

    @State private var name: String
    @State private var values: [String: String]
    @State private var isSaving = false
    @State private var errorText: String?

    init(form: EmailRelayFormSnapshot) {
        self.form = form
        _name = State(initialValue: form.name)
        _values = State(
            initialValue: form.fields.reduce(into: [:]) { values, field in
                values[field.key] = field.value
            })
    }

    private var isEditing: Bool { form.id != nil }

    private var canSave: Bool {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        for field in form.fields where !field.canBeEmpty {
            let value = values[field.key] ?? ""
            if value.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        }
        return true
    }

    var body: some View {
        ModalSheet(
            title: isEditing ? L10n.emailrelayEditAction : L10n.emailrelayAddAction,
            width: 460,
            height: 520,
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.genericName, text: $name)
                } footer: {
                    Text(form.serviceName)
                }
                ForEach(form.fields, id: \.key) { field in
                    fieldSection(field)
                }
                if let docUrl = form.docUrl, let url = URL(string: docUrl) {
                    Section {
                        Link(L10n.emailrelayDocumentationTitle, destination: url)
                    }
                }
                if let errorText {
                    Section {
                        Text(errorText).foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(isEditing ? L10n.save : L10n.add) { save() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave || isSaving)
        }
    }

    @ViewBuilder
    private func fieldSection(_ field: EmailRelayFieldSnapshot) -> some View {
        let binding = Binding<String>(
            get: { values[field.key] ?? "" },
            set: { values[field.key] = $0 }
        )
        Section {
            Group {
                if field.secret {
                    SecureField(field.hint ?? field.title, text: binding)
                } else {
                    TextField(field.hint ?? field.title, text: binding)
                }
            }
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
            .accessibilityLabel(field.title)
        } header: {
            Text(field.title)
        } footer: {
            if let description = field.fieldDescription, !description.isEmpty {
                Text(description)
            }
        }
    }

    private func save() {
        isSaving = true
        errorText = nil
        Task {
            do {
                try await emailRelayModel.saveEmailRelay(
                    id: form.id,
                    type: form.type,
                    name: name,
                    values: values
                )
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}
