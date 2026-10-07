import SwiftUI
import UniformTypeIdentifiers
import KeyguardShared

struct GpgToolsView: View {
    @Environment(GpgToolsModel.self) private var gpgToolsModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var operation: String
    @State private var keySelection: KeySelection?

    private enum KeySelection: String, Identifiable {
        case recipients, signing
        var id: String { rawValue }
    }

    private static var operations: [(key: String, title: String)] {
        [
            ("encrypt", L10n.encrypt), ("decrypt", L10n.decrypt),
            ("sign", L10n.sign), ("verify", L10n.verify),
        ]
    }

    private var gpgTools: GpgToolsSnapshot { gpgToolsModel.gpgTools }
    private var busy: Bool { gpgTools.busy || gpgToolsModel.gpgToolsNativeBusy }
    private var signingKeys: [GpgToolsKeySnapshot] { gpgTools.storedKeys.filter { $0.canSign } }
    private var recipientKeys: [GpgToolsKeySnapshot] { gpgTools.storedKeys.filter { $0.publicKeyAvailable } }
    private var selectedRecipients: [GpgToolsKeySnapshot] {
        recipientKeys.filter { gpgTools.selectedRecipientIds.contains($0.id) }
    }
    private var signingKeyId: String? {
        operation == "encrypt" ? gpgTools.selectedEncryptSigningKeyId : gpgTools.selectedPrivateKeyId
    }
    private var inputLabel: String {
        switch operation {
        case "decrypt": L10n.gpgToolsEncryptedTextLabel
        case "verify" where !gpgTools.showSignatureField: L10n.gpgToolsSignedTextLabel
        default: L10n.gpgToolsMessageLabel
        }
    }
    private var actionTitle: String {
        Self.operations.first { $0.key == operation }?.title ?? L10n.encrypt
    }
    private var operationSelection: Binding<String> {
        Binding(
            get: { operation },
            set: { value in
                guard !busy else { return }
                operation = value
            })
    }
    private var requirement: String? {
        if gpgTools.scope == "file" {
            if gpgTools.inputFile == nil { return L10n.gpgToolsFileRequired }
            if operation == "verify", gpgTools.signatureFile == nil { return L10n.gpgToolsSignatureFileRequired }
        } else {
            if gpgTools.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return L10n.gpgToolsTextRequired
            }
            if gpgTools.showSignatureField,
                gpgTools.signatureText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            {
                return L10n.gpgToolsSignatureRequired
            }
        }
        if operation == "encrypt", selectedRecipients.isEmpty, gpgTools.customPublicKeys.isEmpty {
            return L10n.gpgToolsNoRecipient
        }
        if operation == "sign", !signingKeys.contains(where: { $0.id == signingKeyId }) {
            return L10n.gpgToolsChooseSigningKey
        }
        return nil
    }

    var body: some View {
        #if os(iOS)
        let filePickerRequest = gpgToolsModel.pendingGpgToolsFilePicker
        #endif
        NavStackContainer(scope: "gpg_tools") {
            platformContent
                .navigationTitle(L10n.gpgToolsHeaderTitle)
                #if os(iOS)
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .primaryAction) { runButton }
            }
                #endif
        }
        .observing(
            start: { gpgToolsModel.startGpgToolsObservation(operation: operation) },
            stop: { gpgToolsModel.stopGpgToolsObservation() }
        )
        .onChange(of: operation) { _, value in
            keySelection = nil
            gpgToolsModel.startGpgToolsObservation(operation: value)
        }
        .sheet(item: $keySelection) { selection in
            keyChooser(selection)
        }
        .sheet(
            item: Binding(
                get: { gpgToolsModel.pendingGpgToolsPublicKey },
                set: { value in
                    if value == nil, let request = gpgToolsModel.pendingGpgToolsPublicKey {
                        gpgToolsModel.finishGpgToolsPublicKey(id: request.id, confirm: false)
                    }
                }
            )
        ) { request in
            GpgToolsPublicKeySheet(requestId: request.id, initialText: request.text)

                .onDisappear { gpgToolsModel.finishGpgToolsPublicKey(id: request.id, confirm: false) }
        }
        #if os(iOS)
        // SwiftUI resets presentation before returning the URL. Only these
        // callbacks may consume the request, including interactive cancellation.
        .fileImporter(
            isPresented: Binding(get: { filePickerRequest != nil }, set: { _ in }),
            allowedContentTypes: [.item],
            allowsMultipleSelection: false,
            onCompletion: { result in
                guard let request = filePickerRequest else { return }
                gpgToolsModel.completeGpgToolsFilePicker(
                    id: request.id,
                    result: result.flatMap { urls in
                        guard let url = urls.first else { return .failure(GpgToolsFileError.missingSelection) }
                        return .success(url)
                    })
            },
            onCancellation: {
                guard let request = filePickerRequest else { return }
                gpgToolsModel.cancelGpgToolsFilePicker(id: request.id)
            }
        )
        #endif
        .sheet(
            isPresented: Binding(
                get: { gpgToolsModel.gpgToolsResult != nil },
                set: { if !$0 { gpgToolsModel.dismissGpgToolsResult() } }
            )
        ) {
            if let result = gpgToolsModel.gpgToolsResult {
                GpgToolsResultSheet(result: result)
            }
        }
    }

    @ViewBuilder
    private var platformContent: some View {
        #if os(macOS)
        Group {
            if #available(macOS 15.0, *) {
                operationTabs.tabViewStyle(.grouped)
            } else {
                operationTabs
            }
        }
        .padding(20)
        .disabled(busy)
        #else
        workspace
        #endif
    }

    #if os(macOS)
    private var operationTabs: some View {
        TabView(selection: operationSelection) {
            ForEach(Self.operations, id: \.key) { item in
                Group {
                    if operation == item.key { workspace } else { Color.clear }
                }
                .tabItem { Text(item.title) }
                .tag(item.key)
            }
        }
    }
    #endif

    @ViewBuilder
    private var workspace: some View {
        #if os(macOS)
        if !gpgTools.loaded || gpgTools.operation != operation {
            LoadingIndicator()
        } else {
            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        editors
                        options
                        macKeyControls
                        additionalPublicKeys
                        nativeStatus
                        Divider()
                        HStack {
                            if let requirement { noteText(requirement) }
                            Spacer(minLength: 16)
                            runButton.keyboardShortcut(.return, modifiers: .command)
                        }
                    }
                    .frame(minHeight: max(0, geometry.size.height - 40))
                    .padding(20)
                }
            }
            .id(gpgToolsModel.gpgToolsObservationId)
        }
        #else
        // Keep the form and selector mounted while operation-specific fields reload.
        Form {
            Section {
                operationPicker
                    .disabled(busy)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if !gpgTools.loaded || gpgTools.operation != operation {
                Section { LoadingIndicator() }
            } else {
                Section { editors }
                    .id(gpgToolsModel.gpgToolsObservationId)
                if operation == "encrypt" || operation == "sign" {
                    Section {
                        if operation == "encrypt" {
                            chooserRow(
                                title: L10n.gpgToolsSelectRecipients,
                                value: selectedRecipients.isEmpty
                                    ? L10n.gpgToolsChooseRecipients
                                    : L10n.selectionNSelected(selectedRecipients.count)
                            ) { keySelection = .recipients }
                        }
                        chooserRow(title: L10n.gpgToolsSignWith, value: signingSummary) {
                            keySelection = .signing
                        }
                    }
                } else {
                    Section { automaticKeysNote }
                }
                if hasOptions { Section { options } }
                if operation != "sign" { Section { additionalPublicKeys } }
                if gpgToolsModel.gpgToolsImporting || gpgToolsModel.gpgToolsError != nil { Section { nativeStatus } }
                if let requirement { Section { noteText(requirement) } }
            }
        }
        .formStyle(.grouped)
        .scrollDismissesKeyboard(.interactively)
        #endif
    }

    private var operationPicker: some View {
        ViewThatFits(in: .horizontal) {
            if !dynamicTypeSize.isAccessibilitySize {
                operationChoices.pickerStyle(.segmented).fixedSize(horizontal: true, vertical: false)
            }
            operationChoices.pickerStyle(.menu)
        }
        .frame(maxWidth: .infinity)
    }

    private var operationChoices: some View {
        Picker(L10n.gpgToolsHeaderTitle, selection: operationSelection) {
            ForEach(Self.operations, id: \.key) { Text($0.title).tag($0.key) }
        }
        .labelsHidden()
    }

    private var editors: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker(
                L10n.input,
                selection: Binding(
                    get: { gpgTools.scope },
                    set: { value in gpgToolsModel.setGpgToolsScope(value) }
                )
            ) {
                ForEach(gpgTools.scopes, id: \.key) { Text($0.title).tag($0.key) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 280)
            .frame(maxWidth: .infinity, alignment: .center)
            if gpgTools.scope == "file" {
                fileInput(
                    label: L10n.gpgToolsSelectInputFile, file: gpgTools.inputFile,
                    choose: gpgToolsModel.selectGpgToolsInputFile, clear: gpgToolsModel.clearGpgToolsInputFile)
                if operation == "verify" {
                    fileInput(
                        label: L10n.gpgToolsSelectSignatureFile, file: gpgTools.signatureFile,
                        choose: gpgToolsModel.selectGpgToolsSignatureFile,
                        clear: gpgToolsModel.clearGpgToolsSignatureFile)
                }
            } else {
                editor(
                    label: inputLabel, text: gpgTools.inputText, revision: gpgTools.inputTextRevision,
                    send: gpgToolsModel.setGpgToolsInputText)
                if gpgTools.showSignatureField {
                    editor(
                        label: L10n.gpgToolsSignatureLabel, text: gpgTools.signatureText,
                        revision: gpgTools.signatureTextRevision, send: gpgToolsModel.setGpgToolsSignatureText)
                }
            }
        }
        .disabled(busy)
    }

    private func fileInput(
        label: String, file: GpgToolsFileSnapshot?, choose: @escaping () -> Void,
        clear: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.headline)
            HStack {
                Button(action: choose) {
                    Label(file?.name ?? L10n.chooseFile, systemImage: "doc")
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                if let file {
                    if let size = file.size {
                        Text(ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Button(L10n.remove, action: clear)
                        .accessibilityLabel(Text(L10n.remove + ": " + file.name))
                }
            }
        }
        .buttonStyle(.borderless)
    }

    private func editor(label: String, text: String, revision: Int32, send: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.headline)
            GpgToolsTextInput(label: label, text: text, textRevision: revision, send: send)
        }
    }

    private var hasOptions: Bool {
        (gpgTools.scope == "text" && operation == "sign" && gpgTools.signModes.count > 1)
            || (gpgTools.scope == "text" && operation == "verify" && gpgTools.verifyModes.count > 1)
            || gpgTools.showArmor
    }

    @ViewBuilder
    private var options: some View {
        if gpgTools.scope == "text", operation == "sign", gpgTools.signModes.count > 1 {
            Picker(
                L10n.gpgToolsFormatLabel,
                selection: Binding(
                    get: { gpgTools.signMode },
                    set: { value in gpgToolsModel.setGpgToolsSignMode(value) }
                )
            ) {
                ForEach(gpgTools.signModes, id: \.key) { Text($0.title).tag($0.key) }
            }
            .disabled(busy)
        }
        if gpgTools.scope == "text", operation == "verify", gpgTools.verifyModes.count > 1 {
            Picker(
                L10n.gpgToolsFormatLabel,
                selection: Binding(
                    get: { gpgTools.verifyMode },
                    set: { value in gpgToolsModel.setGpgToolsVerifyMode(value) }
                )
            ) {
                ForEach(gpgTools.verifyModes, id: \.key) { Text($0.title).tag($0.key) }
            }
            .disabled(busy)
        }
        if gpgTools.showArmor {
            Toggle(
                L10n.gpgToolsArmorLabel,
                isOn: Binding(
                    get: { gpgTools.armor },
                    set: { value in gpgToolsModel.setGpgToolsArmor(value) }
                )
            )
            .disabled(busy)
        }
    }

    #if os(macOS)
    @ViewBuilder
    private var macKeyControls: some View {
        if operation == "encrypt" {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(L10n.gpgToolsSelectRecipients).font(.headline)
                    Spacer()
                    Button(L10n.gpgToolsAddRecipients) { keySelection = .recipients }
                }
                if !selectedRecipients.isEmpty {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(selectedRecipients, id: \.id) { key in
                                HStack {
                                    GpgToolsKeyIdentity(key: key)
                                    Spacer()
                                    Button {
                                        gpgToolsModel.toggleGpgToolsRecipient(key.id)
                                    } label: {
                                        Image(systemName: "minus.circle")
                                    }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel(Text(L10n.remove + ": " + key.title))
                                }
                            }
                        }
                    }
                    .frame(height: min(CGFloat(selectedRecipients.count) * 48, 144))
                }
            }
            .disabled(busy)
        }
        if operation == "encrypt" || operation == "sign" {
            if signingKeys.count <= 8, Set(signingKeys.map(\.title)).count == signingKeys.count {
                Picker(
                    L10n.gpgToolsSignWith,
                    selection: Binding<String?>(
                        get: { signingKeyId },
                        set: { value in selectSigningKey(value) }
                    )
                ) {
                    Text(operation == "encrypt" ? L10n.gpgToolsSignNone : L10n.gpgToolsChooseSigningKey)
                        .tag(nil as String?)
                        .disabled(operation == "sign")
                    ForEach(signingKeys, id: \.id) { key in
                        Text(key.title).tag(Optional(key.id))
                    }
                }
                .disabled(busy)
                if signingKeys.isEmpty { noteText(L10n.gpgToolsNoGpgKeys) }
                if let key = signingKeys.first(where: { $0.id == signingKeyId }), let fingerprint = key.description_ {
                    Text(fingerprint).font(.caption.monospaced()).foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            } else {
                chooserRow(title: L10n.gpgToolsSignWith, value: signingSummary) { keySelection = .signing }
            }
        } else {
            automaticKeysNote
        }
    }
    #endif

    private var signingSummary: String {
        signingKeys.first { $0.id == signingKeyId }?.title
            ?? (operation == "encrypt" ? L10n.gpgToolsSignNone : L10n.gpgToolsChooseSigningKey)
    }

    private func selectSigningKey(_ id: String?) {
        if operation == "encrypt" {
            gpgToolsModel.selectGpgToolsEncryptSigningKey(id)
        } else if let id {
            gpgToolsModel.selectGpgToolsPrivateKey(id)
        }
    }

    private func chooserRow(title: String, value: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).foregroundStyle(.primary)
                Spacer(minLength: 16)
                Text(value).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .frame(minHeight: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    private var automaticKeysNote: some View {
        noteText(operation == "verify" ? L10n.gpgToolsStoredPublicKeysNote : L10n.gpgToolsDecryptKeysNote)
    }

    @ViewBuilder
    private var additionalPublicKeys: some View {
        if operation != "sign" {
            VStack(alignment: .leading, spacing: 12) {
                Text(L10n.gpgToolsAdditionalPublicKeys).font(.headline)
                ForEach(gpgTools.customPublicKeys, id: \.id) { key in
                    HStack(alignment: .top) {
                        GpgToolsPublicKeyIdentity(key: key)
                        Spacer(minLength: 8)
                        Button {
                            gpgToolsModel.removeGpgToolsPublicKey(key.id)
                        } label: {
                            Image(systemName: "minus.circle")
                                .touchTarget()
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(Text(L10n.remove + ": " + key.title))
                    }
                }
                Button(L10n.gpgToolsPublicKeyAdd) { gpgToolsModel.addGpgToolsPublicKey() }
            }
            .disabled(busy)
        }
    }

    @ViewBuilder
    private var nativeStatus: some View {
        if gpgToolsModel.gpgToolsImporting {
            ProgressView(L10n.gpgToolsImporting)
        }
        if let error = gpgToolsModel.gpgToolsError {
            Label(error, systemImage: "exclamationmark.triangle")
                .font(.callout).foregroundStyle(.red)
        }
    }

    private var runButton: some View {
        HStack(spacing: 8) {
            if gpgTools.busy { ProgressView().controlSize(.small).accessibilityLabel(Text(actionTitle)) }
            Button(actionTitle) { gpgToolsModel.runGpgTools() }
                .buttonStyle(.borderedProminent)
                .disabled(busy || !gpgTools.loaded || !gpgTools.canRun || requirement != nil)
        }
    }

    private func keyChooser(_ selection: KeySelection) -> some View {
        let recipients = selection == .recipients
        return GpgToolsKeyChooser(
            title: recipients ? L10n.gpgToolsSelectRecipients : L10n.gpgToolsSelectSigningKey,
            keys: recipients ? recipientKeys : signingKeys,
            multiple: recipients,
            allowsNone: !recipients && operation == "encrypt",
            selectedIds: recipients ? Set(gpgTools.selectedRecipientIds) : Set([signingKeyId].compactMap { $0 })
        ) { ids in
            if recipients {
                let available = Set(recipientKeys.map(\.id))
                let desired = ids.intersection(available)
                for id in Set(gpgTools.selectedRecipientIds).symmetricDifference(desired) {
                    gpgToolsModel.toggleGpgToolsRecipient(id)
                }
            } else {
                selectSigningKey(ids.first)
            }
        }
    }

    private func noteText(_ text: String) -> some View {
        Text(text).font(.callout).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct GpgToolsPublicKeyIdentity: View {
    let key: GpgToolsPublicKeySnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(key.title)
            Text(key.fingerprint).font(.caption.monospaced()).foregroundStyle(.secondary)
                .textSelection(.enabled)
            ForEach(Array(key.notes.enumerated()), id: \.offset) { _, note in
                Label(note.text, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// The submitted buffer must still match the checked text when Add is pressed.
private struct GpgToolsPublicKeySheet: View {
    @Environment(GpgToolsModel.self) private var gpgToolsModel
    let requestId: String
    let initialText: String
    @State private var buffer = ""
    @State private var checkedText: String?

    private var canAdd: Bool {
        guard checkedText == buffer,
            !gpgToolsModel.gpgToolsPublicKeyValidating,
            let validation = gpgToolsModel.gpgToolsPublicKeyValidation
        else { return false }
        return validation.error == nil && !validation.keys.isEmpty
    }

    var body: some View {
        ModalSheet(title: L10n.gpgToolsPublicKeyTitle, width: 640, height: 640, dismissLabel: L10n.cancel) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(L10n.gpgToolsPublicKeyDescription).font(.callout).foregroundStyle(.secondary)
                    TextEditor(text: $buffer)
                        .font(.body.monospaced())
                        .autocorrectionDisabled()
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                        #endif
                        .frame(minHeight: 180)
                        .modifier(GpgToolsEditorSurface())
                        .accessibilityLabel(Text(L10n.gpgToolsPastedPublicKeyPlaceholder))
                        .disabled(gpgToolsModel.gpgToolsPublicKeyValidating)
                    HStack {
                        Button(L10n.gpgToolsCheckPublicKey) {
                            checkedText = buffer
                            gpgToolsModel.validateGpgToolsPublicKey(id: requestId, text: buffer)
                        }
                        .disabled(
                            buffer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                || gpgToolsModel.gpgToolsPublicKeyValidating)
                        if gpgToolsModel.gpgToolsPublicKeyValidating { ProgressView().controlSize(.small) }
                    }
                    if checkedText == buffer, let validation = gpgToolsModel.gpgToolsPublicKeyValidation {
                        if let error = validation.error {
                            Label(error, systemImage: "exclamationmark.triangle")
                                .font(.callout).foregroundStyle(.red)
                        }
                        ForEach(validation.keys, id: \.id) { key in
                            GpgToolsPublicKeyIdentity(key: key)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
        } actions: {
            Button(L10n.add) {
                guard canAdd else { return }
                gpgToolsModel.finishGpgToolsPublicKey(id: requestId, confirm: true)
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!canAdd)
        }
        .onAppear { buffer = initialText }
        .onChange(of: buffer) { _, _ in
            checkedText = nil
            gpgToolsModel.invalidateGpgToolsPublicKeyValidation()
        }
    }
}

private struct GpgToolsKeyIdentity: View {
    let key: GpgToolsKeySnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(key.title).foregroundStyle(.primary)
            if let fingerprint = key.description_, !fingerprint.isEmpty {
                Text(fingerprint).font(.caption.monospaced()).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
    }
}

/// Edits a local selection; only Done commits it to the shared producer.
private struct GpgToolsKeyChooser: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let keys: [GpgToolsKeySnapshot]
    let multiple: Bool
    let allowsNone: Bool
    let commit: (Set<String>) -> Void
    @State private var selectedIds: Set<String>
    @State private var search = ""

    init(
        title: String, keys: [GpgToolsKeySnapshot], multiple: Bool, allowsNone: Bool,
        selectedIds: Set<String>, commit: @escaping (Set<String>) -> Void
    ) {
        self.title = title
        self.keys = keys
        self.multiple = multiple
        self.allowsNone = allowsNone
        self.commit = commit
        _selectedIds = State(initialValue: selectedIds)
    }

    private var filteredKeys: [GpgToolsKeySnapshot] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return keys.filter {
            query.isEmpty || $0.title.localizedStandardContains(query)
                || ($0.description_ ?? "").replacingOccurrences(of: " ", with: "")
                    .localizedStandardContains(query.replacingOccurrences(of: " ", with: ""))
        }
    }

    var body: some View {
        ModalSheet(title: title, dismissLabel: L10n.cancel) {
            VStack(spacing: 0) {
                #if os(macOS)
                NativeListSearchField(text: $search, prompt: L10n.gpgToolsSearchKeys).padding(12)
                #endif
                List {
                    if allowsNone {
                        selectionRow(selected: selectedIds.isEmpty) {
                            selectedIds.removeAll()
                        } label: {
                            Text(L10n.gpgToolsSignNone)
                        }
                    }
                    ForEach(filteredKeys, id: \.id) { key in
                        selectionRow(selected: selectedIds.contains(key.id)) {
                            if multiple {
                                if !selectedIds.insert(key.id).inserted { selectedIds.remove(key.id) }
                            } else {
                                selectedIds = [key.id]
                            }
                        } label: {
                            GpgToolsKeyIdentity(key: key)
                        }
                    }
                    if filteredKeys.isEmpty {
                        Text(keys.isEmpty ? L10n.gpgToolsNoGpgKeys : L10n.gpgToolsNoMatchingKeys)
                            .foregroundStyle(.secondary)
                    }
                }
                #if os(iOS)
                .listSearchable(text: $search, prompt: Text(L10n.gpgToolsSearchKeys))
                #endif
            }
        } actions: {
            Button(L10n.gpgToolsSelectionDone) {
                commit(selectedIds.intersection(Set(keys.map(\.id))))
                dismiss()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!multiple && !allowsNone && !keys.contains { selectedIds.contains($0.id) })
        }
    }

    @ViewBuilder
    private func selectionRow<Content: View>(
        selected: Bool, action: @escaping () -> Void,
        @ViewBuilder label: () -> Content
    ) -> some View {
        #if os(macOS)
        if multiple {
            Toggle(isOn: Binding(get: { selected }, set: { _ in action() }), label: label)
                .toggleStyle(.checkbox)
        } else {
            selectionButton(selected: selected, action: action, label: label)
        }
        #else
        selectionButton(selected: selected, action: action, label: label)
        #endif
    }

    private func selectionButton<Content: View>(
        selected: Bool, action: @escaping () -> Void,
        @ViewBuilder label: () -> Content
    ) -> some View {
        Button(action: action) {
            HStack {
                label()
                Spacer(minLength: 8)
                Image(systemName: "checkmark").foregroundStyle(.tint).opacity(selected ? 1 : 0)
            }
            #if os(iOS)
            .frame(minHeight: 44)
            #endif
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// Preserve exact message/signature text, including line breaks, through the bridge.
private struct GpgToolsTextInput: View {
    let label: String
    let text: String
    let textRevision: Int32
    let send: (String) -> Void
    @State private var buffer = ""
    @ScaledMetric(relativeTo: .body) private var editorHeight = 180.0

    var body: some View {
        TextEditor(text: $buffer)
            .font(.body.monospaced())
            .autocorrectionDisabled()
            #if os(iOS)
        .textInputAutocapitalization(.never)
        .frame(height: editorHeight)
            #else
        .frame(minHeight: 110, maxHeight: .infinity)
            #endif
            .modifier(GpgToolsEditorSurface())
            .accessibilityLabel(Text(label))
            .bridgedText($buffer, remote: text, remoteRevision: textRevision, send: send)
    }
}

/// Results keep output visible while copying, with explicit dismissal.
private struct GpgToolsResultSheet: View {
    @Environment(GpgToolsModel.self) private var gpgToolsModel
    let result: GpgToolsResultSnapshot

    private func noteColor(_ kind: String) -> Color {
        switch kind {
        case "ok": return .green
        case "error": return .red
        case "warning": return .orange
        default: return .secondary
        }
    }

    private func noteSymbol(_ kind: String) -> String {
        switch kind {
        case "ok": return "checkmark.circle"
        case "error": return "xmark.circle"
        case "warning": return "exclamationmark.triangle"
        default: return "info.circle"
        }
    }

    var body: some View {
        #if os(iOS)
        let exportRequest = gpgToolsModel.pendingGpgToolsExport
        #endif
        ModalSheet(title: result.title, width: 640, height: 560) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let file = result.file {
                        VStack(alignment: .leading, spacing: 8) {
                            Label(file.name, systemImage: "doc").font(.headline)
                            if let size = file.size {
                                Text(ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file))
                                    .font(.callout).foregroundStyle(.secondary)
                            }
                            Text(L10n.gpgToolsReadyToSave).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(Array(result.notes.enumerated()), id: \.offset) { _, note in
                        if !note.text.isEmpty {
                            Label(note.text, systemImage: noteSymbol(note.kind))
                                .font(.callout)
                                .foregroundStyle(noteColor(note.kind))
                                .textSelection(.enabled)
                        }
                    }
                    if let output = result.outputText {
                        VStack(alignment: .leading, spacing: 8) {
                            if let label = result.outputLabel, !label.isEmpty {
                                Text(label).font(.headline)
                            }
                            Text(output)
                                .font(.body.monospaced())
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    if gpgToolsModel.gpgToolsNativeBusy {
                        ProgressView(L10n.gpgToolsExporting)
                    }
                    if gpgToolsModel.gpgToolsExportSucceeded {
                        Label(L10n.gpgToolsSaved, systemImage: "checkmark.circle")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    if let error = gpgToolsModel.gpgToolsError {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.callout).foregroundStyle(.red)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
        } actions: {
            if result.canCopy {
                Button {
                    gpgToolsModel.invokeGpgToolsResultCopy()
                } label: {
                    Label(L10n.copy, systemImage: "doc.on.doc")
                }
            }
            if result.canSave {
                Button {
                    gpgToolsModel.invokeGpgToolsResultSave()
                } label: {
                    Label(L10n.save, systemImage: "square.and.arrow.up")
                }
                .disabled(gpgToolsModel.gpgToolsNativeBusy)
            }
        }
        .interactiveDismissDisabled(gpgToolsModel.gpgToolsNativeBusy)
        #if os(iOS)
        // The presentation binding is reset before success/cancellation arrives.
        // Keep the captured request (and its artifact lease) until that callback.
        .fileExporter(
            isPresented: Binding(get: { exportRequest != nil }, set: { _ in }),
            item: exportRequest.map { GpgToolsExportFile(request: $0) },
            defaultFilename: exportRequest?.name,
            onCompletion: { outcome in
                guard let request = exportRequest else { return }
                gpgToolsModel.completeGpgToolsExport(request, result: outcome.map { _ in true })
            },
            onCancellation: {
                guard let request = exportRequest else { return }
                gpgToolsModel.completeGpgToolsExport(request, result: .success(false))
            }
        )
        #endif
    }
}
