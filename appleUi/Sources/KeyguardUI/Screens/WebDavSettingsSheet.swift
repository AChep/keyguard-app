import SwiftUI
import KeyguardShared

struct WebDavSettingsSheet: View {
    @Environment(KeePassLoginModel.self) private var keepassModel

    @State private var url: String = ""
    @State private var username: String = ""
    @State private var password: String = ""

    private var webdav: WebDavSettingsSnapshot? { keepassModel.keepassWebDav }

    var body: some View {
        ModalSheet(
            title: L10n.prefItemAutomaticBackupsWebdavServerTitle,
            width: 460,
            height: 400,
            // Full-height on iOS: URL/username/password fields plus the keyboard
            // cramp the .medium detent (detents are a no-op on macOS).
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.url, text: $url, prompt: Text("https://example.com/dav/vault.kdbx"))
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                        #endif
                        .onChange(of: url) { _, text in
                            keepassModel.setWebDavField(id: "url", text: text)
                        }
                    TextField(L10n.username, text: $username)
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                        #endif
                        .onChange(of: username) { _, text in
                            keepassModel.setWebDavField(id: "username", text: text)
                        }
                    SecureField(L10n.password, text: $password)
                        .onChange(of: password) { _, text in
                            keepassModel.setWebDavField(id: "password", text: text)
                        }
                } footer: {
                    Text(L10n.webdavSettingsAuthNote)
                }
                #if os(iOS)
                Section {
                    testConnectionButton
                }
                #endif
                if let error = errorMessage {
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
                keepassModel.submitWebDavSettings()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty || webdav?.isTestingConnection == true)
        }
        .onAppear {
            url = webdav?.url ?? ""
            username = webdav?.username ?? ""
            password = webdav?.password ?? ""
        }
        // Connection tests publish both failures and success on the message bus;
        // the presenting account sheet's overlay is behind this nested modal.
        .appToastOverlay()
    }

    private var testConnectionButton: some View {
        Button {
            updateFields()
            keepassModel.testWebDavConnection()
        } label: {
            if webdav?.isTestingConnection == true {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(L10n.webdavSettingsTestTitle)
            }
        }
        .accessibilityLabel(L10n.webdavSettingsTestTitle)
        .disabled(webdav?.isTestingConnection == true)
    }

    private var errorMessage: String? {
        switch webdav?.errorKind {
        case "UrlRequired":
            return L10n.errorWebdavUrlRequired
        case "InvalidUrl":
            return L10n.errorInvalidUrl
        case "FileUrlRequired":
            return L10n.errorWebdavFileUrlRequired
        case "PasswordRequiresUsername":
            return L10n.webdavSettingsPasswordRequiresUsernameError
        default:
            return nil
        }
    }

    private func updateFields() {
        // Return/click can arrive before the last text-field onChange callback.
        keepassModel.setWebDavField(id: "url", text: url)
        keepassModel.setWebDavField(id: "username", text: username)
        keepassModel.setWebDavField(id: "password", text: password)
    }
}
