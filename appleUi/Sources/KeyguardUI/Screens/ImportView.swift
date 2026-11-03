import SwiftUI
import KeyguardShared

/// Imports items from another password manager's export file, or from a Keyguard backup, into
/// one account: pick a source, unlock it if needed, review, then import.
struct ImportView: View {
    @Environment(NavigationModel.self) private var navigationModel
    /// The root presents its session's request as the iOS document picker.
    @Environment(FilePickerModel.self) private var filePickerModel
    let entry: ScreenEntrySnapshot

    /// The snapshot keeps a request until Kotlin hears back; present each one once.
    @State private var presentedRequestId: String?

    private var snapshot: ImportSnapshot {
        entry.imports ?? ImportSnapshot.companion.empty
    }

    var body: some View {
        Group {
            if !snapshot.loaded || snapshot.stage == ImportStageKind.loading {
                LoadingIndicator()
            } else {
                content
            }
        }
        .navigationTitle(snapshot.title.isEmpty ? L10n.importHeaderTitle : snapshot.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar { toolbar }
        .onChange(of: snapshot.filePickerRequest?.requestId, initial: true) { _, _ in
            presentFilePicker()
        }
    }

    @ViewBuilder
    private var content: some View {
        switch snapshot.stage {
        case ImportStageKind.start:
            startForm
        case ImportStageKind.password:
            passwordForm
        case ImportStageKind.snapshots:
            snapshotsForm
        case ImportStageKind.review:
            reviewForm
        case ImportStageKind.empty:
            emptyForm
        case ImportStageKind.done:
            doneForm
        case ImportStageKind.error:
            errorForm
        default:
            LoadingIndicator()
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if snapshot.stage == ImportStageKind.review {
            ToolbarItem(placement: .confirmationAction) {
                if snapshot.importing {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button {
                        invoke(ImportAction.confirm)
                    } label: {
                        Label(L10n.importConfirmButton, systemImage: "square.and.arrow.down")
                    }
                    .disabled(!snapshot.canImport)
                }
            }
        } else if let action = continueAction {
            ToolbarItem(placement: .confirmationAction) {
                Button(L10n.continue) {
                    invoke(action.id)
                }
                .disabled(!action.enabled)
            }
        } else if snapshot.stage == ImportStageKind.error && snapshot.canRetry {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    invoke(ImportAction.retry)
                } label: {
                    Label(L10n.importRetryButton, systemImage: "arrow.clockwise")
                }
            }
        }
    }

    /// The Continue action of the password and snapshot steps.
    private var continueAction: (id: String, enabled: Bool)? {
        switch snapshot.stage {
        case ImportStageKind.password: (ImportAction.passwordConfirm, snapshot.canConfirmPassword)
        case ImportStageKind.snapshots: (ImportAction.snapshotsConfirm, snapshot.canConfirmSnapshot)
        default: nil
        }
    }

    // MARK: - Stages

