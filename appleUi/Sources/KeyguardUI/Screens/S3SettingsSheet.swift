import SwiftUI
import KeyguardShared

struct S3SettingsSheet: View {
    let sessionId: String
    @Environment(KeePassLoginModel.self) private var keepassModel

    @State private var endpoint: String = ""
    @State private var region: String = ""
    @State private var bucket: String = ""
    @State private var key: String = ""
    @State private var accessKeyId: String = ""
    @State private var secretAccessKey: String = ""
    @State private var pathStyle: Bool = true
    @FocusState private var focusedField: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var s3: S3SettingsSnapshot? { keepassModel.keepassS3.flatMap { $0.id == sessionId ? $0 : nil } }

    var body: some View {
        ModalSheet(
            title: L10n.s3SettingsHeaderTitle,
            width: 460,
            height: 600,
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            ScrollViewReader { scroll in
                Form {
                    Section {
                        field(
                            L10n.s3SettingsEndpointTitle, text: $endpoint, id: "endpoint",
                            prompt: KeyguardUrls.shared.PLACEHOLDER_S3_ENDPOINT, url: true)
                    } footer: {
                        Text(L10n.s3SettingsEndpointNote)
                    }
                    Section {
                        field(
                            L10n.s3SettingsRegionTitle, text: $region, id: "region",
                            prompt: KeyguardUrls.shared.PLACEHOLDER_S3_REGION)
                        field(
                            L10n.s3SettingsBucketTitle, text: $bucket, id: "bucket",
                            prompt: KeyguardUrls.shared.PLACEHOLDER_S3_BUCKET)
                        field(
                            L10n.s3SettingsKeyTitle, text: $key, id: "key",
                            prompt: KeyguardUrls.shared.PLACEHOLDER_S3_KEEPASS_KEY)
                    } footer: {
                        Text(L10n.s3SettingsKeepassRequirements)
                    }
                    Section {
                        field(L10n.s3SettingsAccessKeyIdTitle, text: $accessKeyId, id: "accessKeyId")
                        fieldRow(L10n.s3SettingsSecretAccessKeyTitle, id: "secretAccessKey") {
                            SecureField(L10n.s3SettingsSecretAccessKeyTitle, text: $secretAccessKey)
                                .privacySensitive()
                                .accessibilityIdentifier("s3.secretAccessKey")
                                .accessibilityLabel(L10n.s3SettingsSecretAccessKeyTitle)
                                #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                                #endif
                                .focused($focusedField, equals: "secretAccessKey")
                                .accessibilityHint(errorMessage(for: "secretAccessKey") ?? "")
                                .onChange(of: secretAccessKey) { _, text in
                                    keepassModel.setS3Field(sessionId: sessionId, id: "secretAccessKey", text: text)
                                }
                        }
                    } footer: {
                        Text(L10n.s3SettingsAuthNote)
                    }
                    Section {
                        Toggle(isOn: $pathStyle) {
                            Text(L10n.s3SettingsPathStyleTitle)
                            Text(L10n.s3SettingsPathStyleText)
                        }
                        .onChange(of: pathStyle) { _, value in
                            keepassModel.setS3PathStyle(sessionId: sessionId, value: value)
                        }
                    }
                    #if os(iOS)
                    Section {
                        testConnectionButton
                    }
                    #endif
                }
                .formStyle(.grouped)
                .onChange(of: focusedField) { previous, _ in
                    if let previous {
                        updateFields()
                        keepassModel.blurS3Field(sessionId: sessionId, id: previous)
                    }
                }
                .onChange(of: s3?.validationRequest) { _, _ in
                    guard let id = s3?.validationField else { return }
                    focusedField = id
                    withAnimation(reduceMotion ? nil : .default) {
                        scroll.scrollTo(id, anchor: .center)
                    }
                    if let error = errorMessage(for: id) {
                        AccessibilityNotification.Announcement("\(fieldTitle(id)). \(error)").post()
                    }
                }
            }
        } actions: {
            #if os(macOS)
            testConnectionButton
            #endif

            Button(L10n.save) {
                updateFields()
                keepassModel.submitS3Settings(sessionId: sessionId)
            }
            .keyboardShortcut(.defaultAction)
            .disabled(s3?.isTestingConnection == true)
        }
        .onAppear {
            endpoint = s3?.endpoint ?? ""
            region = s3?.region ?? ""
            bucket = s3?.bucket ?? ""
            key = s3?.key ?? ""
            accessKeyId = s3?.accessKeyId ?? ""
            secretAccessKey = s3?.secretAccessKey ?? ""
            pathStyle = s3?.pathStyle ?? true
        }
        // Connection tests publish both failures and success on the message bus;
        // the presenting account sheet's overlay is behind this nested modal.
        .appToastOverlay()
    }

    private func field(
        _ title: String,
        text: Binding<String>,
        id: String,
        prompt: String? = nil,
        url: Bool = false
    ) -> some View {
        fieldRow(title, id: id) {
            TextField(title, text: text, prompt: prompt.map { Text(verbatim: $0) })
                #if os(iOS)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(url ? .URL : .asciiCapable)
                #endif
                .accessibilityIdentifier("s3.\(id)")
                .accessibilityLabel(title)
                .focused($focusedField, equals: id)
                .accessibilityHint(errorMessage(for: id) ?? "")
                .onChange(of: text.wrappedValue) { _, value in
                    keepassModel.setS3Field(sessionId: sessionId, id: id, text: value)
                }
        }
    }

    private func fieldRow<Content: View>(_ title: String, id: String, @ViewBuilder content: () -> Content) -> some View
    {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline).foregroundStyle(.secondary).accessibilityHidden(true)
            content()
            if let error = errorMessage(for: id) {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .id(id)
    }

    private func errorMessage(for id: String) -> String? {
        s3ErrorMessage(s3?.fieldErrors.first { $0.id == id }?.kind)
    }

    private func fieldTitle(_ id: String) -> String {
        switch id {
        case "endpoint": L10n.s3SettingsEndpointTitle
        case "bucket": L10n.s3SettingsBucketTitle
        case "key": L10n.s3SettingsKeyTitle
        case "accessKeyId": L10n.s3SettingsAccessKeyIdTitle
        case "secretAccessKey": L10n.s3SettingsSecretAccessKeyTitle
        default: ""
        }
    }

    private var testConnectionButton: some View {
        ConnectionTestButton(isTesting: s3?.isTestingConnection == true) {
            updateFields()
            keepassModel.testS3Connection(sessionId: sessionId)
        }
    }

    private func updateFields() {
        // Return/click can arrive before the last text-field onChange callback.
        keepassModel.setS3Field(sessionId: sessionId, id: "endpoint", text: endpoint)
        keepassModel.setS3Field(sessionId: sessionId, id: "region", text: region)
        keepassModel.setS3Field(sessionId: sessionId, id: "bucket", text: bucket)
        keepassModel.setS3Field(sessionId: sessionId, id: "key", text: key)
        keepassModel.setS3Field(sessionId: sessionId, id: "accessKeyId", text: accessKeyId)
        keepassModel.setS3Field(sessionId: sessionId, id: "secretAccessKey", text: secretAccessKey)
        keepassModel.setS3PathStyle(sessionId: sessionId, value: pathStyle)
    }
}
