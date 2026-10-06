import SwiftUI
import KeyguardShared

/// The S3 destination fields of the backup wizard.
struct BackupS3Fields: Equatable {
    var endpoint = ""
    var region = ""
    var bucket = ""
    var prefix = ""
    var accessKeyId = ""
    var secretAccessKey = ""
    var pathStyle = true
}

struct BackupSetupWizardContent: View {
    @Environment(\.dismiss) private var dismiss

    @State private var path: [BackupSetupStep] = []
    @State private var webDavURL: String
    @State private var webDavUsername: String
    @State private var webDavPassword = ""
    @State private var s3: BackupS3Fields
    @State private var encryptionPassword = ""
    @State private var confirmationPassword = ""
    @State private var replacePassword = false
    @State private var hasEdits = false
    @State private var confirmingDiscard = false
    @FocusState private var focusedField: Field?

    // Preserve the opening configuration across live snapshot re-renders.
    @State private var initialHasPassword: Bool
    @State private var initialWebDavURL: String
    @State private var initialWebDavUsername: String

    private enum Field: Hashable {
        case server, username, serverPassword, encryptionPassword, confirmationPassword
        case s3Endpoint, s3Region, s3Bucket, s3Prefix, s3AccessKeyId, s3Secret
    }

    let s: BackupSetupSnapshot
    let setStoreKind: (String) -> Void
    let setWebDav: (String, String, String) -> Void
    let setS3: (BackupS3Fields) -> Void
    let setPassword: (String) -> Void
    let restorePassword: () -> Void
    let setIncludeAttachments: (Bool) -> Void
    let setRetention: (Int32) -> Void
    let submit: () -> Void
    let pickLocation: () -> Void
    let isValidWebDavURL: (String) -> Bool
    /// Returns the `S3FormError` name of the first invalid field.
    let s3ErrorKind: (BackupS3Fields) -> String?
    /// Whether an empty secret access key keeps the saved key of the fields' account.
    let keepsS3Secret: (BackupS3Fields) -> Bool

    init(
        s: BackupSetupSnapshot,
        setStoreKind: @escaping (String) -> Void,
        setWebDav: @escaping (String, String, String) -> Void,
        setS3: @escaping (BackupS3Fields) -> Void,
        setPassword: @escaping (String) -> Void,
        restorePassword: @escaping () -> Void,
        setIncludeAttachments: @escaping (Bool) -> Void,
        setRetention: @escaping (Int32) -> Void,
        submit: @escaping () -> Void,
        pickLocation: @escaping () -> Void,
        isValidWebDavURL: @escaping (String) -> Bool,
        s3ErrorKind: @escaping (BackupS3Fields) -> String?,
        keepsS3Secret: @escaping (BackupS3Fields) -> Bool
    ) {
        self.s = s
        self.setStoreKind = setStoreKind
        self.setWebDav = setWebDav
        self.setS3 = setS3
        self.setPassword = setPassword
        self.restorePassword = restorePassword
        self.setIncludeAttachments = setIncludeAttachments
        self.setRetention = setRetention
        self.submit = submit
        self.pickLocation = pickLocation
        self.isValidWebDavURL = isValidWebDavURL
        self.s3ErrorKind = s3ErrorKind
        self.keepsS3Secret = keepsS3Secret
        _s3 = State(
            initialValue: BackupS3Fields(
                endpoint: s.s3Endpoint ?? "",
                region: s.s3Region ?? "",
                bucket: s.s3Bucket ?? "",
                prefix: s.s3Prefix ?? "",
                accessKeyId: s.s3AccessKeyId ?? "",
                pathStyle: s.s3PathStyle
            ))
        _initialHasPassword = State(initialValue: s.hasPassword)
        _initialWebDavURL = State(initialValue: s.webDavUrl ?? "")
        _initialWebDavUsername = State(initialValue: s.webDavUsername ?? "")
        _webDavURL = State(initialValue: s.webDavUrl ?? "")
        _webDavUsername = State(initialValue: s.webDavUsername ?? "")
    }

