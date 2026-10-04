import SwiftUI
import KeyguardShared

struct WebDavSettingsSheet: View {
    let sessionId: String
    @Environment(KeePassLoginModel.self) private var keepassModel

    @State private var url: String = ""
    @State private var username: String = ""
    @State private var password: String = ""

    private var webdav: WebDavSettingsSnapshot? { keepassModel.keepassWebDav.flatMap { $0.id == sessionId ? $0 : nil } }

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
                    TextField(
                        L10n.url, text: $url,
                        prompt: Text(verbatim: KeyguardUrls.shared.PLACEHOLDER_WEBDAV_KEEPASS_DATABASE)
                    )
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    #endif
                    .onChange(of: url) { _, text in
                        keepassModel.setWebDavField(sessionId: sessionId, id: "url", text: text)
                    }
                    TextField(L10n.username, text: $username)
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                        #endif
                        .onChange(of: username) { _, text in
                            keepassModel.setWebDavField(sessionId: sessionId, id: "username", text: text)
                        }
                    SecureField(L10n.password, text: $password)
                        .onChange(of: password) { _, text in
                            keepassModel.setWebDavField(sessionId: sessionId, id: "password", text: text)
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
                keepassModel.submitWebDavSettings(sessionId: sessionId)
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
        ConnectionTestButton(isTesting: webdav?.isTestingConnection == true) {
            updateFields()
            keepassModel.testWebDavConnection(sessionId: sessionId)
        }
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
        keepassModel.setWebDavField(sessionId: sessionId, id: "url", text: url)
        keepassModel.setWebDavField(sessionId: sessionId, id: "username", text: username)
        keepassModel.setWebDavField(sessionId: sessionId, id: "password", text: password)
    }
}
