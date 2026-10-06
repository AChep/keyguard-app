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

    private var s3: S3SettingsSnapshot? { keepassModel.keepassS3.flatMap { $0.id == sessionId ? $0 : nil } }

    var body: some View {
        ModalSheet(
            title: L10n.s3SettingsHeaderTitle,
            width: 460,
            height: 600,
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
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
                }
                Section {
                    field(L10n.s3SettingsAccessKeyIdTitle, text: $accessKeyId, id: "accessKeyId")
                    SecureField(L10n.s3SettingsSecretAccessKeyTitle, text: $secretAccessKey)
                        .privacySensitive()
                        .onChange(of: secretAccessKey) { _, text in
                            keepassModel.setS3Field(sessionId: sessionId, id: "secretAccessKey", text: text)
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
                if let error = s3ErrorMessage(s3?.errorKind) {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
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
        TextField(title, text: text, prompt: prompt.map { Text(verbatim: $0) })
            #if os(iOS)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .keyboardType(url ? .URL : .asciiCapable)
            #endif
            .onChange(of: text.wrappedValue) { _, value in
                keepassModel.setS3Field(sessionId: sessionId, id: id, text: value)
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
