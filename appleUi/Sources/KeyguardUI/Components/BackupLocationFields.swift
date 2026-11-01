import SwiftUI
import KeyguardShared

/// The S3 fields of a backup location.
struct BackupS3Fields: Equatable {
    var endpoint = ""
    var region = ""
    var bucket = ""
    var prefix = ""
    var accessKeyId = ""
    var secretAccessKey = ""
    var pathStyle = true
}

/// A field of a backup location. The screen owns the focus, so that it can clear it, e.g.
/// before it moves on.
enum BackupLocationField: Hashable {
    case server, username, serverPassword
    case s3Endpoint, s3Region, s3Bucket, s3Prefix, s3AccessKeyId, s3Secret
}

/// The WebDAV server of a backup location in the backup wizard.
struct BackupWebDavSection: View {
    @Binding var url: String
    @Binding var username: String
    @Binding var password: String
    @FocusState.Binding var focusedField: BackupLocationField?
    let isValidURL: (String) -> Bool
    /// An empty password keeps the saved one; the footer says so.
    var keepsPassword = false

    var body: some View {
        Section {
            TextField(
                L10n.url, text: $url,
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
            TextField(L10n.username, text: $username)
                .focused($focusedField, equals: .username)
                .submitLabel(.next)
                .onSubmit { focusedField = .serverPassword }
                #if os(iOS)
            .textInputAutocapitalization(.never)
                #endif
                .autocorrectionDisabled()
            SecureField(L10n.password, text: $password)
                .focused($focusedField, equals: .serverPassword)
                .privacySensitive()
        } header: {
            Text(L10n.prefItemAutomaticBackupsWebdavServerTitle)
        } footer: {
            if !url.isEmpty && !isValidURL(url) {
                Label(L10n.prefItemAutomaticBackupsWizardInvalidUrlError, systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
            } else if keepsPassword {
                Text(L10n.prefItemAutomaticBackupsWebdavKeepPassword)
            }
        }
    }
}

/// The S3 bucket and credentials of a backup location in the backup wizard.
struct BackupS3Sections: View {
    @Binding var fields: BackupS3Fields
    @FocusState.Binding var focusedField: BackupLocationField?
    /// The `S3FormError` name of the first invalid field.
    let errorKind: String?
    /// An empty secret access key keeps the saved one; the footer says so.
    var keepsSecret = false

    var body: some View {
        Section {
            field(
                L10n.s3SettingsEndpointTitle, text: $fields.endpoint, field: .s3Endpoint, next: .s3Region,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_ENDPOINT, url: true)
            field(
                L10n.s3SettingsRegionTitle, text: $fields.region, field: .s3Region, next: .s3Bucket,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_REGION)
            field(
                L10n.s3SettingsBucketTitle, text: $fields.bucket, field: .s3Bucket, next: .s3Prefix,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_BUCKET)
            field(
                L10n.s3SettingsPrefixTitle, text: $fields.prefix, field: .s3Prefix, next: .s3AccessKeyId,
                prompt: KeyguardUrls.shared.PLACEHOLDER_S3_PREFIX)
        } header: {
            Text(L10n.prefItemAutomaticBackupsS3Title)
        } footer: {
            Text(L10n.s3SettingsEndpointNote)
        }
        Section {
            field(L10n.s3SettingsAccessKeyIdTitle, text: $fields.accessKeyId, field: .s3AccessKeyId, next: .s3Secret)
            SecureField(L10n.s3SettingsSecretAccessKeyTitle, text: $fields.secretAccessKey)
                .focused($focusedField, equals: .s3Secret)
                .privacySensitive()
            Toggle(isOn: $fields.pathStyle) {
                Text(L10n.s3SettingsPathStyleTitle)
                Text(L10n.s3SettingsPathStyleText)
            }
        } footer: {
            if !fields.bucket.isEmpty, let error = s3ErrorMessage(errorKind) {
                Label(error, systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
            } else if keepsSecret {
                Text(L10n.prefItemAutomaticBackupsS3KeepSecret)
            } else {
                Text(L10n.s3SettingsAuthNote)
            }
        }
    }

    private func field(
        _ title: String,
        text: Binding<String>,
        field: BackupLocationField,
        next: BackupLocationField,
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
}
