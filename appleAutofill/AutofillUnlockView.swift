import Foundation
import SwiftUI
import AuthenticationServices
import KeyguardShared

/// In-extension unlock screen, hosted by `CredentialProviderViewController` when a
/// credential is selected but the App-Group vault is locked. Shared by the macOS and
/// iOS extensions. Mirrors the main app's `MasterPasswordView(.unlock)` and is driven
/// by the **extension's** own `KeyguardCore` via the same shared unlock producer
/// (`observeUnlock` + `setUnlockPassword` / `submitUnlock` / `triggerUnlockBiometric`).
/// Biometrics reuse the keychain-backed cipher (shared keychain group,
/// provisioning-gated).
struct AutofillUnlockView: View {
    @State private var model: AutofillUnlockModel
    @State private var password: String = ""
    @State private var pendingSubmission: String?
    @ScaledMetric(relativeTo: .largeTitle) private var lockIconSize: CGFloat = 48

    #if os(macOS)
    /// The macOS panel is pointer-and-keyboard driven, so the field takes focus on
    /// entry. On iOS that would raise the keyboard over the content unprompted.
    @FocusState private var fieldFocused: Bool
    #endif

    init(model: AutofillUnlockModel) {
        _model = State(initialValue: model)
    }

    private var canSubmit: Bool {
        model.canSubmit && model.acknowledgedPassword == password
    }

    var body: some View {
        GeometryReader { geometry in
            // Keep one password field mounted when the keyboard changes the
            // viewport; swapping between scrollable and fixed forms loses focus.
            ScrollView {
                if model.needsSetup {
                    AutofillErrorView(message: L("autofill_setup_required"), onCancel: model.cancel)
                        .frame(minHeight: geometry.size.height)
                } else {
                    unlockForm.frame(minHeight: geometry.size.height)
                }
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            model.start()
            #if os(macOS)
            fieldFocused = true
            #endif
        }
        .onDisappear {
            pendingSubmission = nil
            password = ""
            model.stop()
        }
        .onChange(of: canSubmit) { _, enabled in
            if enabled, pendingSubmission == password {
                submit()
            }
        }
        .onChange(of: password) { _, newValue in
            if pendingSubmission != newValue { pendingSubmission = nil }
            model.setPassword(newValue)
        }
    }

    private var unlockForm: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield")
                .font(.system(size: lockIconSize))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)