    var body: some View {
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
        .onChange(of: webDavURL) { _, _ in hasEdits = true }
        .onChange(of: webDavUsername) { _, _ in hasEdits = true }
        .onChange(of: webDavPassword) { _, _ in hasEdits = true }
        .onChange(of: s3) { _, _ in hasEdits = true }
        .onChange(of: encryptionPassword) { _, _ in hasEdits = true }
        .onChange(of: confirmationPassword) { _, _ in hasEdits = true }
        .onChange(of: replacePassword) { _, _ in hasEdits = true }
        .onChange(of: s.localPath) { _, _ in hasEdits = true }
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
            destinationChoice(
                kind: "s3", title: L10n.prefItemAutomaticBackupsS3Title,
                detail: L10n.prefItemAutomaticBackupsWizardS3Detail, symbol: "shippingbox")
        }
        switch s.storeKind {
        case "webdav":
            Section {
                TextField(
                    L10n.url, text: $webDavURL,
                    prompt: Text(verbatim: KeyguardUrls.shared.PLACEHOLDER_WEBDAV_COLLECTION)
                )
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
                if !webDavURL.isEmpty && !isValidWebDavURL(webDavURL) {
                    Label(L10n.prefItemAutomaticBackupsWizardInvalidUrlError, systemImage: "exclamationmark.circle")
                        .foregroundStyle(.red)
                } else if s.hasWebDavPassword && webDavURL == initialWebDavURL
                    && webDavUsername == initialWebDavUsername
                {
                    Text(L10n.prefItemAutomaticBackupsWebdavKeepPassword)
                }
            }
        case "s3":
            s3Sections
        default:
            Section {
                if let folder = s.localPath, !folder.isEmpty {
                    BackupLocationLabel(kind: "local", location: folder)
                }
                Button(L10n.prefItemAutomaticBackupsChooseFolderAction) { pickLocation() }
            } footer: {
                Text(L10n.prefItemAutomaticBackupsFolderAccessHelp)
            }
        }
    }

    @ViewBuilder
    private var s3Sections: some View {
        Section {
            s3Field(
                L10n.s3SettingsEndpointTitle, text: $s3.endpoint, field: .s3Endpoint, next: .s3Region,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_ENDPOINT, url: true)
            s3Field(
                L10n.s3SettingsRegionTitle, text: $s3.region, field: .s3Region, next: .s3Bucket,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_REGION)
            s3Field(
                L10n.s3SettingsBucketTitle, text: $s3.bucket, field: .s3Bucket, next: .s3Prefix,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_BUCKET)
            s3Field(
                L10n.s3SettingsPrefixTitle, text: $s3.prefix, field: .s3Prefix, next: .s3AccessKeyId,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_PREFIX)
        } header: {
            Text(L10n.prefItemAutomaticBackupsS3Title)
        } footer: {
            Text(L10n.s3SettingsEndpointNote)
        }
        Section {
            s3Field(L10n.s3SettingsAccessKeyIdTitle, text: $s3.accessKeyId, field: .s3AccessKeyId, next: .s3Secret)
            SecureField(L10n.s3SettingsSecretAccessKeyTitle, text: $s3.secretAccessKey)
                .focused($focusedField, equals: .s3Secret)
                .privacySensitive()
            Toggle(isOn: $s3.pathStyle) {
                Text(L10n.s3SettingsPathStyleTitle)
                Text(L10n.s3SettingsPathStyleText)
            }
        } footer: {
            if !s3.bucket.isEmpty, let error = s3ErrorMessage(currentS3ErrorKind) {
                Label(error, systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
            } else if keepsSavedS3Secret {
                Text(L10n.prefItemAutomaticBackupsS3KeepSecret)
            } else {
                Text(L10n.s3SettingsAuthNote)
            }
        }
    }

    private func s3Field(
        _ title: String,
        text: Binding<String>,
        field: Field,
        next: Field,
        prompt: String? = nil,
        url: Bool = false
    ) -> some View {
        TextField(title, text: text, prompt: prompt.map { Text(verbatim: $0) })
            .focused($focusedField, equals: field)
            .submitLabel(.next)
            .onSubmit { focusedField = next }
            #if os(iOS)
        .keyboardType(url ? .URL : .asciiCapable)
        .textInputAutocapitalization(.never)
            #endif
            .autocorrectionDisabled()
    }

    /// The saved secret is kept while the endpoint, bucket and access key are unchanged.
    private var keepsSavedS3Secret: Bool {
        s3.secretAccessKey.isEmpty && keepsS3Secret(s3)
    }

    private var currentS3ErrorKind: String? {
        s3ErrorKind(s3)
    }

    private var reviewLocation: String? {
        BackupLocationLabel.location(
            kind: s.storeKind, localPath: s.localPath, webDavUrl: s.webDavUrl, s3Location: s.s3Location,
            s3EndpointHost: s.s3EndpointHost)
    }

    private func destinationChoice(kind: String, title: String, detail: String, symbol: String) -> some View {
        Button {
            applyRemoteFields()
            setStoreKind(kind)
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
                Image(systemName: s.storeKind == kind ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(s.storeKind == kind ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(s.storeKind == kind ? .isSelected : [])
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
                    get: { s.includeAttachments },
                    set: {
                        setIncludeAttachments($0); hasEdits = true
                    }
                ))
        }
        Section {
            Stepper(
                value: Binding(
                    get: { Int(s.retentionMaxSnapshots) },
                    set: {
                        setRetention(Int32($0)); hasEdits = true
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
            BackupLocationLabel(kind: s.storeKind, location: reviewLocation)
            Label(
                hasEncryptionPassword
                    ? L10n.prefItemAutomaticBackupsPasswordSet : L10n.prefItemAutomaticBackupsPasswordNotSet,
                systemImage: hasEncryptionPassword ? "lock.shield" : "lock.open")
            LabeledContent(
                L10n.prefItemAutomaticBackupsIncludeAttachmentsTitle,
                value: s.includeAttachments
                    ? L10n.prefItemAutomaticBackupsIncludeAttachmentsEnabledSummary
                    : L10n.prefItemAutomaticBackupsIncludeAttachmentsDisabledSummary)
            LabeledContent(L10n.prefItemAutomaticBackupsRetentionLabel, value: retentionSummary)
            if let error = s.error, !error.isEmpty {
                Label(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }
        }
    }

    private var retentionSummary: String {
        s.retentionMaxSnapshots == 0
            ? L10n.prefItemAutomaticBackupsRetentionKeepAll
            : L10n.prefItemAutomaticBackupsRetentionKeepSnapshotCount(Int(s.retentionMaxSnapshots))
    }

    private var hasEncryptionPassword: Bool {
        (initialHasPassword && !replacePassword) || !encryptionPassword.isEmpty
    }

    private func canAdvance(from step: BackupSetupStep) -> Bool {
        switch step {
        case .destination:
            switch s.storeKind {
            case "webdav": isValidWebDavURL(webDavURL)
            case "s3": currentS3ErrorKind == nil
            default: !(s.localPath ?? "").isEmpty
            }
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
            applyRemoteFields()
            path.append(.protection)
        case .protection:
            path.append(.contents)
        case .contents:
            path.append(.review)
        case .review:
            if initialHasPassword && !replacePassword {
                restorePassword()
            } else {
                setPassword(encryptionPassword)
            }
            submit()
        }
    }

    private func applyWebDav() {
        setWebDav(webDavURL, webDavUsername, webDavPassword)
    }

    /// Pushes the typed fields of the selected remote destination into the native draft.
    private func applyRemoteFields() {
        switch s.storeKind {
        case "webdav": applyWebDav()
        case "s3": setS3(s3)
        default: break
        }
    }

    private func cancel() {
        if hasEdits { confirmingDiscard = true } else { dismiss() }
    }
}
