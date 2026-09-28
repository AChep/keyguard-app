import SwiftUI
import KeyguardShared
#if os(iOS)
import AuthenticationServices
#endif
#if canImport(UIKit)
import UIKit
#endif

private func markdownText(_ string: String) -> Text {
    if let attributed = try? AttributedString(markdown: string) {
        return Text(attributed)
    }
    return Text(string)
}

/// Routes a settings category by stable id; unknown ids use a placeholder.
struct SettingsSubroute: View {
    let item: SettingsItemSnapshot

    var body: some View {
        switch item.id {
        case "subscription": SubscriptionsSettingsView(title: item.title)
        case "autofill": AutofillSettingsView(item: item)
        case "automatic_backups": AutomaticBackupsSettingsView(item: item)
        case "security": SecuritySettingsView(item: item)
        case "developer": DeveloperSettingsView(item: item)
        case "watchtower": WatchtowerSettingsView(item: item)
        case "notifications": NotificationsSettingsView(item: item)
        case "display": DisplaySettingsView(item: item)
        case "debug": DebugSettingsView(item: item)
        case "about": AboutSettingsView(item: item)
        default: SettingsPlaceholder(item: item)
        }
    }
}

// MARK: - General (macOS-only: launch-at-login + menu-bar mode)

#if os(macOS)
struct GeneralSettingsView: View {
    @Environment(LaunchAtLoginModel.self) private var launchAtLoginModel
    @AppStorage(DockIconMode.menuBarOnlyKey) private var menuBarOnly = false

    private var launch: LaunchAtLoginSnapshot { launchAtLoginModel.launchAtLogin }

    private var launchBinding: Binding<Bool> {
        Binding(
            get: { launch.enabled },
            set: { launchAtLoginModel.setLaunchAtLogin($0) }
        )
    }