            VStack(spacing: 6) {
                Text(L("unlock_vault_title"))
                    .font(.title2.weight(.semibold))
                Text(L("autofill_unlock_subtitle"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            secureField

            if let error = model.passwordError ?? model.messageError, !error.isEmpty {
                Label {
                    Text(error)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .labelStyle(.titleAndIcon)
                .font(.footnote)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
            }

            Button(action: submit) {
                if model.isLoading {
                    ProgressView().controlSize(.small)
                } else {
                    Text(L("unlock_button_unlock")).frame(maxWidth: 320)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityLabel(L("unlock_button_unlock"))
            #if os(macOS)
            .keyboardShortcut(.defaultAction)
            #endif
            .disabled(!canSubmit || model.isLoading)

            if model.hasBiometric {
                Button {
                    model.triggerBiometric()
                } label: {
                    biometricLabel.frame(maxWidth: 320)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .disabled(model.isLoading)
            }

            Button(action: model.cancel) {
                Text(L("cancel"))
                    #if os(iOS)
                .frame(minWidth: 44, minHeight: 44)
                    #endif
            }
            .buttonStyle(.borderless)
            #if os(macOS)
            .keyboardShortcut(.cancelAction)
            #endif
        }
        .padding(40)
        .frame(maxWidth: .infinity)
    }

    /// The master-password field. `textContentType` / `submitLabel` are iOS-only
    /// modifiers (the macOS content-type enum is a different type), so the field is
    /// built per platform rather than gating each modifier in a chain.
    @ViewBuilder
    private var secureField: some View {
        #if os(macOS)
        SecureField(L("password"), text: $password)
            .textFieldStyle(.roundedBorder)
            .frame(maxWidth: 320)
            .focused($fieldFocused)
            .onSubmit(submit)
        #else
        SecureField(L("password"), text: $password)
            .textFieldStyle(.roundedBorder)
            .textContentType(.password)
            .submitLabel(.go)
            .frame(maxWidth: 320)
            .onSubmit(submit)
        #endif
    }

    private var biometricLabel: some View {
        let biometry = AppleBiometry.current
        return Label(biometry.unlockTitle(bundle: .main), systemImage: biometry.symbol)
    }

    private func submit() {
        guard !model.isLoading, !password.isEmpty else { return }
        model.setPassword(password)
        guard canSubmit else {
            pendingSubmission = password
            return
        }
        pendingSubmission = nil
        model.submit()
    }
}

/// Thin observable wrapper over the extension's `KeyguardCore` unlock subset — the
/// same bridge calls `AppViewModel` uses, scoped to what the appex needs. On the vault
/// reaching the unlocked state it invokes `onUnlocked`; the view controller then
/// resolves the picked credential and completes the request.
@MainActor
@Observable
final class AutofillUnlockModel {
    private let core: KeyguardCore
    private let onUnlocked: () -> Void
    private let onCancel: () -> Void

    @ObservationIgnored private var statusSubscription: KeyguardCancellable?
    @ObservationIgnored private var unlockSubscription: KeyguardCancellable?
    @ObservationIgnored private var messagesSubscription: KeyguardCancellable?
    @ObservationIgnored private var observationID: UUID?
    @ObservationIgnored private var didUnlock = false

    var needsSetup = false
    var passwordError: String?
    /// Runtime error forwarded from the shared producer (e.g. a wrong master password)
    /// via the global message bus — the extension has no toast host, so it is shown
    /// inline beneath the field like `passwordError`.
    var messageError: String?
    /// The password associated with the producer's current submit callback.
    /// Validation snapshots can trail the native field by a few keystrokes.
    var acknowledgedPassword = ""
    var canSubmit = false
    var isLoading = false
    var hasBiometric = false

    init(core: KeyguardCore, onUnlocked: @escaping () -> Void, onCancel: @escaping () -> Void) {
        self.core = core
        self.onUnlocked = onUnlocked
        self.onCancel = onCancel
    }

    func start() {
        guard observationID == nil else { return }
        let observationID = UUID()
        self.observationID = observationID
        didUnlock = false
        statusSubscription = core.observeStatus { [weak self] status in
            Task { @MainActor [weak self] in
                guard let self, self.observationID == observationID, !self.didUnlock else { return }
                self.needsSetup = status == KeyguardVaultStatus.needsCreate
                if status == KeyguardVaultStatus.unlocked {
                    self.didUnlock = true
                    self.onUnlocked()
                }
            }
        }
        unlockSubscription = core.observeUnlock { [weak self] snapshot in
            Task { @MainActor [weak self] in
                guard let self, self.observationID == observationID else { return }
                self.acknowledgedPassword = snapshot.password
                self.passwordError = snapshot.passwordError
                self.canSubmit = snapshot.canUnlock
                self.isLoading = snapshot.isLoading
                self.hasBiometric = snapshot.hasBiometric
            }
        }
        messagesSubscription = core.observeMessages { [weak self] snapshot in
            Task { @MainActor [weak self] in
                guard let self, self.observationID == observationID, snapshot.isError else { return }
                if let text = snapshot.text, !text.isEmpty {
                    self.messageError = text
                } else {
                    self.messageError = snapshot.title
                }
            }
        }
        core.setUnlockScreenVisible(visible: true)
    }

    func stop() {
        guard observationID != nil else { return }
        observationID = nil
        core.setUnlockScreenVisible(visible: false)
        statusSubscription?.cancel()
        statusSubscription = nil
        unlockSubscription?.cancel()
        unlockSubscription = nil
        messagesSubscription?.cancel()
        messagesSubscription = nil
        core.setUnlockPassword(text: "")
        passwordError = nil
        messageError = nil
        acknowledgedPassword = ""
        canSubmit = false
        isLoading = false
    }

    func setPassword(_ text: String) {
        // A stale error (e.g. a wrong password) clears as the user edits.
        messageError = nil
        core.setUnlockPassword(text: text)
    }
    func submit() { core.submitUnlock() }
    func triggerBiometric() { core.triggerUnlockBiometric() }
    func cancel() { onCancel() }
}
