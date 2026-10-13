import SwiftUI
import KeyguardShared

struct BitwardenLoginView: View {
    @Environment(BitwardenLoginModel.self) private var loginModel
    @Environment(\.dismiss) private var dismiss

    private var login: LoginSnapshot { loginModel.login }

    /// The form hosts only its own 2FA; SSO's 2FA is pushed from the SSO screen.
    private var loginTwofaActive: Binding<Bool> {
        Binding(
            get: { loginModel.twofaActive && !loginModel.ssoActive },
            set: { loginModel.twofaActive = $0 }
        )
    }

    var body: some View {
        @Bindable var loginModel = loginModel
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L10n.addaccountLoginIntroText)
                    .font(.callout)
                    .foregroundStyle(.secondary)

                regionPicker

                field(login.email, title: L10n.email, secure: false)
                field(login.password, title: L10n.password, secure: true)

                Button {
                    loginModel.startSso()
                } label: {
                    Label(L10n.ssoLogin, systemImage: "building.2")
                }
                .buttonStyle(.borderless)
                .disabled(!login.canSso)

                if let discovery = login.serverDiscovery {
                    Button {
                        loginModel.discoverLoginServer()
                    } label: {
                        HStack(spacing: 8) {
                            if discovery.isLoading {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "magnifyingglass")
                            }
                            Text(L10n.addaccountServerDiscoveryButton)
                        }
                    }
                    .buttonStyle(.borderless)
                    .disabled(!discovery.enabled)
                }

                if let clientSecret = login.clientSecret {
                    clientSecretSection(clientSecret)
                }

                if login.showCustomEnv {
                    customEnvironmentEditor
                }

                if login.canRegister {
                    Button {
                        loginModel.clickLoginRegister()
                    } label: {
                        Label(L10n.addaccountCreateAnAccountTitle, systemImage: "safari")
                    }
                    .buttonStyle(.bordered)
                }

                loginButton
                    .padding(.top, 8)
            }
            .frame(maxWidth: 420, alignment: .leading)
            .padding(40)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(L10n.addaccountMethodHeaderTitle)
        .navigationDestination(isPresented: $loginModel.ssoActive) {
            BitwardenSsoView()
        }
        // The SSO step keeps running under its 2FA screen, so it
        // stops only once its own screen is popped.
        .onChange(of: loginModel.ssoActive) { _, active in
            if !active { loginModel.stopSsoObservation() }
        }
        .navigationDestination(isPresented: loginTwofaActive) {
            BitwardenTwofaView()
        }
        .onChange(of: loginModel.loginDidSucceed) { _, succeeded in
            if succeeded { dismiss() }
        }
        .onAppear {
            if loginModel.loginDidSucceed { dismiss() } else { loginModel.startLoginObservation() }
        }
        .sheet(
            isPresented: Binding(
                get: { loginModel.dialogs?.confirmation != nil },
                set: { if !$0 { loginModel.dialogs?.closeConfirmation() } }
            )
        ) {
            ConfirmationDialogView().environment(loginModel.dialogs)
        }
    }

    // MARK: - Region

    private var regionSelection: Binding<String> {
        Binding(
            get: { login.regions.first(where: { $0.checked })?.key ?? "" },
            set: { loginModel.selectLoginRegion(key: $0) }
        )
    }

    @ViewBuilder
    private var regionPicker: some View {
        if !login.regions.isEmpty {
            let picker = Picker(L10n.addaccountRegionSection, selection: regionSelection) {
                ForEach(login.regions, id: \.key) { region in
                    Text(region.title).tag(region.key)
                }
            }
            if login.regions.count <= 3 {
                picker
                    .pickerStyle(.segmented)
                    .labelsHidden()
            } else {
                picker
                    .pickerStyle(.menu)
                    .labelsHidden()
            }
        }
    }

    // MARK: - Fields

    private func field(_ field: TextFieldSnapshot, title: String, secure: Bool) -> some View {
        LoginFieldRow(field: field) {
            loginTextField(field, title: title, secure: secure)
        }
    }

    private func loginTextField(
        _ field: TextFieldSnapshot,
        title: String,
        secure: Bool = false
    ) -> BridgedTextField {
        let input = BridgedTextField(
            label: title,
            text: field.text,
            textRevision: field.textRevision,
            secure: secure,
            send: { loginModel.setLoginField(id: field.id, text: $0) },
            style: .roundedBorder,
            disablesAutocorrection: true
        )
        #if os(iOS)
        // Credentials, endpoint URLs and HTTP headers must preserve exact input.
        var configuredInput = input
        configuredInput.autocapitalization = .never
        if field.id == login.email.id {
            configuredInput.keyboard = .emailAddress
            configuredInput.contentType = .username
        } else if field.id == login.password.id {
            configuredInput.contentType = .password
        }
        return configuredInput
        #else
        return input
        #endif
    }

    @ViewBuilder
    private func clientSecretSection(_ clientSecret: TextFieldSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.addaccountLoginCaptchaClientSecretNote)
                .font(.footnote)
                .foregroundStyle(.secondary)
            field(clientSecret, title: L10n.addaccountCaptchaNeedClientSecretLabel, secure: true)
        }
    }

    // MARK: - Custom self-hosted environment editor

    @ViewBuilder
    private var customEnvironmentEditor: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            ForEach(login.items, id: \.id) { item in
                itemView(item)
            }
        }
    }

    @ViewBuilder
    private func itemView(_ item: LoginItemSnapshot) -> some View {
        if item.kind == LoginItemKind.url {
            if let field = item.field {
                self.field(field, title: item.label ?? L10n.url, secure: false)
            }
        } else if item.kind == LoginItemKind.header {
            headerRow(item)
        } else if item.kind == LoginItemKind.section {
            if let text = item.text, !text.isEmpty {
                Text(text)
                    .font(.headline)
                    .padding(.top, 8)
            }
        } else if item.kind == LoginItemKind.label {
            if let text = item.text {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } else if item.kind == LoginItemKind.add {
            addButton(item)
        }
    }

    @ViewBuilder
    private func headerRow(_ item: LoginItemSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                if let keyField = item.keyField {
                    loginTextField(keyField, title: L10n.addaccountHttpHeaderKeyLabel)
                        .id(keyField.id)
                }
                if let valueField = item.field {
                    loginTextField(valueField, title: L10n.addaccountHttpHeaderValueLabel)
                        .id(valueField.id)
                }
                if !item.actions.isEmpty {
                    Menu {
                        ForEach(item.actions, id: \.id) { action in
                            Button(action.title) { loginModel.invokeLoginAction(id: action.id) }
                        }
                    } label: {
                        Label(L10n.moreActions, systemImage: "ellipsis.circle")
                            .touchTarget()
                    }
                    .labelStyle(.iconOnly)
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }
            }
            if let error = item.field?.error {
                errorText(error)
            }
        }
    }

    @ViewBuilder
    private func addButton(_ item: LoginItemSnapshot) -> some View {
        let title = item.text ?? L10n.add
        if item.actions.count == 1 {
            Button {
                loginModel.invokeLoginAction(id: item.actions[0].id)
            } label: {
                Label(title, systemImage: "plus")
            }
            .buttonStyle(.bordered)
        } else if item.actions.count > 1 {
            Menu {
                ForEach(item.actions, id: \.id) { action in
                    Button(action.title) { loginModel.invokeLoginAction(id: action.id) }
                }
            } label: {
                Label(title, systemImage: "plus")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }

    // MARK: - Login

    private var loginButton: some View {
        LoginPrimaryButton(
            title: L10n.addaccountSignInButton, isLoading: login.isLoading, enabled: login.canLogin, action: submit)
    }

    private func submit() {
        guard login.canLogin else { return }
        loginModel.submitLogin()
    }

    @ViewBuilder
    private func errorText(_ message: String) -> some View {
        Text(message)
            .font(.footnote)
            .foregroundStyle(.red)
    }
}

/// A sign-in field with its error.
struct LoginFieldRow<Input: View>: View {
    let field: TextFieldSnapshot
    @ViewBuilder let input: Input

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            input
                // The per-field local buffer must reset when this row is
                // reused for a different field.
                .id(field.id)
                // Locked by the producer: a re-login's email / server, or while
                // the login runs.
                .disabled(!field.editable)
            if let error = field.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }
}

/// The full-width primary action of a sign-in step.
struct LoginPrimaryButton: View {
    let title: String
    let isLoading: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
                    .frame(maxWidth: .infinity)
            } else {
                Text(title)
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityLabel(title)
        .disabled(!enabled)
    }
}
