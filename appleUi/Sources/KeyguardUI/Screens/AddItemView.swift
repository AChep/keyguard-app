import SwiftUI
import KeyguardShared
#if os(iOS)
import UniformTypeIdentifiers
#endif

/// Which kind of item the create sheet builds. Each maps onto a shared Kotlin
/// producer (`addCipherStateProducer` for ciphers, `sendAddStateProducer` for
/// Sends) run headless inside `KeyguardCore`.
enum AddSheetMode {
    case cipher
    case send
}

/// A generated-value prefill carried into the create sheet from the generator's
/// "create login / SSH key" action. `id` lets it drive a `.sheet(item:)`.
struct AddCipherPrefill: Identifiable {
    let id = UUID()
    let type: String
    let name: String?
    let username: String?
    let password: String?
}

struct AddEditPrefill: Identifiable {
    let id = UUID()
    let requestId: String
    let isSend: Bool
}

/// A selectable type shown by the chooser that precedes the form.
private struct AddTypeOption: Identifiable {
    let id: String
    let title: String
    let systemImage: String
}

struct AddItemSheet: View {
    @Environment(AddItemModel.self) private var addItemModel
    @Environment(DialogsModel.self) private var dialogsModel
    @Environment(FilePickerModel.self) private var filePickerModel
    @Environment(\.dismiss) private var dismiss

    let mode: AddSheetMode
    /// Optional generated-value prefill (generator "create login / SSH key"): skips
    /// the type chooser and starts the form prefilled.
    let prefill: AddCipherPrefill?
    /// Optional edit-an-existing-item request (cipher / Send detail "edit", vault
    /// list "clone"): skips the type chooser and starts the form pre-filled from
    /// the stashed model rather than empty.
    let editRequest: AddEditPrefill?
    /// Whether an account can create a File send; the Send type chooser hides
    /// File otherwise, as the shared list does.
    let canCreateFileSend: Bool

    @State private var chosenType: String?
    @State private var didStartObservation = false

    init(
        mode: AddSheetMode,
        prefill: AddCipherPrefill? = nil,
        editRequest: AddEditPrefill? = nil,
        canCreateFileSend: Bool = true
    ) {
        self.mode = mode
        self.prefill = prefill
        self.editRequest = editRequest
        self.canCreateFileSend = canCreateFileSend
        // The edit form has no type chooser; jump straight to the form. The
        // concrete cipher / Send type comes from the stashed model, so any
        // non-nil placeholder skips the chooser.
        _chosenType = State(initialValue: prefill?.type ?? (editRequest != nil ? "" : nil))
    }

    private var isEditing: Bool { editRequest != nil }

    private var form: AddItemFormSnapshot { addItemModel.addForm }

    private var typeOptions: [AddTypeOption] {
        switch mode {
        case .cipher:
            return [
                AddTypeOption(id: "Login", title: L10n.cipherTypeLogin, systemImage: "person.crop.circle"),
                AddTypeOption(id: "SecureNote", title: L10n.additemTypeSecureNoteTitle, systemImage: "note.text"),
                AddTypeOption(id: "Card", title: L10n.cipherTypeCard, systemImage: "creditcard"),
                AddTypeOption(id: "Identity", title: L10n.cipherTypeIdentity, systemImage: "person.text.rectangle"),
                AddTypeOption(id: "SshKey", title: L10n.cipherTypeSshKey, systemImage: "key"),
                AddTypeOption(id: "GpgKey", title: L10n.cipherTypeGpgKey, systemImage: "key.horizontal"),
            ]
        case .send:
            var options = [
                AddTypeOption(id: "Text", title: L10n.sendTypeText, systemImage: "text.alignleft")
            ]
            if canCreateFileSend {
                options.append(AddTypeOption(id: "File", title: L10n.sendTypeFile, systemImage: "doc"))
            }
            return options
        }
    }

