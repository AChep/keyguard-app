import SwiftUI
import KeyguardShared

/// The SSO step: pushed from the sign-in form, or the root of an SSO account's re-login.
struct BitwardenSsoView: View {
    @Environment(BitwardenLoginModel.self) private var loginModel
    @Environment(\.dismiss) private var dismiss

    @State private var browser = SsoBrowserSession()

    private var sso: SsoLoginSnapshot { loginModel.sso }

    var body: some View {
        @Bindable var loginModel = loginModel
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(sso.password != nil ? L10n.ssoUnlockNote : L10n.ssoBrowserNote)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                if let password = sso.password {
                    passwordField(password)
                        .onSubmit(unlock)
                    LoginPrimaryButton(
                        title: L10n.ssoUnlock, isLoading: sso.isLoading, enabled: sso.canUnlock, action: unlock
                    )
                    .keyboardShortcut(.defaultAction)
                    .padding(.top, 8)
                } else {
                    if sso.isLoading {
                        // Waiting for the browser sign-in.
                        ProgressView()
                            .controlSize(.small)
                            .frame(maxWidth: .infinity)
                    }
                    // A pending sign-in can be replaced, e.g. when its 2FA screen was closed.
                    if sso.canRetry {
                        LoginPrimaryButton(title: L10n.retry, isLoading: false, enabled: true) {
                            loginModel.retrySso()
                        }
                        .keyboardShortcut(.defaultAction)
                        .padding(.top, 8)
                    }
                }
                Button(L10n.cancel, role: .cancel) {
                    dismiss()
                }
                .buttonStyle(.bordered)
                .keyboardShortcut(.cancelAction)
            }
            .frame(maxWidth: 420, alignment: .leading)
            .padding(40)
            .frame(maxWidth: .infinity)
        }
        .background(
            SsoBrowserAnchor(session: browser)
                .frame(width: 0, height: 0)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        )
        .onChange(of: sso.browserId) { _, id in
            guard let id, let url = sso.browserUrl else {
                browser.cancel()
                return
            }
            browser.start(id: id, url: url) { callback, failed in
                loginModel.completeSso(browserId: id, callback: callback, failed: failed)
            }
        }
        .onDisappear { browser.cancel() }
        .navigationTitle(L10n.ssoLogin)
        .navigationDestination(isPresented: $loginModel.twofaActive) {
            BitwardenTwofaView()
        }
        // Pushed from the sign-in form, the form closes the sheet.
        .onChange(of: loginModel.loginDidSucceed) { _, succeeded in
            if succeeded && loginModel.startsWithSso { dismiss() }
        }
        .onAppear {
            if loginModel.loginDidSucceed {
                if loginModel.startsWithSso { dismiss() }
                return
            }
            if loginModel.startsWithSso { loginModel.startLoginObservation() }
            loginModel.startSsoObservation()
        }
    }

    private func passwordField(_ field: TextFieldSnapshot) -> some View {
        let base = BridgedTextField(
            label: L10n.password,
            text: field.text,
            textRevision: field.textRevision,
            secure: true,
            send: { loginModel.setSsoField(id: field.id, text: $0) },
            style: .roundedBorder,
            disablesAutocorrection: true
        )
        #if os(iOS)
        var input = base
        input.autocapitalization = .never
        input.contentType = .password
        #else
        let input = base
        #endif
        return LoginFieldRow(field: field) { input }
    }

    private func unlock() {
        guard sso.canUnlock else { return }
        loginModel.unlockSso()
    }
}