    var body: some View {
        Form {
            Section(L10n.settingsStartupHeaderTitle) {
                Toggle(L10n.prefItemLaunchAtLoginTitle, isOn: launchBinding)
                    .disabled(!launch.available)
                if launch.requiresApproval {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.yellow)
                        Text(L10n.prefItemLaunchAtLoginApprovalNote)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Button(L10n.openAction) { launchAtLoginModel.openLoginItemsSettings() }
                            .buttonStyle(.link)
                    }
                } else if !launch.available {
                    Text(L10n.prefItemLaunchAtLoginUnavailableText)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            Section(L10n.settingsMenuBarHeaderTitle) {
                Toggle(L10n.prefItemMenuBarOnlyTitle, isOn: $menuBarOnly)
                Text(L10n.prefItemMenuBarOnlyText)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle(L10n.settingsGeneralHeaderTitle)
        .observing(
            start: { launchAtLoginModel.startLaunchAtLoginObservation() },
            stop: { launchAtLoginModel.stopLaunchAtLoginObservation() }
        )
    }
}
#endif

// MARK: - Per-category sub-route screens

struct AutofillSettingsView: View {
    @Environment(AutofillIndexService.self) private var autofillIndex
    let item: SettingsItemSnapshot

    private var indexStatus: String {
        switch autofillIndex.coordinator.state {
        case .updating: L10n.prefItemAutofillServiceUpdatingText
        case .current: L10n.prefItemAutofillServiceCurrentText
        case .failed: L10n.prefItemAutofillServiceIndexFailedText
        case .locked: L10n.prefItemAutofillServiceLockedText
        case .idle, .disabled: L10n.prefItemAutofillServiceDisabledText
        }
    }

    var body: some View {
        Form {
            Section {
                if autofillIndex.coordinator.isEnabled {
                    Label(L10n.prefItemAutofillServiceEnabledText, systemImage: "checkmark.circle")
                } else {
                    Button(L10n.prefItemAutofillServiceEnableAction) {
                        Task { await autofillIndex.enable() }
                    }
                    .disabled(autofillIndex.isEnabling)
                    Text(L10n.prefItemAutofillServiceDisabledText).foregroundStyle(.secondary)
                }
                Button(L10n.prefItemAutofillServiceOpenSettingsAction) { Task { await autofillIndex.openSettings() } }
                Text(indexStatus).foregroundStyle(.secondary)
                Button(L10n.prefItemAutofillServiceRefreshAction) { autofillIndex.refreshAutofillIdentities() }
                    .disabled(autofillIndex.coordinator.state == .updating)
            }
        }
        .onAppear { autofillIndex.refreshAutofillIdentities() }
        .formStyle(.grouped)
        .navigationTitle(item.title)

    }
}

struct SecuritySettingsView: View {
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(SecuritySettingsModel.self) private var securityModel
    let item: SettingsItemSnapshot

    @State private var changingPassword = false
    @State private var choosingYubiKeySlot = false
    /// Set to the chosen slot once we've probed it and found it already
    /// configured, which presents the "use existing vs overwrite" dialog.
    @State private var yubiKeyOverwriteSlot: Int?

    private var s: SecuritySettingsSnapshot { securityModel.securitySettings }

    private func boolBinding(
        _ get: @escaping @MainActor @Sendable () -> Bool,
        _ set: @escaping @MainActor @Sendable (Bool) -> Void
    ) -> Binding<Bool> {
        Binding(
            get: { MainActor.assumeIsolated { get() } },
            set: { value in MainActor.assumeIsolated { set(value) } }
        )
    }

    var body: some View {
        Form {
            // Biometric unlock — its own section so the explanation lands in the
            // native inset footnote rather than as a full-width gray row.
            if s.biometricUnlockSupported {
                Section {
                    Toggle(
                        AppleBiometry.current.unlockTitle(bundle: AppLocalization.shared.bundle),
                        isOn: boolBinding({ s.biometricUnlockEnabled }, { securityModel.setBiometricUnlock($0) }))
                    if s.biometricUnlockEnabled {
                        durationPicker(
                            L10n.prefItemBiometricUnlockTimeoutTitle,
                            options: s.biometricTimeoutOptions,
                            currentTitle: s.biometricTimeoutTitle,
                            set: { securityModel.setBiometricTimeout($0) })
                    }
                } header: {
                    Text(L10n.homeVaultLabel)
                } footer: {
                    Text(L10n.prefItemBiometricUnlockDescription)
                }
            }
            if s.fido2UnlockSupported {
                Section {
                    Toggle(
                        L10n.fido2UnlockTitle,
                        isOn: boolBinding({ s.fido2UnlockEnabled }, { securityModel.setFido2Unlock($0) }))
                } footer: {
                    Text(L10n.fido2UnlockDescription)
                }
            }
            if s.yubiKeyUnlockSupported {
                Section {
                    Toggle(
                        L10n.unlockYubikeyTitle,
                        isOn: boolBinding(
                            { s.yubiKeyUnlockEnabled },
                            { enabled in
                                if enabled {
                                    choosingYubiKeySlot = true
                                } else {
                                    securityModel.setYubiKeyUnlock(false)
                                }
                            }))
                } header: {
                    // The "Vault" header rides with the first visible section; only
                    // repeat it here when biometric unlock wasn't shown above.
                    if !s.biometricUnlockSupported { Text(L10n.homeVaultLabel) }
                } footer: {
                    Text(L10n.prefItemYubikeyUnlockNote)
                }
            }
            Section {
                Toggle(
                    L10n.prefItemVaultLockTimeoutNeverTitle,
                    isOn: boolBinding({ s.vaultPersist }, { securityModel.setVaultPersist($0) }))
                durationPicker(
                    L10n.prefItemVaultLockTimeoutTitle,
                    options: s.lockTimeoutOptions,
                    currentTitle: s.lockTimeoutTitle,
                    set: { securityModel.setVaultLockTimeout($0) })
                if s.lockAfterRebootVisible {
                    Toggle(
                        L10n.prefItemLockVaultAfterRebootText,
                        isOn: boolBinding({ s.lockAfterReboot }, { securityModel.setVaultLockAfterReboot($0) }))
                }
                Button(L10n.prefItemLockVaultTitle) { authModel.lockVault() }
            } header: {
                // Repeat the "Vault" header only if neither biometric nor YubiKey
                // section rendered it above.
                if !s.biometricUnlockSupported && !s.yubiKeyUnlockSupported { Text(L10n.homeVaultLabel) }
            } footer: {
                // The persist-vault security warning keeps its leading caution glyph
                // (color-plus-icon, not color alone) in the section footer.
                if s.vaultPersist {
                    Label(L10n.prefItemPersistVaultKeyNote, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.secondary)
                }
            }
            Section(L10n.settingsClipboardHeaderTitle) {
                durationPicker(
                    L10n.prefItemClipboardAutoClearTitle,
                    options: s.clipboardAutoClearOptions,
                    currentTitle: s.clipboardAutoClearTitle,
                    set: { securityModel.setClipboardAutoClear($0) })
            }
            Section {
                Toggle(
                    L10n.prefItemConcealFieldsTitle,
                    isOn: boolBinding({ s.conceal }, { securityModel.setConcealFields($0) }))
                Toggle(
                    L10n.prefItemLoadWebsiteIconsTitle,
                    isOn: boolBinding({ s.websiteIcons }, { securityModel.setWebsiteIcons($0) }))
                Toggle(
                    L10n.prefItemLoadGravatarIconsTitle,
                    isOn: boolBinding({ s.gravatar }, { securityModel.setGravatar($0) }))
            } header: {
                Text(L10n.settingsPrivacyHeaderTitle)
            }
            Section(L10n.password) {
                Button(L10n.prefItemChangeMasterPasswordAction) { changingPassword = true }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        .observing(
            start: { securityModel.startSecuritySettingsObservation() },
            stop: { securityModel.stopSecuritySettingsObservation() }
        )
        .sheet(isPresented: $changingPassword) {
            ChangePasswordSheet(isPresented: $changingPassword)
        }
        .confirmationDialog(
            L10n.yubikeySlotPickerTitle,
            isPresented: $choosingYubiKeySlot,
            titleVisibility: .visible
        ) {
            Button(L10n.yubikeySlot1Label) { beginYubiKeyEnroll(slot: 1) }
            Button(L10n.yubikeySlot2Label) { beginYubiKeyEnroll(slot: 2) }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.yubikeySlotPickerNote)
        }
        .confirmationDialog(
            L10n.yubikeySlotConfiguredWarning(yubiKeyOverwriteSlot ?? 0),
            isPresented: Binding(
                get: { yubiKeyOverwriteSlot != nil },
                set: { presented in if !presented { yubiKeyOverwriteSlot = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let slot = yubiKeyOverwriteSlot {
                Button(L10n.yubikeySlotUseExistingAction) {
                    securityModel.setYubiKeyUnlock(true, slot: slot, provision: false)
                }
                Button(L10n.yubikeySlotOverwriteAction, role: .destructive) {
                    securityModel.setYubiKeyUnlock(true, slot: slot, provision: true, overwrite: true)
                }
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.yubikeySlotOverwriteWarning)
        }
    }

    /// Probes the chosen slot: an empty slot is configured automatically; a slot
    /// that already holds a configuration prompts before overwriting.
    private func beginYubiKeyEnroll(slot: Int) {
        Task {
            let configured = await securityModel.inspectYubiKeySlot(slot)
            if configured == false {
                securityModel.setYubiKeyUnlock(true, slot: slot, provision: true)
            } else {
                // Already configured, or undeterminable — ask before touching it.
                yubiKeyOverwriteSlot = slot
            }
        }
    }

    /// A native `Picker` over the shared duration variants. The snapshot marks the
    /// selected option; the binding forwards the chosen opaque id to the bridge.
    /// Falls back to a static row until the options have loaded.
    @ViewBuilder
    private func durationPicker(
        _ title: String,
        options: [SettingOptionSnapshot],
        currentTitle: String,
        set: @escaping (String) -> Void
    ) -> some View {
        if options.isEmpty {
            HStack {
                Text(title)
                Spacer()
                Text(currentTitle).foregroundStyle(.secondary)
            }
        } else {
            Picker(
                title,
                selection: Binding(
                    get: { options.first(where: { $0.selected })?.id ?? options.first?.id ?? "" },
                    set: { set($0) }
                )
            ) {
                ForEach(options, id: \.id) { option in
                    Text(option.title).tag(option.id)
                }
            }
        }
    }
}

/// Modal change-master-password flow, driven by the shared change-password
/// producer running headless in the bridge. Dismisses itself when the producer
/// signals success (via the bridge `onClose` callback).
private struct ChangePasswordSheet: View {
    @Environment(ChangePasswordModel.self) private var changePasswordModel
    @Binding var isPresented: Bool
    @State private var currentPassword = ""
    @State private var newPassword = ""

    private var s: ChangePasswordSnapshot { changePasswordModel.changePassword }

    var body: some View {
        ModalSheet(
            title: L10n.changepasswordMasterPasswordTitle,
            width: 460,
            height: 420,
            // Full-height on iOS: two SecureFields plus the software keyboard cramp
            // the .medium detent (detents are a no-op on macOS).
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    SecureField(
                        L10n.currentPassword,
                        text: Binding(
                            get: { currentPassword },
                            set: {
                                currentPassword = $0
                                changePasswordModel.setChangePasswordCurrent($0)
                            }
                        ))
                } footer: {
                    if let error = s.currentError, !error.isEmpty {
                        Text(error).foregroundStyle(.red)
                    }
                }
                Section {
                    SecureField(
                        L10n.newPassword,
                        text: Binding(
                            get: { newPassword },
                            set: {
                                newPassword = $0
                                changePasswordModel.setChangePasswordNew($0)
                            }
                        ))
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if let error = s.newError, !error.isEmpty {
                            Text(error).foregroundStyle(.red)
                        }
                        Text(L10n.changepasswordDisclaimerLocalNote)
                        Text(L10n.changepasswordDisclaimerAbuseNote)
                    }
                }
                if s.biometricVisible {
                    Section {
                        Toggle(
                            L10n.changepasswordBiometricAuthCheckbox,
                            isOn: Binding(
                                get: { s.biometricChecked },
                                set: { changePasswordModel.setChangePasswordBiometric($0) }
                            ))
                    }
                }
            }
            .formStyle(.grouped)
            .disabled(!s.loaded || s.isLoading)
        } actions: {
            Button(L10n.change) {
                changePasswordModel.submitChangePassword()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(
                !s.canConfirm || s.isLoading || s.currentPassword != currentPassword || s.newPassword != newPassword
            )
        }
        .observing(
            start: { changePasswordModel.startChangePasswordObservation { isPresented = false } },
            stop: { changePasswordModel.stopChangePasswordObservation() }
        )
        .onChange(of: s.loaded, initial: true) { _, loaded in
            guard loaded else { return }
            // Seed once from the producer. Later snapshots echo earlier edits
            // and must not replace the local buffer while typing.
            currentPassword = s.currentPassword
            newPassword = s.newPassword
        }
        // Password validation failures arrive on the shared message bus. Keep
        // their feedback above the native sheet covering the root overlay.
        .appToastOverlay()
    }
}

/// Developer preferences: the SSH agent (mirroring the common SSH agent
/// settings items, driven by the shared Get/Put use cases surfaced as
/// `SshAgentModel.sshAgentSettings`) and the in-app logs.
struct DeveloperSettingsView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel
    let item: SettingsItemSnapshot

    private enum Dialog: String, Identifiable {
        case clientSetup, filters, history
        var id: String { rawValue }
    }

    @State private var dialog: Dialog?

    private var s: SshAgentSettingsSnapshot { sshAgentModel.sshAgentSettings }
    private var status: SshAgentStatusSnapshot { sshAgentModel.sshAgentStatus }

    var body: some View {
        Form {
            #if os(macOS)
            sshAgentSection
            approvalsSection
            keysSection
            GpgAgentSettingsSections()
            #else
            Section(L10n.sshAgent) {
                Label(L10n.agentBinaryUnavailableText, systemImage: "xmark.circle")
                    .foregroundStyle(.secondary)
            }
            #endif
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        #if os(macOS)
        .onAppear {
            sshAgentModel.startSshAgentSettingsObservation()
            sshAgentModel.startSshAgentObservation()
        }
        .onDisappear { sshAgentModel.stopSshAgentSettingsObservation() }
        .sheet(item: $dialog) { dialog in
            switch dialog {
            case .clientSetup:
                ModalSheet(title: L10n.sshAgentSetupHeaderTitle) {
                    SshAgentSetupView()
                }
            case .filters:
                SshAgentFiltersView()
            case .history:
                ModalSheet(title: L10n.sshAgentHistoryHeaderTitle) {
                    SshAgentHistoryView()
                }
            }
        }
        #endif
    }

    @ViewBuilder
    private var sshAgentSection: some View {
        Section {
            Toggle(
                L10n.prefItemSshAgentEnableTitle,
                isOn: Binding(
                    get: { s.enabled },
                    set: { sshAgentModel.setSshAgentEnabled($0) }
                )
            )
            // Allow switching OFF even when the binary is missing, so the
            // preference can't get stuck enabled on an unsupported build.
            .disabled(status.state == SshAgentRunState.unsupported && !s.enabled)
            statusRow
            if let sock = status.sshAuthSock, status.running {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L10n.sshAgentSetupClientSocketNote)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Text("export SSH_AUTH_SOCK=\(sock)")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                }
            }
            Button(L10n.prefItemSshAgentSetupTitle) { dialog = .clientSetup }
        } header: {
            Text(L10n.sshAgent)
        } footer: {
            Text(L10n.prefItemSshAgentDescription)
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        switch status.state {
        case SshAgentRunState.ready:
            Label(L10n.statusRunning, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case SshAgentRunState.starting:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(L10n.prefItemSshAgentStatusStarting).foregroundStyle(.secondary)
            }
        case SshAgentRunState.failed:
            Label(L10n.statusFailedToStart, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case SshAgentRunState.unsupported:
            Label(L10n.agentBinaryUnavailableText, systemImage: "xmark.circle")
                .foregroundStyle(.secondary)
        default:
            Label(L10n.prefItemSshAgentStatusStopped, systemImage: "stop.circle")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var approvalsSection: some View {
        Section {
            durationPicker(
                L10n.prefItemSshAgentApprovalWindowTitle,
                options: s.approvalWindowOptions,
                currentTitle: s.approvalWindowTitle,
                set: { sshAgentModel.setSshAgentApprovalWindow(optionId: $0) })
        } header: {
            Text(L10n.agentApprovalsHeaderTitle)
        } footer: {
            Text(L10n.prefItemSshAgentApprovalRememberNote)
        }
    }

    @ViewBuilder
    private var keysSection: some View {
        // Display-key-names toggle in its own section so its explanation renders as
        // the native inset footnote instead of a full-width gray row.
        Section {
            Toggle(
                L10n.prefItemSshAgentDisplayKeyNamesTitle,
                isOn: Binding(
                    get: { s.displayKeyNames },
                    set: { sshAgentModel.setSshAgentDisplayKeyNames($0) }
                ))
        } header: {
            Text(L10n.agentKeysHeaderTitle)
        } footer: {
            Text(L10n.prefItemSshAgentDisplayKeyNamesNote)
        }
        Section {
            Button {
                dialog = .filters
            } label: {
                HStack {
                    Label(L10n.prefItemSshAgentFiltersTitle, systemImage: "line.3.horizontal.decrease.circle")
                    Spacer()
                    Text(
                        s.filterActive
                            ? L10n.prefItemSshAgentFiltersSummaryActive : L10n.prefItemSshAgentFiltersSummaryAll
                    )
                    .font(.callout)
                    .foregroundStyle(.secondary)
                }
            }
            Button {
                dialog = .history
            } label: {
                Label(L10n.prefItemSshAgentHistoryTitle, systemImage: "clock.arrow.circlepath")
            }
        }
    }

    /// A native `Picker` over the shared duration variants. The snapshot marks the
    /// selected option; the binding forwards the chosen opaque id to the bridge.
    /// Falls back to a static row until the options have loaded.
    @ViewBuilder
    private func durationPicker(
        _ title: String,
        options: [SettingOptionSnapshot],
        currentTitle: String,
        set: @escaping (String) -> Void
    ) -> some View {
        if options.isEmpty {
            HStack {
                Text(title)
                Spacer()
                Text(currentTitle).foregroundStyle(.secondary)
            }
        } else {
            Picker(
                title,
                selection: Binding(
                    get: { options.first(where: { $0.selected })?.id ?? options.first?.id ?? "" },
                    set: { set($0) }
                )
            ) {
                ForEach(options, id: \.id) { option in
                    Text(option.title).tag(option.id)
                }
            }
        }
    }
}

/// Watchtower (breach detection) preferences. Mirrors the common
/// `WatchtowerSettingsScreen`, driven by the shared Get/Put use cases surfaced as
/// `WatchtowerModel.watchtowerSettings`.
struct WatchtowerSettingsView: View {
    @Environment(WatchtowerModel.self) private var watchtowerModel
    let item: SettingsItemSnapshot

    @State private var editingToken = false

    private var s: WatchtowerSettingsSnapshot { watchtowerModel.watchtowerSettings }

    private func boolBinding(
        _ get: @escaping @MainActor @Sendable () -> Bool,
        _ set: @escaping @MainActor @Sendable (Bool) -> Void
    ) -> Binding<Bool> {
        Binding(
            get: { MainActor.assumeIsolated { get() } },
            set: { value in MainActor.assumeIsolated { set(value) } }
        )
    }

    var body: some View {
        Form {
            Section {
                Toggle(
                    L10n.prefItemCheckPwnedPasswordsTitle,
                    isOn: boolBinding({ s.checkPwnedPasswords }, { watchtowerModel.setCheckPwnedPasswords($0) }))
                Toggle(
                    L10n.prefItemCheckPwnedServicesTitle,
                    isOn: boolBinding({ s.checkPwnedServices }, { watchtowerModel.setCheckPwnedServices($0) }))
                hibpTokenRow
            } header: {
                Text(L10n.prefItemHibpHeaderTitle)
            } footer: {
                markdownText(L10n.watchtowerHibpAttributionText)
            }
            Section {
                Toggle(
                    L10n.prefItemCheckInactive2faTitle,
                    isOn: boolBinding({ s.checkTwoFa }, { watchtowerModel.setCheckTwoFa($0) }))
            } header: {
                Text(L10n.tfaDirectoryTitle)
            } footer: {
                markdownText(L10n.watchtower2faDirectoryAttributionText)
            }
            Section {
                Toggle(
                    L10n.prefItemCheckInactivePasskeysTitle,
                    isOn: boolBinding({ s.checkPasskeys }, { watchtowerModel.setCheckPasskeys($0) }))
            } header: {
                Text(L10n.passkeysDirectoryTitle)
            } footer: {
                markdownText(L10n.watchtowerPasskeysDirectoryAttributionText)
            }
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        .observing(
            start: { watchtowerModel.startWatchtowerSettingsObservation() },
            stop: { watchtowerModel.stopWatchtowerSettingsObservation() }
        )
        .sheet(isPresented: $editingToken) {
            HibpTokenEditor(currentToken: s.hibpApiToken ?? "") { newToken in
                _ = watchtowerModel.setHibpApiToken(newToken)
            }
        }
    }

    @ViewBuilder
    private var hibpTokenRow: some View {
        Button {
            editingToken = true
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.apiKey)
                        .foregroundStyle(.primary)
                    if let token = s.hibpApiToken, !token.isEmpty {
                        HStack(spacing: 6) {
                            Text(Self.maskedToken(token))
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                            hibpStatusBadge
                        }
                    } else {
                        Text(L10n.prefItemHibpApiTokenNote)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                // The row opens a sheet (not a push), so a navigation chevron would
                // mislead (and chevron.right points the wrong way in RTL). A pencil
                // glyph signals an editable value and is direction-neutral.
                Image(systemName: "pencil")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.apiKey)
        .accessibilityHint(L10n.prefItemHibpApiTokenNote)
    }

    @ViewBuilder
    private var hibpStatusBadge: some View {
        switch s.hibpCheckState {
        case "checking":
            HStack(spacing: 4) {
                ProgressView().controlSize(.mini)
                Text(L10n.prefItemHibpApiTokenStatusChecking).font(.caption2)
            }
            .foregroundStyle(.secondary)
        case "verified":
            Label(L10n.prefItemHibpApiTokenStatusVerified, systemImage: "checkmark.circle.fill")
                .font(.caption2).labelStyle(.titleAndIcon).foregroundStyle(.green)
        case "rejected":
            Label(L10n.prefItemHibpApiTokenStatusRejected, systemImage: "xmark.circle.fill")
                .font(.caption2).labelStyle(.titleAndIcon).foregroundStyle(.red)
        case "failed":
            Label(L10n.prefItemHibpApiTokenCheckFailed, systemImage: "exclamationmark.triangle.fill")
                .font(.caption2).labelStyle(.titleAndIcon).foregroundStyle(.orange)
        default:
            EmptyView()
        }
    }

    private static func maskedToken(_ token: String) -> String {
        guard token.count > 4 else { return String(repeating: "•", count: token.count) }
        return token.prefix(4) + String(repeating: "•", count: max(0, token.count - 4))
    }
}

/// Modal token entry for the HIBP API key. Validates that the value is empty
/// (clears the key) or a 32-hex-character token before allowing save.
private struct HibpTokenEditor: View {
    let currentToken: String
    let onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var token: String = ""

    private static let pattern = try! NSRegularExpression(pattern: "^[0-9a-fA-F]{32}$")

    private var trimmed: String { token.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var isValid: Bool {
        if trimmed.isEmpty { return true }
        let range = NSRange(trimmed.startIndex..., in: trimmed)
        return Self.pattern.firstMatch(in: trimmed, range: range) != nil
    }

    var body: some View {
        ModalSheet(
            title: L10n.prefItemHibpApiTokenTitle,
            width: 460,
            height: 300,
            // Full-height on iOS so the text field clears the software keyboard; a
            // .medium-only detent can't expand to fit it (no-op on macOS).
            detents: [.large],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    TextField(L10n.prefItemHibpApiTokenPlaceholder, text: $token)
                        .font(.body.monospaced())
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                        #endif
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.prefItemHibpApiTokenInputNote)
                        if !trimmed.isEmpty && !isValid {
                            Label(
                                L10n.prefItemHibpApiTokenValidationError, systemImage: "exclamationmark.triangle"
                            )
                            .foregroundStyle(.red)
                        }
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(L10n.save) {
                onSave(trimmed)
                dismiss()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!isValid)
        }
        .onAppear { token = currentToken }
    }
}

struct NotificationsSettingsView: View {
    let item: SettingsItemSnapshot
    var body: some View { SettingsPlaceholder(item: item) }
}

struct DisplaySettingsView: View {
    @Environment(AppPreferencesModel.self) private var preferencesModel
    @Environment(SecuritySettingsModel.self) private var securityModel
    let item: SettingsItemSnapshot

    private var s: AppearanceSettingsSnapshot { preferencesModel.appearanceSettings }

    private func boolBinding(
        _ get: @escaping @MainActor @Sendable () -> Bool,
        _ set: @escaping @MainActor @Sendable (Bool) -> Void
    ) -> Binding<Bool> {
        Binding(
            get: { MainActor.assumeIsolated { get() } },
            set: { value in MainActor.assumeIsolated { set(value) } }
        )
    }

    var body: some View {
        Form {
            Section {
                optionPicker(L10n.prefItemLocaleTitle, options: s.localeOptions, currentTitle: s.localeTitle) {
                    preferencesModel.setLocale($0)
                }
            } header: {
                Text(L10n.prefItemLocaleTitle)
            } footer: {
                Text(L10n.prefItemAppearanceRestartNote)
            }
            Section(L10n.prefItemColorSchemeSelectionTitle) {
                optionPicker(L10n.prefItemColorSchemeTitle, options: s.themeOptions, currentTitle: s.themeTitle) {
                    preferencesModel.setTheme($0)
                }
                optionPicker(L10n.prefItemColorAccentTitle, options: s.accentOptions, currentTitle: s.accentTitle) {
                    preferencesModel.setColors($0)
                }
            }
            Section(L10n.settingsNavigationHeaderTitle) {
                // Per-item visibility / order / custom filter tabs; supersedes
                // the old "Hide the Send feature" toggle (PR #1434 parity).
                NavigationLink {
                    NavigationItemsSettingsView()
                } label: {
                    Label(L10n.settingsNavigationItemsHeaderTitle, systemImage: "list.bullet.rectangle")
                }
                Toggle(
                    L10n.prefItemNavLabelShortTitle,
                    isOn: boolBinding({ s.navLabel }, { preferencesModel.setNavLabel($0) }))
                Toggle(
                    L10n.prefItemRenderMarkdownTitle,
                    isOn: boolBinding({ s.markdown }, { preferencesModel.setMarkdown($0) }))
            }
            Section(L10n.settingsIconsHeaderTitle) {
                Toggle(
                    L10n.prefItemLoadWebsiteIconsTitle,
                    isOn: boolBinding({ s.websiteIcons }, { securityModel.setWebsiteIcons($0) }))
                Toggle(
                    L10n.prefItemLoadGravatarIconsTitle,
                    isOn: boolBinding({ s.gravatar }, { securityModel.setGravatar($0) }))
            }
            #if os(macOS)
            Section(L10n.settingsExperienceHeaderTitle) {
                Toggle(
                    L10n.prefItemMinimizeOnCopyTitle,
                    isOn: boolBinding({ s.minimizeOnCopy }, { preferencesModel.setMinimizeOnCopy($0) }))
                Toggle(
                    L10n.prefItemCloseToMenuBarTitle,
                    isOn: boolBinding({ s.closeToTray }, { preferencesModel.setCloseToTray($0) }))
            }
            #endif
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        .observing(
            start: { preferencesModel.startAppearanceSettingsObservation() },
            stop: { preferencesModel.stopAppearanceSettingsObservation() }
        )
    }

    @ViewBuilder
    private func optionPicker(
        _ title: String,
        options: [SettingOptionSnapshot],
        currentTitle: String,
        set: @escaping (String) -> Void
    ) -> some View {
        if options.isEmpty {
            HStack {
                Text(title)
                Spacer()
                Text(currentTitle).foregroundStyle(.secondary)
            }
        } else {
            Picker(
                title,
                selection: Binding(
                    get: { options.first(where: { $0.selected })?.id ?? options.first?.id ?? "" },
                    set: { set($0) }
                )
            ) {
                ForEach(options, id: \.id) { option in
                    Text(option.title).tag(option.id)
                }
            }
        }
    }
}

/// Shared empty-state body for a not-yet-implemented settings sub-route.
struct SettingsPlaceholder: View {
    let item: SettingsItemSnapshot

    var body: some View {
        ContentUnavailableView {
            Label(item.title, systemImage: SettingsIcon.symbol(for: item.id))
        } description: {
            if let text = item.text, !text.isEmpty {
                Text(text)
            }
        }
        .navigationTitle(item.title)
    }
}
