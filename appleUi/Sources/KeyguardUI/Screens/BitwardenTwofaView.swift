import SwiftUI
import KeyguardShared

struct BitwardenTwofaView: View {
    @Environment(BitwardenLoginModel.self) private var loginModel
    @Environment(\.dismiss) private var dismiss

    @State private var yubiKeyCode = ""

    private var twofa: TwofaSnapshot { loginModel.twofa }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                providerPicker
                content
            }
            .frame(maxWidth: 420, alignment: .leading)
            .padding(40)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(L10n.accountActionTfaTitle)
        .onChange(of: loginModel.twofaDidSucceed) { _, succeeded in
            if succeeded { dismiss() }
        }
        .observing(
            start: { loginModel.startTwofaObservation() },
            stop: { loginModel.stopTwofaObservation() }
        )
    }

    // MARK: - Provider selector

    private var providerSelection: Binding<String> {
        Binding(
            get: { twofa.providers.first(where: { $0.checked })?.key ?? "" },
            set: { loginModel.selectTwofaProvider(key: $0) }
        )
    }

    @ViewBuilder
    private var providerPicker: some View {
        if twofa.providers.count > 1 {
            let picker = Picker(L10n.addaccount2faMethodLabel, selection: providerSelection) {
                ForEach(twofa.providers, id: \.key) { provider in
                    Text(provider.title).tag(provider.key)
                }
            }
            if twofa.providers.count <= 3 {
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

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if twofa.kind == TwofaKind.skeleton {
            ProgressView()
                .controlSize(.small)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        } else if twofa.kind == TwofaKind.fallback {
            fallbackCard
        } else if twofa.kind == TwofaKind.fido2 {
            VStack(alignment: .leading, spacing: 12) {
                if twofa.rememberMeEnabled {
                    Toggle(L10n.rememberMe, isOn: rememberMeBinding)
                }
                continueButton
            }
        } else if twofa.kind == TwofaKind.yubikey {
            yubiKeyForm
        } else {
            // Authenticator / Email / Email-new-device — verification code entry.
            codeForm
        }
    }

    // MARK: - Code entry (authenticator / email)

    private var rememberMeBinding: Binding<Bool> {
        Binding(
            get: { twofa.rememberMe },
            set: { loginModel.toggleTwofaRememberMe(checked: $0) }
        )
    }

    @ViewBuilder
    private var codeForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let email = twofa.emailNote, !email.isEmpty {
                Text(L10n.addaccount2faEmailNote(email))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } else {
                Text(L10n.addaccount2faOtpNote)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 4) {
                codeField
                    // Each provider owns a different text handle whose revision
                    // may match the previous provider's revision.
                    .id(twofa.kind)
                    .font(.system(.body, design: .monospaced))
                    .onSubmit { loginModel.submitTwofa() }
                if let error = twofa.code?.error {
                    errorText(error)
                }
            }

            if twofa.canResend {
                Button {
                    loginModel.resendTwofaCode()
                } label: {
                    Label(L10n.resendVerificationCode, systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
            }

            if twofa.rememberMeEnabled {
                Toggle(L10n.rememberMe, isOn: rememberMeBinding)
            }

            continueButton
                .padding(.top, 8)
        }
    }

    /// `contentType`, `keyboard` and `autocapitalization` are iOS-only `BridgedTextField`
    /// parameters, so the macOS call omits them.
    @ViewBuilder
    private var codeField: some View {
        #if os(iOS)
        BridgedTextField(
            label: L10n.verificationCode,
            text: twofa.code?.text ?? "",
            textRevision: twofa.code?.textRevision ?? 0,
            secure: false,
            send: { loginModel.setTwofaCode(text: $0) },
            style: .roundedBorder,
            submitLabel: .go,
            contentType: .oneTimeCode,
            keyboard: .numberPad,
            autocapitalization: .never,
            disablesAutocorrection: true
        )
        #else
        BridgedTextField(
            label: L10n.verificationCode,
            text: twofa.code?.text ?? "",
            textRevision: twofa.code?.textRevision ?? 0,
            secure: false,
            send: { loginModel.setTwofaCode(text: $0) },
            style: .roundedBorder,
            submitLabel: .go,
            disablesAutocorrection: true
        )
        #endif
    }

    // MARK: - YubiKey (manual OTP entry)

    @ViewBuilder
    private var yubiKeyForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.addaccount2faYubikeyOtpNote)
                .font(.callout)
                .foregroundStyle(.secondary)

            TextField(L10n.verificationCode, text: $yubiKeyCode)
                .textFieldStyle(.roundedBorder)
                .font(.system(.body, design: .monospaced))
                .autocorrectionDisabled()
                #if os(iOS)
            .textInputAutocapitalization(.never)
                #endif
                .onSubmit { submitYubiKey() }

            if twofa.rememberMeEnabled {
                Toggle(L10n.rememberMe, isOn: rememberMeBinding)
            }

            Button(action: submitYubiKey) {
                if twofa.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .frame(maxWidth: .infinity)
                } else {
                    Text(L10n.continue)
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityLabel(L10n.continue)
            .disabled(!twofa.canSubmit || yubiKeyCode.isEmpty)
            .padding(.top, 8)
        }
    }

    private func submitYubiKey() {
        guard twofa.canSubmit, !yubiKeyCode.isEmpty else { return }
        loginModel.submitTwofaYubiKey(token: yubiKeyCode)
    }

    // MARK: - Fallback (Duo / FIDO2 on iOS)

    @ViewBuilder
    private var fallbackCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(
                L10n.addaccount2faMethodUnsupportedPlatformText(
                    twofa.fallbackTitle ?? L10n.addaccount2faMethodOtherTitle
                )
            )
            .font(.headline)
            Text(L10n.addaccount2faMethodWebVaultNote)
                .font(.callout)
                .foregroundStyle(.secondary)
            if twofa.webVaultUrl != nil {
                Button {
                    loginModel.openTwofaWebVault()
                } label: {
                    Label(L10n.launchWebVault, systemImage: "safari")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 4)
            }
        }
    }

    // MARK: - Shared

    private var continueButton: some View {
        Button(action: { loginModel.submitTwofa() }) {
            if twofa.isLoading {
                ProgressView()
                    .controlSize(.small)
                    .frame(maxWidth: .infinity)
            } else {
                Text(twofa.primaryActionText ?? L10n.continue)
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityLabel(twofa.primaryActionText ?? L10n.continue)
        .disabled(!twofa.canSubmit)
    }

    @ViewBuilder
    private func errorText(_ message: String) -> some View {
        Text(message)
            .font(.footnote)
            .foregroundStyle(.red)
    }
}
