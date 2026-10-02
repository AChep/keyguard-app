import SwiftUI
import KeyguardShared

struct MasterPasswordContent: View {
    let mode: MasterPasswordView.Mode
    let snapshot: MasterPasswordSnapshot
    let actions: MasterPasswordActions

    @Environment(VaultSessionModel.self) private var authModel
    @State private var password: String = ""
    @State private var pendingSubmission: String?
    #if os(iOS)
    /// The toolbar's recovery action awaiting confirmation.
    @State private var pendingAction: UnlockActionSnapshot?
    #endif
    @FocusState private var passwordFocused: Bool

    private var canSubmit: Bool {
        snapshot.canSubmit && snapshot.password == password
    }

    private var isBusy: Bool { snapshot.isLoading }

    private var errorText: String? { snapshot.passwordError }

    private var hasBiometric: Bool {
        mode == .create ? snapshot.hasBiometric : authModel.unlockHasBiometric
    }

    private var biometricBinding: Binding<Bool> {
        Binding(get: { snapshot.biometricEnabled }, set: actions.setBiometric)
    }

    private var crashlyticsBinding: Binding<Bool> {
        Binding(get: { snapshot.crashlyticsEnabled }, set: actions.setCrashlytics)
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "lock.shield")
                        .font(.system(size: 48))
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)

                    VStack(spacing: 6) {
                        Text(mode.title)
                            .font(.title2.weight(.semibold))
                        Text(mode.subtitle)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        if mode == .unlock, let reason = authModel.unlockLockReason, !reason.isEmpty {
                            Text(reason)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                    }

                    VStack(spacing: 12) {
                        masterPasswordField

                        if mode == .create {
                            if snapshot.hasBiometric {
                                Toggle(
                                    AppleBiometry.current.unlockTitle(bundle: AppLocalization.shared.bundle),
                                    isOn: biometricBinding
                                )
                                .disabled(isBusy)
                            }
                            Toggle(L10n.setupButtonSendCrashReports, isOn: crashlyticsBinding)
                                .disabled(isBusy)
                        }
                    }
                    .frame(maxWidth: 320)

                    if let error = errorText {
                        // Lead the inline validation error with a danger SF Symbol so it
                        // is not communicated by color alone (color-blind / increased
                        // contrast), and use the system-adaptive danger color.
                        Label {
                            Text(error)
                        } icon: {
                            Image(systemName: "exclamationmark.circle.fill")
                        }
                        .font(.footnote)
                        .foregroundStyle(Color(platform: .platformDanger))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 320)
                    }

                    #if os(macOS)
                    if mode == .unlock {
                        if isBusy {
                            ProgressView()
                                .controlSize(.large)
                        }
                    } else {
                        submitButton
                    }
                    #else
                    submitButton
                    #endif

                    if mode == .unlock, hasBiometric {
                        Button {
                            authModel.triggerUnlockBiometric()
                        } label: {
                            Label(
                                AppleBiometry.current.unlockTitle(bundle: AppLocalization.shared.bundle),
                                systemImage: AppleBiometry.current.symbol
                            )
                            .frame(maxWidth: 320)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .disabled(isBusy)
                    }

                    if mode == .unlock, authModel.unlockHasFido2 {
                        Button(L10n.fido2UnlockTitle, systemImage: "key") { authModel.triggerUnlockFido2() }
                            .disabled(isBusy)
                    }
                    if mode == .unlock, authModel.unlockHasYubiKey {
                        Button {
                            authModel.triggerUnlockYubiKey()
                        } label: {
                            Label(L10n.unlockYubikeyTitle, systemImage: "key.fill")
                                .frame(maxWidth: 320)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .disabled(isBusy)
                    }

                }
                .padding(40)
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background {
            VisualEffectBackground()
                .ignoresSafeArea()
        }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if mode == .unlock, !authModel.unlockActions.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu(L10n.moreActions, systemImage: "ellipsis") {
                        ForEach(authModel.unlockActions, id: \.id) { action in
                            Button(action.title, role: .destructive) {
                                pendingAction = action
                            }
                            .disabled(isBusy)
                        }
                    }
                    .labelStyle(.iconOnly)
                    .disabled(isBusy)
                    .help(L10n.moreActions)
                    .unlockActionConfirmation($pendingAction)
                }
            }
        }
        #endif
        .onAppear {
            switch mode {
            case .unlock: authModel.setUnlockScreenVisible(true)
            case .create: break
            }
            // The macOS unlock screen has no Unlock button, so focus the field to
            // keep Return-to-submit working (macOS doesn't auto-focus reliably).
            #if os(macOS)
            if mode == .unlock { passwordFocused = true }
            #endif
        }
        .onDisappear {
            pendingSubmission = nil
            password = ""
            switch mode {
            case .unlock: authModel.setUnlockScreenVisible(false)
            case .create: break
            }
        }
        .onChange(of: canSubmit) { _, enabled in
            if enabled, pendingSubmission == password {
                submit()
            }
        }
    }

    @ViewBuilder
    private var masterPasswordField: some View {
        let field = SecureField(L10n.setupFieldAppPasswordLabel, text: $password)
            .textFieldStyle(.roundedBorder)
            .focused($passwordFocused)
            .onSubmit(submit)
            .onChange(of: password) { _, newValue in
                if pendingSubmission != newValue { pendingSubmission = nil }
                actions.setPassword(newValue)
            }
        #if os(iOS)
        field.textContentType(mode == .create ? .newPassword : .password)
        #else
        field
        #endif
    }

    @ViewBuilder
    private var submitButton: some View {
        Button(action: submit) {
            if isBusy {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(mode.actionTitle)
                    .frame(maxWidth: 320)
            }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .keyboardShortcut(.defaultAction)
        .accessibilityLabel(mode.actionTitle)
        .disabled(!canSubmit)
    }

    private func submit() {
        guard !isBusy, !password.isEmpty else { return }
        // Flush before consulting validation: a rapid Return can arrive before
        // either the last onChange or the first enabled shared snapshot.
        actions.setPassword(password)
        guard canSubmit else {
            pendingSubmission = password
            return
        }
        pendingSubmission = nil
        actions.submit()
    }
}