    private var startForm: some View {
        Form {
            Section {
                Text(L10n.importHeaderText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !snapshot.formats.isEmpty {
                Section(L10n.importSourceFileSection) {
                    ForEach(snapshot.formats, id: \.key) { format in
                        Button {
                            invoke(format.actionId)
                        } label: {
                            DetailRowLabel(title: format.title, subtitle: format.text, disclosure: "chevron.forward")
                                .touchTarget()
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(format.actionId == nil)
                    }
                }
            }
            if !snapshot.backupLocations.isEmpty {
                Section(L10n.importSourceBackupSection) {
                    ForEach(snapshot.backupLocations, id: \.kind) { location in
                        let visuals = Self.backupLocationVisuals(location.kind)
                        Button {
                            invoke(location.actionId)
                        } label: {
                            Label(visuals.title, systemImage: visuals.symbol)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .touchTarget()
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(location.actionId == nil)
                    }
                }
            }
            if snapshot.canLearnMore {
                Section {
                    Button {
                        invoke(ImportAction.learnMore)
                    } label: {
                        Label(L10n.importLearnMore, systemImage: "arrow.up.forward.square")
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
        .formStyle(.grouped)
    }

    private var passwordForm: some View {
        Form {
            Section {
                if let text = snapshot.passwordText {
                    Text(text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                BridgedTextField(
                    label: L10n.password,
                    text: snapshot.passwordValue,
                    textRevision: snapshot.passwordRevision,
                    secure: true,
                    send: { navigationModel.setImportPassword(instanceId: entry.instanceId, text: $0) },
                    style: .automatic
                )
                .onSubmit {
                    if snapshot.canConfirmPassword { invoke(ImportAction.passwordConfirm) }
                }
                if let error = snapshot.passwordError, !error.isEmpty {
                    // A glyph next to the message, so the error does not rely on color alone.
                    Label {
                        Text(error)
                    } icon: {
                        Image(systemName: "exclamationmark.circle")
                    }
                    .font(.caption)
                    .foregroundStyle(Color(platform: .platformDanger))
                }
            }
            if snapshot.canCancelPassword {
                Section {
                    Button(L10n.cancel) {
                        invoke(ImportAction.passwordCancel)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private var snapshotsForm: some View {
        Form {
            Section {
                Text(L10n.importBackupSnapshotsText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Section {
                ForEach(snapshot.snapshots, id: \.key) { row in
                    Button {
                        invoke(row.actionId)
                    } label: {
                        HStack(spacing: 12) {
                            DetailRowLabel(title: row.title, subtitle: row.text, disclosure: nil)
                            Spacer(minLength: 8)
                            Image(systemName: row.selected ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(row.selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                                .imageScale(.large)
                                .accessibilityHidden(true)
                        }
                        .touchTarget()
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(row.actionId == nil)
                    .accessibilityAddTraits(row.selected ? [.isSelected] : [])
                }
            }
            if snapshot.canCancelSnapshot {
                Section {
                    Button(L10n.cancel) {
                        invoke(ImportAction.snapshotsCancel)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private var reviewForm: some View {
        Form {
            if !snapshot.counts.isEmpty {
                Section {
                    countRows(snapshot.counts)
                }
            }
            if !snapshot.items.isEmpty {
                Section {
                    ForEach(snapshot.items, id: \.index) { item in
                        itemRow(item)
                    }
                }
            }
            skippedSection
        }
        .formStyle(.grouped)
    }

    private var emptyForm: some View {
        Form {
            Section {
                Text(snapshot.message ?? "")
                    .fixedSize(horizontal: false, vertical: true)
            }
            skippedSection
            closeSection
        }
        .formStyle(.grouped)
    }

    private var doneForm: some View {
        Form {
            Section {
                Label {
                    Text(L10n.importSuccessTitle)
                        .font(.headline)
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.tint)
                }
                if let message = snapshot.message {
                    Text(message)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                countRows(snapshot.counts)
            }
            closeSection
        }
        .formStyle(.grouped)
    }

    private var errorForm: some View {
        Form {
            Section {
                Label {
                    Text(snapshot.message ?? "")
                        .fixedSize(horizontal: false, vertical: true)
                } icon: {
                    Image(systemName: "exclamationmark.circle")
                        .foregroundStyle(Color(platform: .platformDanger))
                }
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Rows

    @ViewBuilder
    private var closeSection: some View {
        if snapshot.canClose {
            Section {
                Button(L10n.close) {
                    invoke(ImportAction.close)
                }
            }
        }
    }

    private func countRows(_ counts: [ImportCountSnapshot]) -> some View {
        ForEach(counts, id: \.kind) { count in
            Label {
                Text(count.text)
            } icon: {
                Image(systemName: Self.countSymbol(count.kind))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func itemRow(_ item: ImportItemSnapshot) -> some View {
        Button {
            navigationModel.setImportItemSelected(
                instanceId: entry.instanceId, index: item.index, selected: !item.selected)
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(item.title.isEmpty ? L10n.untitled : item.title)
                        .lineLimit(1)
                    if !item.credentials.isEmpty {
                        FlowLayout(spacing: 6) {
                            ForEach(item.credentials, id: \.self) { kind in
                                credentialBadge(kind)
                            }
                        }
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: item.selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(item.selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .imageScale(.large)
                    .accessibilityHidden(true)
            }
            .touchTarget()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!item.canToggle)
        .accessibilityAddTraits(item.selected ? [.isSelected] : [])
    }

    private func credentialBadge(_ kind: String) -> some View {
        let visuals = Self.credentialVisuals(kind)
        return HStack(spacing: 4) {
            Image(systemName: visuals.symbol)
                .accessibilityHidden(true)
            Text(visuals.title)
                .lineLimit(1)
        }
        .font(.caption)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(.quaternary, in: Capsule())
    }

    @ViewBuilder
    private var skippedSection: some View {
        if !snapshot.skipped.isEmpty {
            Section {
                Text(L10n.skippedItemsText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(snapshot.skipped, id: \.id) { note in
                    skippedNoteRow(note)
                    ForEach(Array(note.titles.enumerated()), id: \.offset) { _, title in
                        skippedTitleRow(text: title.text, count: Int(title.count))
                    }
                    if let remaining = note.remainingText {
                        skippedTitleRow(text: remaining, count: 1)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func skippedNoteRow(_ note: ImportSkippedNoteSnapshot) -> some View {
        let label = HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .accessibilityHidden(true)
            Text(note.text)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            if note.toggleActionId != nil {
                Image(systemName: note.expanded ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        if let actionId = note.toggleActionId {
            Button {
                invoke(actionId)
            } label: {
                label
                    .touchTarget()
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            label
        }
    }

    private func skippedTitleRow(text: String, count: Int) -> some View {
        HStack(spacing: 4) {
            Text(text)
            if count > 1 {
                Text("(\(count))")
                    .foregroundStyle(.tertiary)
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.leading, 28)
    }

    // MARK: - Actions

    private func invoke(_ actionId: String?) {
        guard let actionId else { return }
        navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: actionId)
    }

    private func presentFilePicker() {
        guard let request = snapshot.filePickerRequest else {
            // Answered; a restarted producer may reuse the id.
            presentedRequestId = nil
            return
        }
        guard presentedRequestId != request.requestId else { return }
        presentedRequestId = request.requestId
        let instanceId = entry.instanceId
        let navigationModel = navigationModel
        let picker = filePickerModel.session
        // Outside the view update: on macOS the open panel runs a modal loop.
        Task { @MainActor in
            picker.presentFilePicker(
                for: request,
                resolve: { requestId, uri, name, size, token in
                    navigationModel.resolveImportFilePicker(
                        instanceId: instanceId, requestId: requestId, uri: uri, name: name, size: size,
                        accessToken: token)
                },
                cancel: { requestId in
                    navigationModel.cancelImportFilePicker(instanceId: instanceId, requestId: requestId)
                }
            )
        }
    }

    // MARK: - Symbols

    private static func countSymbol(_ kind: String) -> String {
        switch kind {
        case "login": return "key"
        case "passkey": return "person.badge.key"
        case "otp": return "clock"
        case "card": return "creditcard"
        case "identity": return "person.text.rectangle"
        case "note": return "note.text"
        case "sshKey": return "terminal"
        case "gpgKey": return "key.horizontal"
        case "environment": return "terminal"
        case "folder": return "folder"
        default: return "doc"
        }
    }

    /// `kind` is a backup location kind, as in the backup wizard.
    private static func backupLocationVisuals(_ kind: String) -> (symbol: String, title: String) {
        switch kind {
        case "webdav": return ("network", L10n.prefItemAutomaticBackupsWebdavServerTitle)
        case "s3": return ("shippingbox", L10n.prefItemAutomaticBackupsS3Title)
        default: return ("folder", L10n.prefItemAutomaticBackupsLocalFolderTitle)
        }
    }

    /// `kind` is a `TransferItem.Kind` name.
    private static func credentialVisuals(_ kind: String) -> (symbol: String, title: String) {
        switch kind {
        case "Passkey": return ("person.badge.key", L10n.credentialExchangeExportCredentialPasskey)
        case "Password": return ("key", L10n.credentialExchangeExportCredentialPassword)
        case "Totp": return ("clock", L10n.credentialExchangeExportCredentialTotp)
        case "Card": return ("creditcard", L10n.credentialExchangeExportCredentialCard)
        case "Identity": return ("person.text.rectangle", L10n.credentialExchangeExportCredentialIdentity)
        case "Note": return ("note.text", L10n.credentialExchangeExportCredentialNote)
        case "Fields": return ("slider.horizontal.3", L10n.credentialExchangeExportCredentialFields)
        case "SshKey": return ("terminal", L10n.credentialExchangeExportCredentialSshKey)
        case "GpgKey": return ("key.horizontal", L10n.credentialExchangeExportCredentialGpgKey)
        case "Environment": return ("terminal", L10n.credentialExchangeExportCredentialEnvironment)
        default: return ("doc", kind)
        }
    }
}

/// Action ids handled by `ImportController`'s handler map.
private enum ImportAction {
    static let learnMore = "import:learnMore"
    static let passwordConfirm = "import:password:confirm"
    static let passwordCancel = "import:password:cancel"
    static let snapshotsConfirm = "import:snapshots:confirm"
    static let snapshotsCancel = "import:snapshots:cancel"
    static let confirm = "import:confirm"
    static let close = "import:close"
    static let retry = "import:retry"
}
