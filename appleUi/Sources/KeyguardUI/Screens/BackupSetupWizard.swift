import SwiftUI
import KeyguardShared
import UniformTypeIdentifiers

struct BackupSetupWizard: View {
    @Environment(BackupSettingsModel.self) private var backupsModel
    @Environment(FilePickerModel.self) private var filePickerModel
    @Environment(\.dismiss) private var dismiss

    @State private var path: [BackupSetupStep] = []
    @State private var webDavURL: String
    @State private var webDavUsername: String
    @State private var webDavPassword = ""
    @State private var encryptionPassword = ""
    @State private var confirmationPassword = ""
    @State private var replacePassword = false
    @State private var hasEdits = false
    @State private var confirmingDiscard = false
    @State private var saveRequested = false
    @FocusState private var focusedField: Field?

    // Preserve the opening configuration across live snapshot re-renders.
    @State private var initialHasPassword: Bool
    @State private var initialWebDavURL: String
    @State private var initialWebDavUsername: String

    private enum Field: Hashable {
        case server, username, serverPassword, encryptionPassword, confirmationPassword
    }

    init(initial: BackupSettingsSnapshot) {
        _initialHasPassword = State(initialValue: initial.hasPassword)
        _initialWebDavURL = State(initialValue: initial.webDavUrl ?? "")
        _initialWebDavUsername = State(initialValue: initial.webDavUsername ?? "")
        _webDavURL = State(initialValue: initial.webDavUrl ?? "")
        _webDavUsername = State(initialValue: initial.webDavUsername ?? "")
    }

    private var s: BackupSettingsSnapshot { backupsModel.backupSettings }