    var body: some View {
        ModalSheet(
            title: addItemModel.keyGenerator?.title ?? sheetTitle,
            width: 520,
            height: 600,
            detents: [.large],
            dismissLabel: addItemModel.keyGenerator == nil ? L10n.cancel : L10n.additemKeyGeneratorBackTitle,
            onDismiss: {
                if addItemModel.keyGenerator != nil {
                    addItemModel.stopKeyGenerator()
                } else {
                    dismiss()
                }
            }
        ) {
            Group {
                if let generator = addItemModel.keyGenerator {
                    AddKeyGeneratorView(model: generator)
                } else if chosenType == nil {
                    chooser
                } else {
                    formView
                }
            }
        } actions: {
            if let generator = addItemModel.keyGenerator {
                Button(L10n.additemKeyUseTitle) { addItemModel.useGeneratedKey() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!generator.snapshot.canUseKey)
            } else if chosenType != nil {
                Button(L10n.save) {
                    addItemModel.submitAddItem()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!form.canSave)
            }
        }
        .onChange(of: addItemModel.addFormDidSave) { _, saved in
            if saved { dismiss() }
        }
        .onAppear {
            // A confirmation or picker can temporarily cover the editor. Keep
            // its producer and unsaved draft alive until the sheet is dismissed.
            guard !didStartObservation else { return }
            didStartObservation = true
            if let editRequest {
                // Editing an existing cipher / Send: the chooser is skipped and the
                // form starts from the stashed model (carrying its initialValue).
                if editRequest.isSend {
                    addItemModel.startEditSendObservation(requestId: editRequest.requestId)
                } else {
                    addItemModel.startEditCipherObservation(requestId: editRequest.requestId)
                }
            } else if let prefill {
                // Prefilled (generator) case: the chooser is skipped, so start the
                // producer here with the generated value.
                addItemModel.startAddCipherObservation(
                    type: prefill.type,
                    name: prefill.name,
                    username: prefill.username,
                    password: prefill.password
                )
            }
        }
        // Date requests originate from this form and must be presented above it.
        .sheet(
            item: Binding(
                get: { filePickerModel.pendingDatePicker.flatMap { $0.request.presentsInAddForm ? $0 : nil } },
                set: { if $0 == nil { filePickerModel.cancelDatePicker() } }
            )
        ) { pending in
            DatePickerSheet(request: pending.request)
        }
        .sheet(
            isPresented: Binding(
                get: { dialogsModel.cipherLinkPicker != nil },
                set: { if !$0 { dialogsModel.closeCipherLinkPicker() } }
            )
        ) {
            CipherLinkPickerSheet()
        }
        .sheet(isPresented: accountPickerPresented) {
            AccountPickerView()
        }
        // A root sibling sheet replaces this editor on iOS and triggers its
        // dismissal cleanup. Present removal and other form confirmations here
        // so the existing producer and its unsaved fields remain alive.
        .sheet(isPresented: confirmationPresented) {
            ConfirmationDialogView()
        }
        #if os(iOS)
        // Present above this modal form, rather than from the covered root.
        .pendingFileImporter { $0.presentsInAddForm }
        #endif
        // Import and mutation results arrive on the shared message bus. Native
        // sheets cover the root overlay, so keep feedback inside the editor.
        .appToastOverlay()
    }

    private var confirmationPresented: Binding<Bool> {
        Binding(
            get: { dialogsModel.confirmation != nil },
            set: { if !$0 { dialogsModel.closeConfirmation() } }
        )
    }

    /// Drives the "Save to" account picker sheet from `dialogsModel.accountPicker`; an external
    /// dismissal routes back through `closeAccountPicker` to cancel the producer.
    private var accountPickerPresented: Binding<Bool> {
        Binding(
            get: { dialogsModel.accountPicker != nil },
            set: { if !$0 { dialogsModel.closeAccountPicker() } }
        )
    }

    /// The navigation-bar / panel title for the current page.
    private var sheetTitle: String {
        if isEditing {
            // The producer sets the title to the edited item's name; while it
            // loads, show a generic "edit" header.
            if form.loaded { return form.title }
            return mode == .cipher ? L10n.additemHeaderEditTitle : L10n.addsendHeaderEditTitle
        }
        if chosenType == nil {
            return mode == .cipher ? L10n.additemHeaderNewTitle : L10n.addsendHeaderNewTitle
        }
        return form.loaded ? form.title : L10n.loading
    }

    // MARK: - Type chooser