    var body: some View {
        #if os(iOS)
        let pickerRequest = filePickerModel.pendingFilePicker.flatMap { $0.presentsInBackupSetup ? $0 : nil }
        #endif
        NavigationStack(path: $path) {
            page(.destination)
                .navigationDestination(for: BackupSetupStep.self) { step in page(step) }
                .toolbar {
                    if path.isEmpty {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L10n.cancel, action: cancel)
                                .disabled(s.isTestingLocation)
                        }
                    }
                }
        }
        .interactiveDismissDisabled(hasEdits || s.isTestingLocation)
        .confirmationDialog(L10n.prefItemAutomaticBackupsWizardDiscardTitle, isPresented: $confirmingDiscard) {
            Button(L10n.prefItemAutomaticBackupsWizardDiscardAction, role: .destructive) { dismiss() }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.prefItemAutomaticBackupsWizardDiscardMessage)
        }
        .onChange(of: s.setupSaveRevision) { old, new in
            if saveRequested && new > old { dismiss() }
        }
        .onChange(of: s.loaded) { _, loaded in
            if !loaded { dismiss() }
        }
        .onChange(of: webDavURL) { _, _ in hasEdits = true }
        .onChange(of: webDavUsername) { _, _ in hasEdits = true }
        .onChange(of: webDavPassword) { _, _ in hasEdits = true }
        .onChange(of: encryptionPassword) { _, _ in hasEdits = true }
        .onChange(of: confirmationPassword) { _, _ in hasEdits = true }
        .onChange(of: replacePassword) { _, _ in hasEdits = true }
        .onChange(of: s.setupLocalPath) { _, _ in hasEdits = true }
        #if os(iOS)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .fileImporter(
            isPresented: Binding(
                get: { filePickerModel.pendingFilePicker?.presentsInBackupSetup == true },
                set: { _ in }
            ),
            allowedContentTypes: pickerRequest?.allowedContentTypes ?? [.folder],
            allowsMultipleSelection: false
        ) { result in
            if let request = pickerRequest {
                filePickerModel.resolveFilePicker(result: result, requestId: request.requestId)
            }
        }
        #else
        .frame(minWidth: 480, idealWidth: 540, minHeight: 520, idealHeight: 660)
        #endif
        .appToastOverlay()
    }

    private func page(_ step: BackupSetupStep) -> some View {
        Form {
            Section {
                BackupWizardHeader(
                    title: step.title,
                    subtitle: step.subtitle,
                    systemImage: step.systemImage,
                    stepLabel: L10n.prefItemAutomaticBackupsWizardStepLabel(
                        step.number, BackupSetupStep.allCases.count),
                    step: step.number,
                    stepCount: BackupSetupStep.allCases.count
                )
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            switch step {
            case .destination: destinationSections
            case .protection: protectionSection
            case .contents: contentsSections
            case .review: reviewSection
            }
        }
        .formStyle(.grouped)
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(L10n.prefItemAutomaticBackupsWizardTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationBarBackButtonHidden(s.isTestingLocation)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                advance(from: step)
            } label: {
                HStack {
                    if s.isTestingLocation { ProgressView().controlSize(.small) }
                    Text(
                        s.isTestingLocation
                            ? L10n.prefItemAutomaticBackupsWizardVerifyingStatus
                            : step == .review
                                ? (s.enabled
                                    ? L10n.prefItemAutomaticBackupsSaveVerifyAction
                                    : L10n.prefItemAutomaticBackupsEnableButton)
                                : L10n.continue
                    )
                    .frame(maxWidth: .infinity)
                }
                .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!canAdvance(from: step) || s.isTestingLocation || !s.loaded)
            .keyboardShortcut(.defaultAction)
            .padding(16)
            .background(.bar)
        }
        .disabled(s.isTestingLocation)
    }

    @ViewBuilder
    private var destinationSections: some View {
        Section {
            destinationChoice(
                kind: "local", title: L10n.prefItemAutomaticBackupsLocalFolderTitle,
                detail: L10n.prefItemAutomaticBackupsWizardFolderDetail, symbol: "folder")
            destinationChoice(
                kind: "webdav", title: L10n.prefItemAutomaticBackupsWebdavServerTitle,
                detail: L10n.prefItemAutomaticBackupsWizardWebdavDetail, symbol: "network")
        }
        if s.setupStoreKind == "webdav" {
            Section {
                TextField(L10n.url, text: $webDavURL, prompt: Text(verbatim: "https://example.com/dav"))
                    .focused($focusedField, equals: .server)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .username }
                    #if os(iOS)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                TextField(L10n.username, text: $webDavUsername)
                    .focused($focusedField, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .serverPassword }
                    #if os(iOS)
                .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                SecureField(L10n.password, text: $webDavPassword)
                    .focused($focusedField, equals: .serverPassword)
                    .privacySensitive()
            } header: {
                Text(L10n.prefItemAutomaticBackupsWebdavServerTitle)
            } footer: {
                if !webDavURL.isEmpty && !BackupSetupValidation.isValidWebDAVURL(webDavURL) {
                    Label(L10n.prefItemAutomaticBackupsWizardInvalidUrlError, systemImage: "exclamationmark.circle")
                        .foregroundStyle(.red)
                } else if s.setupHasWebDavPassword && webDavURL == initialWebDavURL
                    && webDavUsername == initialWebDavUsername
                {
                    Text(L10n.prefItemAutomaticBackupsWebdavKeepPassword)
                }
            }
        } else {
            Section {
                if let folder = s.setupLocalPath, !folder.isEmpty {
                    BackupLocationLabel(isWebDav: false, location: folder)
                }
                Button(L10n.prefItemAutomaticBackupsChooseFolderAction) { backupsModel.pickBackupLocation() }
            } footer: {
                Text(L10n.prefItemAutomaticBackupsFolderAccessHelp)
            }
        }
    }

    private func destinationChoice(kind: String, title: String, detail: String, symbol: String) -> some View {
        Button {
            if s.setupStoreKind == "webdav" { applyWebDav() }
            backupsModel.setBackupStoreKind(kind)
            hasEdits = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 36)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: s.setupStoreKind == kind ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(s.setupStoreKind == kind ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(s.setupStoreKind == kind ? .isSelected : [])
    }

    private var protectionSection: some View {
        Section {
            if initialHasPassword {
                Label(L10n.prefItemAutomaticBackupsPasswordSetSummary, systemImage: "lock.shield")
                Toggle(L10n.prefItemAutomaticBackupsWizardReplacePasswordAction, isOn: $replacePassword)
            }
            if !initialHasPassword || replacePassword {
                SecureField(L10n.prefItemAutomaticBackupsPasswordOptionalLabel, text: $encryptionPassword)
                    .focused($focusedField, equals: .encryptionPassword)
                    .privacySensitive()
                    .submitLabel(.next)
                    .onSubmit { focusedField = .confirmationPassword }
                if !encryptionPassword.isEmpty {
                    SecureField(L10n.prefItemAutomaticBackupsWizardConfirmPasswordLabel, text: $confirmationPassword)
                        .focused($focusedField, equals: .confirmationPassword)
                        .privacySensitive()
                }
            }
        } footer: {
            if (!initialHasPassword || replacePassword) && encryptionPassword.isEmpty {
                Label(L10n.prefItemAutomaticBackupsPasswordNotSet, systemImage: "lock.open")
            } else if (!initialHasPassword || replacePassword) && !confirmationPassword.isEmpty
                && encryptionPassword != confirmationPassword
            {
                Label(L10n.prefItemAutomaticBackupsWizardPasswordMismatchError, systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
            } else {
                Text(L10n.prefItemAutomaticBackupsPasswordMessage)
            }
        }
    }

    @ViewBuilder
    private var contentsSections: some View {
        Section {
            Toggle(
                L10n.prefItemAutomaticBackupsIncludeAttachmentsTitle,
                isOn: Binding(
                    get: { s.setupIncludeAttachments },
                    set: {
                        backupsModel.setBackupIncludeAttachments($0); hasEdits = true
                    }
                ))
        }
        Section {
            Stepper(
                value: Binding(
                    get: { Int(s.setupRetentionMaxSnapshots) },
                    set: {
                        backupsModel.setBackupSetupRetention(Int32($0)); hasEdits = true
                    }
                ), in: 0...365
            ) {
                Text(retentionSummary)
            }
        } header: {
            Text(L10n.prefItemAutomaticBackupsRetentionLabel)
        } footer: {
            Text(L10n.prefItemAutomaticBackupsRetentionPruneNote)
        }
    }

    private var reviewSection: some View {
        Section {
            BackupLocationLabel(
                isWebDav: s.setupStoreKind == "webdav",
                location: s.setupStoreKind == "webdav" ? s.setupWebDavUrl : s.setupLocalPath)
            Label(
                hasEncryptionPassword
                    ? L10n.prefItemAutomaticBackupsPasswordSet : L10n.prefItemAutomaticBackupsPasswordNotSet,
                systemImage: hasEncryptionPassword ? "lock.shield" : "lock.open")
            LabeledContent(
                L10n.prefItemAutomaticBackupsIncludeAttachmentsTitle,
                value: s.setupIncludeAttachments
                    ? L10n.prefItemAutomaticBackupsIncludeAttachmentsEnabledSummary
                    : L10n.prefItemAutomaticBackupsIncludeAttachmentsDisabledSummary)
            LabeledContent(L10n.prefItemAutomaticBackupsRetentionLabel, value: retentionSummary)
            if let error = s.setupError, !error.isEmpty {
                Label(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }
        }
    }

    private var retentionSummary: String {
        s.setupRetentionMaxSnapshots == 0
            ? L10n.prefItemAutomaticBackupsRetentionKeepAll
            : L10n.prefItemAutomaticBackupsRetentionKeepSnapshotCount(Int(s.setupRetentionMaxSnapshots))
    }

    private var hasEncryptionPassword: Bool {
        (initialHasPassword && !replacePassword) || !encryptionPassword.isEmpty
    }

    private func canAdvance(from step: BackupSetupStep) -> Bool {
        switch step {
        case .destination:
            s.setupStoreKind == "webdav"
                ? BackupSetupValidation.isValidWebDAVURL(webDavURL) : !(s.setupLocalPath ?? "").isEmpty
        case .protection:
            (initialHasPassword && !replacePassword) || encryptionPassword.isEmpty
                || encryptionPassword == confirmationPassword
        case .contents, .review: true
        }
    }

    private func advance(from step: BackupSetupStep) {
        guard canAdvance(from: step), !s.isTestingLocation else { return }
        focusedField = nil
        switch step {
        case .destination:
            if s.setupStoreKind == "webdav" { applyWebDav() }
            path.append(.protection)
        case .protection:
            path.append(.contents)
        case .contents:
            path.append(.review)
        case .review:
            if initialHasPassword && !replacePassword {
                backupsModel.restoreBackupSetupPassword()
            } else {
                backupsModel.setBackupPassword(encryptionPassword)
            }
            saveRequested = true
            backupsModel.enableBackup()
        }
    }

    private func applyWebDav() {
        backupsModel.setBackupStoreWebDav(url: webDavURL, username: webDavUsername, password: webDavPassword)
    }

    private func cancel() {
        if hasEdits { confirmingDiscard = true } else { dismiss() }
    }
}