    /// Grouped-form list of creatable types, one navigable row per type in the
    /// System Settings style: tinted symbol, title, trailing chevron. Picking a
    /// row immediately starts the matching form (no select-then-confirm step).
    private var chooser: some View {
        Form {
            Section {
                ForEach(typeOptions) { option in
                    Button {
                        select(type: option.id)
                    } label: {
                        HStack {
                            Label {
                                Text(option.title)
                            } icon: {
                                Image(systemName: option.systemImage)
                                    .foregroundStyle(.tint)
                                    .frame(width: 20)
                            }
                            Spacer()
                            #if os(iOS)
                            Image(systemName: "chevron.forward")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                            #endif
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .formStyle(.grouped)
    }

    private func select(type: String) {
        chosenType = type
        switch mode {
        case .cipher: addItemModel.startAddCipherObservation(type: type)
        case .send: addItemModel.startAddSendObservation(type: type)
        }
    }

    // MARK: - Form

    @ViewBuilder
    private var formView: some View {
        if !form.loaded {
            LoadingIndicator()
        } else {
            Form {
                if let merge = form.merge {
                    Section(L10n.additemHeaderMergeTitle) {
                        mergeSection(merge)
                    }
                }
                if let ownership = form.ownership {
                    Section {
                        ownershipRow(ownership)
                    }
                }
                ForEach(AddFormSection.sections(from: form.items)) { section in
                    Section {
                        ForEach(section.items, id: \.id) { item in
                            AddItemRow(item: item)
                        }
                    } header: {
                        if let title = section.title, !title.isEmpty {
                            Text(title)
                        }
                    }
                }
                if !form.actions.isEmpty {
                    Section {
                        ForEach(form.actions, id: \.id) { action in
                            Button(action.title) { addItemModel.invokeAddAction(id: action.id) }
                        }
                    }
                }
            }
            .formStyle(.grouped)
            .fileDropTarget(text: form.fileDropText) { url in
                addItemModel.dropFileOnForm(url: url)
            }
        }
    }

    private func mergeSection(_ merge: AddMergeSnapshot) -> some View {
        Group {
            ForEach(merge.sources, id: \.id) { source in
                Label(source.title, systemImage: "doc.on.doc")
                    .font(.subheadline)
            }
            if let note = merge.note, !note.isEmpty {
                Label(note, systemImage: "info.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Picker(
                L10n.additemHeaderMergeTitle,
                selection: Binding(
                    get: { merge.actions.first(where: { $0.selected })?.id ?? "" },
                    set: { addItemModel.invokeAddAction(id: $0) }
                )
            ) {
                ForEach(merge.actions, id: \.id) { action in
                    Text(action.title).tag(action.id)
                }
            }
            .pickerStyle(.menu)
            .disabled(!merge.canChange)
        }
    }

    /// The ownership "Save to" account row, mirroring the Compose add screen's
    /// ownership selector: the chosen account name + email, tappable (when not
    /// read-only) to open the native account picker dialog.
    @ViewBuilder
    private func ownershipRow(_ ownership: AddOwnershipSnapshot) -> some View {
        Button {
            addItemModel.invokeAddOwnership()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle")
                    .foregroundStyle(.tint)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.saveTo)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(ownership.title)
                        .foregroundStyle(.primary)
                    if let text = ownership.text, !text.isEmpty {
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if ownership.canPick {
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!ownership.canPick)
    }
}

/// Renders one flat `AddItemSnapshot`, switching on its `kind`. Mirrors the
/// shared Compose add screen, projecting each variant onto a native control.
private struct AddItemRow: View {
    @Environment(AddItemModel.self) private var addItemModel
    let item: AddItemSnapshot

    #if os(iOS)
    /// Drives the camera QR-scan sheet for the TOTP secret (iOS only).
    @State private var scanningTotp = false
    #endif

    var body: some View {
        let kind = item.kind
        if kind == AddItemKind.title {
            simpleField(label: L10n.genericName)
        } else if kind == AddItemKind.username {
            simpleField(label: L10n.username, hint: .username)
        } else if kind == AddItemKind.password {
            simpleField(label: item.title ?? L10n.password, hint: .password)
        } else if kind == AddItemKind.link {
            linkRow
        } else if kind == AddItemKind.text {
            simpleField(label: item.title)
        } else if kind == AddItemKind.note {
            simpleField(label: L10n.notes)
        } else if kind == AddItemKind.totp {
            totpRow
        } else if kind == AddItemKind.url {
            urlRow
        } else if kind == AddItemKind.fieldText {
            customFieldRow
        } else if kind == AddItemKind.fieldSwitch {
            customSwitchRow
        } else if kind == AddItemKind.fieldLinkedId {
            linkedIdRow
        } else if kind == AddItemKind.tag {
            customFieldRow
        } else if kind == AddItemKind.passkey {
            passkeyRow
        } else if kind == AddItemKind.attachment {
            attachmentRow
        } else if kind == AddItemKind.sshKey {
            sshKeyRow
        } else if kind == AddItemKind.gpgKey {
            gpgKeyRow
        } else if kind == AddItemKind.enumField {
            enumRow
        } else if kind == AddItemKind.switchField {
            switchRow
        } else if kind == AddItemKind.dateMonthYear {
            dateMonthYearRow
        } else if kind == AddItemKind.dateTime {
            dateTimeRow
        } else if kind == AddItemKind.add {
            addMenuRow
        } else if kind == AddItemKind.suggestion {
            suggestionRow
        }
    }

    // MARK: - Generic field

    private var firstField: AddTextFieldSnapshot? { item.fields.first }

    private func simpleField(label: String?, hint: AddFieldInputHint = .plain) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(item.fields, id: \.id) { field in
                fieldEditor(field: field, fallbackLabel: label, hint: hint)
            }
        }
    }

    private func fieldEditor(
        field: AddTextFieldSnapshot,
        fallbackLabel: String?,
        hint: AddFieldInputHint = .plain
    ) -> some View {
        AddFieldEditor(
            field: field,
            fallbackLabel: fallbackLabel,
            hint: hint,
            onChange: { addItemModel.setAddField(id: field.id, text: $0) },
            onAutofill: { addItemModel.setAddFieldText(id: field.id, text: $0) }
        )
    }

    private var linkRow: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title ?? L10n.cipherLinkUnavailableTitle)
                if let text = item.text, !text.isEmpty {
                    Text(text).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer()
            overflowMenu(item.options)
        }
    }

    // MARK: - TOTP (manual secret + iOS camera QR scan)

    private var totpRow: some View {
        #if os(iOS)
        return HStack(spacing: 8) {
            simpleField(label: L10n.additemLoginTotpLabel, hint: .totp)
            if let scanId = item.totpScanId {
                Button {
                    scanningTotp = true
                } label: {
                    Label(L10n.scanqrTitle, systemImage: "qrcode.viewfinder")
                        .touchTarget()
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .sheet(isPresented: $scanningTotp) {
                    QRScannerView { value in
                        addItemModel.scanAddTotp(id: scanId, value: value)
                    }
                }
            }
        }
        #else
        return simpleField(label: L10n.additemLoginTotpLabel, hint: .totp)
        #endif
    }

    // MARK: - URL (text + match-type dropdown + overflow)

    private var urlRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let field = firstField {
                fieldEditor(field: field, fallbackLabel: L10n.additemLoginUriLabel, hint: .url)
            }
            HStack {
                if !item.options.isEmpty {
                    dropdown(
                        title: item.enumValue ?? L10n.uriMatchDetectionTitle,
                        options: item.options
                    )
                }
                Spacer()
                overflowMenu(item.actions)
            }
        }
    }

    // MARK: - Custom fields

    private var customFieldRow: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(item.fields, id: \.id) { field in
                    fieldEditor(field: field, fallbackLabel: nil)
                }
            }
            overflowMenu(item.actions)
        }
    }

    private var customSwitchRow: some View {
        HStack(spacing: 8) {
            if let field = firstField {
                fieldEditor(field: field, fallbackLabel: L10n.genericName)
            }
            switchToggle(
                label: firstField?.value.isEmpty == false ? firstField?.value : L10n.customFieldToggleBooleanValue
            )
            .labelsHidden()
            overflowMenu(item.actions)
        }
    }

    private var linkedIdRow: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                if let field = firstField {
                    fieldEditor(field: field, fallbackLabel: L10n.genericName)
                }
                if let value = item.enumValue, !item.options.isEmpty {
                    dropdown(title: value, options: item.options)
                }
            }
            overflowMenu(item.actions)
        }
    }

    // MARK: - Passkey

    private var passkeyRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "person.badge.key")
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.passkey)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(item.passkeyName ?? "—")
            }
            Spacer()
            overflowMenu(item.actions)
        }
    }

    // MARK: - Attachment

    private var attachmentRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "paperclip")
                .foregroundStyle(.secondary)
                .overlay(alignment: .bottomTrailing) {
                    if let attachment = item.attachment {
                        attachmentSyncBadge(synced: attachment.synced)
                    }
                }
            if let field = firstField {
                fieldEditor(field: field, fallbackLabel: L10n.fileNamePlaceholder)
            }
            if let size = item.attachment?.size {
                Text(size)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            overflowMenu(item.actions)
        }
        .fileDropTarget(text: item.attachment?.dropText) { url in
            addItemModel.dropFile(onItem: item.id, url: url)
        }
    }

    /// Whether the file already lives on the server or is waiting for the next sync.
    private func attachmentSyncBadge(synced: Bool) -> some View {
        Image(systemName: synced ? "checkmark.icloud.fill" : "icloud.and.arrow.up.fill")
            .font(.system(size: 8, weight: .semibold))
            .foregroundStyle(.tint)
            .padding(1)
            .background(.background, in: Circle())
            .offset(x: 5, y: 4)
            .accessibilityLabel(L10n.fileStatusPendingUpload)
            .accessibilityHidden(synced)
    }

    // MARK: - SSH key

    @ViewBuilder
    private var sshKeyRow: some View {
        if let key = item.sshKey, key.hasKey {
            VStack(alignment: .leading, spacing: 8) {
                if !key.fingerprint.isEmpty {
                    labeled(L10n.fingerprint, key.fingerprint)
                }
                if !key.publicKey.isEmpty {
                    labeled(L10n.publicKey, key.publicKey)
                }
            }
        }
        keyActions(hasKey: item.sshKey?.hasKey == true, isGpg: false)
            .disabled(item.sshKey?.canChange != true)
    }

    // MARK: - GPG key

    @ViewBuilder
    private var gpgKeyRow: some View {
        if let key = item.gpgKey, key.hasKey {
            VStack(alignment: .leading, spacing: 8) {
                if !key.userId.isEmpty {
                    labeled(L10n.genericName, key.userId)
                }
                if !key.fingerprint.isEmpty {
                    labeled(L10n.fingerprint, key.fingerprint)
                }
                if !key.publicKey.isEmpty {
                    labeled(L10n.publicKey, key.publicKey)
                }
            }
        }
        keyActions(hasKey: item.gpgKey?.hasKey == true, isGpg: true)
            .disabled(item.gpgKey?.canChange != true)
    }

    @ViewBuilder
    private func keyActions(hasKey: Bool, isGpg: Bool) -> some View {
        if hasKey {
            Menu(L10n.additemKeyReplaceTitle) {
                keyActionButtons(isGpg: isGpg)
            }
            .menuStyle(.borderlessButton)
        } else {
            keyActionButtons(isGpg: isGpg)
        }
    }

    @ViewBuilder
    private func keyActionButtons(isGpg: Bool) -> some View {
        Button(
            isGpg ? L10n.additemGpgKeyGenerateTitle : L10n.additemSshKeyGenerateTitle, systemImage: "arrow.clockwise"
        ) {
            addItemModel.startKeyGenerator(item: item)
        }
        .labelStyle(.titleAndIcon)
        if let importAction = item.actions.first {
            Button(isGpg ? L10n.gpgKeyImportTitle : L10n.additemKeyImportTitle, systemImage: "square.and.arrow.down") {
                addItemModel.invokeAddAction(id: importAction.id)
            }
            .labelStyle(.titleAndIcon)
        }
    }

    private func labeled(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.body.monospaced())
                .textSelection(.enabled)
                .lineLimit(2)
                .truncationMode(.middle)
                // Refresh the selectable native text's accessibility value when
                // an imported key replaces the preview.
                .id(value)
        }
    }

    // MARK: - Enum dropdown

    private var enumRow: some View {
        LabeledContent(item.title ?? "") {
            // Some shared producers publish only a display value, without a
            // selected option (custom Send dates, for example).
            dropdown(title: item.enumValue ?? "", options: item.options)
        }
    }

    // MARK: - Switch

    private var switchRow: some View {
        switchToggle(label: item.title)
    }

    private func switchToggle(label: String?) -> some View {
        let binding = Binding<Bool>(
            get: { item.switchValue },
            set: { value in
                if let id = item.switchId { addItemModel.setAddSwitch(id: id, value: value) }
            }
        )
        return Toggle(isOn: binding) {
            VStack(alignment: .leading, spacing: 2) {
                if let label = label, !label.isEmpty {
                    Text(label)
                }
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .disabled(!item.switchEnabled)
    }

    // MARK: - Dates

    private var dateMonthYearRow: some View {
        LabeledContent(item.title ?? "") {
            HStack(spacing: 8) {
                ForEach(item.fields, id: \.id) { field in
                    fieldEditor(field: field, fallbackLabel: nil)
                }
                if let pick = item.actions.first {
                    Button {
                        addItemModel.invokeAddAction(id: pick.id)
                    } label: {
                        Label(L10n.datepickerTitle, systemImage: "calendar")
                            .touchTarget()
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                }
            }
        }
    }

    private var dateTimeRow: some View {
        HStack(spacing: 8) {
            ForEach(item.actions, id: \.id) { action in
                Button(action.title.isEmpty ? L10n.select : action.title) {
                    addItemModel.invokeAddAction(id: action.id)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    // MARK: - Add-more menu

    private var addMenuTitle: String {
        guard let title = item.title, !title.isEmpty else { return L10n.add }
        return title
    }

    private var addMenuRow: some View {
        Menu {
            ForEach(item.options, id: \.id) { option in
                Button(option.title) { addItemModel.invokeAddAction(id: option.id) }
            }
        } label: {
            Label(addMenuTitle, systemImage: "plus")
                .labelStyle(.titleAndIcon)
        }
        .menuStyle(.borderlessButton)
    }

    // MARK: - Suggestions

    private var suggestionRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(item.options, id: \.id) { option in
                Button {
                    addItemModel.invokeAddAction(id: option.id)
                } label: {
                    HStack {
                        Text(option.title)
                        Spacer()
                        if option.selected {
                            Image(systemName: "checkmark")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option.selected ? .isSelected : [])
            }
        }
    }

    // MARK: - Shared bits

    private func dropdown(title: String, options: [AddActionSnapshot]) -> some View {
        Menu {
            ForEach(options, id: \.id) { option in
                Button {
                    addItemModel.invokeAddAction(id: option.id)
                } label: {
                    if option.selected {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Text(option.title)
                    }
                }
            }
        } label: {
            Text(title)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
    }

    @ViewBuilder
    private func overflowMenu(_ actions: [AddActionSnapshot]) -> some View {
        if !actions.isEmpty {
            Menu {
                ForEach(actions, id: \.id) { action in
                    Button(action.title) { addItemModel.invokeAddAction(id: action.id) }
                }
            } label: {
                Label(L10n.moreActions, systemImage: "ellipsis.circle")
                    .touchTarget()
            }
            .labelStyle(.iconOnly)
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }
}

private struct CipherLinkPickerSheet: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: L10n.cipherLinkPickerTitle,
            width: 480,
            height: 560,
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            if let snapshot = dialogsModel.cipherLinkPicker {
                VStack(alignment: .leading, spacing: 12) {
                    BridgedTextField(
                        label: L10n.vaultMainSearchPlaceholder,
                        text: snapshot.query,
                        textRevision: snapshot.queryRevision,
                        secure: false,
                        send: dialogsModel.setCipherLinkPickerQuery,
                        style: .roundedBorder
                    )
                    if snapshot.items.isEmpty {
                        Text(L10n.itemsEmptyLabel)
                            .foregroundStyle(.secondary)
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(snapshot.items, id: \.id) { item in
                                    Button {
                                        dialogsModel.selectCipherLinkPickerItem(id: item.id)
                                    } label: {
                                        HStack {
                                            VStack(alignment: .leading) {
                                                Text(item.title).foregroundStyle(.primary)
                                                if let text = item.text, !text.isEmpty {
                                                    Text(text).font(.subheadline).foregroundStyle(.secondary)
                                                }
                                            }
                                            Spacer()
                                            Image(systemName: "chevron.forward")
                                        }
                                        .padding(.vertical, 12)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding()
            }
        }
    }
}
