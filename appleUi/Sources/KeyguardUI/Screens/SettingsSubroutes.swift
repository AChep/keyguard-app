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

/// "Powered by" attribution with the provider as a link.
private func poweredByFooter(_ provider: String, url: String) -> Text {
    markdownText(L10n.poweredByText("[\(provider)](\(url))"))
}

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
        SettingsForm {
            Section(L10n.settingsStartupHeaderTitle) {
                Toggle(L10n.prefItemLaunchAtLoginTitle, isOn: launchBinding)
                    .disabled(!launch.available)
                    .settingsSearchTarget(.launchAtLogin)
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
                    .settingsSearchTarget(.menuBar)
                Text(L10n.prefItemMenuBarOnlyText)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
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
    @Environment(AutofillSettingsModel.self) private var settingsModel
    let item: SettingsItemSnapshot

    private var settings: AutofillSettingsSnapshot { settingsModel.autofillSettings }

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
        SettingsForm(
            ready: settings.loaded,
            aliases: autofillIndex.coordinator.isEnabled ? [.autofillEnable: .autofill] : [:]
        ) {
            Section {
                if !autofillIndex.coordinator.isEnabled {
                    Button(L10n.prefItemAutofillServiceEnableAction) {
                        Task { await autofillIndex.enable() }
                    }
                    .disabled(autofillIndex.isEnabling)
                    .settingsSearchTarget(.autofillEnable)
                }
                Group {
                    if autofillIndex.coordinator.isEnabled {
                        Label(L10n.prefItemAutofillServiceEnabledText, systemImage: "checkmark.circle")
                    } else {
                        Text(L10n.prefItemAutofillServiceDisabledText).foregroundStyle(.secondary)
                    }
                }
                .settingsSearchTarget(.autofillStatus)
                Button(L10n.prefItemAutofillServiceOpenSettingsAction) { Task { await autofillIndex.openSettings() } }
                    .settingsSearchTarget(.autofill)
                Text(indexStatus).foregroundStyle(.secondary)
                    .settingsSearchTarget(.autofillIndexStatus)
                Button(L10n.prefItemAutofillServiceRefreshAction) { autofillIndex.refreshAutofillIdentities() }
                    .disabled(autofillIndex.coordinator.state == .updating)
                    .settingsSearchTarget(.autofillRefresh)
            }
            Section {
                optionPicker(
                    L10n.prefItemAutofillDefaultMatchDetectionTitle,
                    options: settings.defaultMatchDetectionOptions,
                    currentTitle: settings.defaultMatchDetectionTitle
                ) { settingsModel.setAutofillDefaultMatchDetection($0) }
                .settingsSearchTarget(.autofillDefaultMatchDetection)
            }
        }
        .onAppear { autofillIndex.refreshAutofillIdentities() }
        .navigationTitle(item.title)
        .observing(
            start: { settingsModel.startAutofillSettingsObservation() },
            stop: { settingsModel.stopAutofillSettingsObservation() }
        )
    }
}

struct SecuritySettingsView: View {
    @Environment(SessionFactory.self) private var sessions
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
            get: { get() },
            set: { value in set(value) }
        )
    }

    var body: some View {
        SettingsForm(
            ready: s.loaded,
            aliases: [
                .biometricTimeout: s.biometricUnlockEnabled ? .biometricTimeout : .biometric,
                .lockReboot: s.lockAfterRebootVisible ? .lockReboot : .persist,
            ]
        ) {
            // Biometric unlock — its own section so the explanation lands in the
            // native inset footnote rather than as a full-width gray row.
            if s.biometricUnlockSupported {
                Section {
                    Toggle(
                        AppleBiometry.current.unlockTitle(bundle: AppLocalization.shared.bundle),
                        isOn: boolBinding({ s.biometricUnlockEnabled }, { securityModel.setBiometricUnlock($0) })
                    )
                    .settingsSearchTarget(.biometric)
                    if s.biometricUnlockEnabled {
                        durationPicker(
                            L10n.prefItemBiometricUnlockTimeoutTitle,
                            options: s.biometricTimeoutOptions,
                            currentTitle: s.biometricTimeoutTitle,
                            set: { securityModel.setBiometricTimeout($0) }
                        )
                        .settingsSearchTarget(.biometricTimeout)
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
                        isOn: boolBinding({ s.fido2UnlockEnabled }, { securityModel.setFido2Unlock($0) })
                    )
                    .settingsSearchTarget(.fido2)
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
                            })
                    )
                    .settingsSearchTarget(.yubikey)
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
                    isOn: boolBinding({ s.vaultPersist }, { securityModel.setVaultPersist($0) })
                )
                .settingsSearchTarget(.persist)
                durationPicker(
                    L10n.prefItemVaultLockTimeoutTitle,
                    options: s.lockTimeoutOptions,
                    currentTitle: s.lockTimeoutTitle,
                    set: { securityModel.setVaultLockTimeout($0) }
                )
                .settingsSearchTarget(.lockTimeout)
                if s.lockAfterRebootVisible {
                    Toggle(
                        L10n.prefItemLockVaultAfterRebootText,
                        isOn: boolBinding({ s.lockAfterReboot }, { securityModel.setVaultLockAfterReboot($0) })
                    )
                    .settingsSearchTarget(.lockReboot)
                }
                Button(L10n.prefItemLockVaultTitle) { authModel.lockVault() }
                    .settingsSearchTarget(.lock)
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
                    set: { securityModel.setClipboardAutoClear($0) }
                )
                .settingsSearchTarget(.clipboard)
            }
            Section {
                Toggle(
                    L10n.prefItemConcealFieldsTitle,
                    isOn: boolBinding({ s.conceal }, { securityModel.setConcealFields($0) })
                )
                .settingsSearchTarget(.conceal)
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
                    .settingsSearchTarget(.changePassword)
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { securityModel.startSecuritySettingsObservation() },
            stop: { securityModel.stopSecuritySettingsObservation() }
        )
        .sheet(isPresented: $changingPassword) {
            ChangePasswordSheet(makeSession: sessions.makeChangePasswordSession)
        }
        .confirmationDialog(
            L10n.yubikeySlotPickerTitle,
            isPresented: $choosingYubiKeySlot,
            titleVisibility: .visible
        ) {
            Button(L10n.yubikeySlotLabel(1)) { beginYubiKeyEnroll(slot: 1) }
            Button(L10n.yubikeySlotLabel(2)) { beginYubiKeyEnroll(slot: 2) }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.yubikeySlotPickerNote(SecuritySettingsModel.defaultYubiKeySlot))
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

struct DeveloperSettingsView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel
    #if os(macOS)
    @Environment(GpgAgentModel.self) private var gpgAgentModel
    #endif
    let item: SettingsItemSnapshot

    private enum Dialog: String, Identifiable {
        case clientSetup, filters, history
        var id: String { rawValue }
    }

    @State private var dialog: Dialog?

    private var s: SshAgentSettingsSnapshot { sshAgentModel.sshAgentSettings }
    private var status: SshAgentStatusSnapshot { sshAgentModel.sshAgentStatus }

    private var searchReady: Bool {
        #if os(macOS)
        s.loaded && gpgAgentModel.gpgAgentSettings.loaded
        #else
        s.loaded
        #endif
    }

    var body: some View {
        SettingsForm(
            ready: searchReady,
            aliases: status.running && status.sshAuthSock != nil ? [:] : [.sshSocket: .sshEnable]
        ) {
            #if os(macOS)
            sshAgentSection
            approvalsSection
            keysSection
            GpgAgentSettingsSections()
            #else
            Section(L10n.agentTitle(L10n.protocolSsh)) {
                Label(L10n.agentBinaryUnavailableText, systemImage: "xmark.circle")
                    .foregroundStyle(.secondary)
            }
            #endif
        }
        .navigationTitle(item.title)
        #if os(macOS)
        .observing(
            start: {
                sshAgentModel.startSshAgentSettingsObservation()
                sshAgentModel.startSshAgentObservation()
                gpgAgentModel.startGpgAgentObservation()
                gpgAgentModel.startGpgAgentSettingsObservation()
            },
            stop: {
                sshAgentModel.stopSshAgentSettingsObservation()
                gpgAgentModel.stopGpgAgentSettingsObservation()
            }
        )
        .sheet(item: $dialog) { dialog in
            switch dialog {
            case .clientSetup:
                ModalSheet(title: L10n.agentSetupTitle(L10n.protocolSsh)) {
                    SshAgentSetupView()
                }
            case .filters:
                AgentFiltersView(
                    title: L10n.agentFiltersTitle(L10n.protocolSsh),
                    lockedText: L10n.sshAgentFiltersLockedText,
                    makeSession: sshAgentModel.makeFiltersSession
                )
            case .history:
                ModalSheet(title: L10n.agentHistoryHeaderTitle(L10n.protocolSsh)) {
                    SshAgentHistoryScreen(makeSession: sshAgentModel.makeHistorySession)
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
            .settingsSearchTarget(.sshEnable)
            statusRow
                .settingsSearchTarget(.sshStatus)
            if let sock = status.sshAuthSock, status.running {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L10n.sshAgentSetupClientSocketNote)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Text("export SSH_AUTH_SOCK=\(sock)")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                }
                .settingsSearchTarget(.sshSocket)
            }
            Button(L10n.agentSetupTitle(L10n.protocolSsh)) { dialog = .clientSetup }
                .settingsSearchTarget(.sshSetup)
        } header: {
            Text(L10n.agentTitle(L10n.protocolSsh))
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
                Text(L10n.prefItemAgentStatusStarting).foregroundStyle(.secondary)
            }
        case SshAgentRunState.failed:
            Label(L10n.statusFailedToStart, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case SshAgentRunState.unsupported:
            Label(L10n.agentBinaryUnavailableText, systemImage: "xmark.circle")
                .foregroundStyle(.secondary)
        default:
            Label(L10n.prefItemAgentStatusStopped, systemImage: "stop.circle")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var approvalsSection: some View {
        Section {
            durationPicker(
                L10n.prefItemAgentApprovalWindowTitle,
                options: s.approvalWindowOptions,
                currentTitle: s.approvalWindowTitle,
                set: { sshAgentModel.setSshAgentApprovalWindow(optionId: $0) }
            )
            .settingsSearchTarget(.sshApproval)
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
                L10n.prefItemAgentDisplayKeyNamesTitle,
                isOn: Binding(
                    get: { s.displayKeyNames },
                    set: { sshAgentModel.setSshAgentDisplayKeyNames($0) }
                )
            )
            .settingsSearchTarget(.sshNames)
        } header: {
            Text(L10n.agentKeysHeaderTitle)
        } footer: {
            Text(L10n.prefItemAgentDisplayKeyNamesNote)
        }
        Section {
            Button {
                dialog = .filters
            } label: {
                HStack {
                    Label(L10n.agentFiltersTitle(L10n.protocolSsh), systemImage: "line.3.horizontal.decrease.circle")
                    Spacer()
                    Text(
                        s.filterActive
                            ? L10n.prefItemAgentFiltersSummaryActive : L10n.prefItemAgentFiltersSummaryAll
                    )
                    .font(.callout)
                    .foregroundStyle(.secondary)
                }
            }
            .settingsSearchTarget(.sshFilters)
            Button {
                dialog = .history
            } label: {
                Label(L10n.prefItemAgentHistoryTitle, systemImage: "clock.arrow.circlepath")
            }
            .settingsSearchTarget(.sshHistory)
        }
    }

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
            get: { get() },
            set: { value in set(value) }
        )
    }

    var body: some View {
        SettingsForm(ready: s.loaded) {
            Section {
                Toggle(
                    L10n.prefItemCheckPwnedPasswordsTitle,
                    isOn: boolBinding({ s.checkPwnedPasswords }, { watchtowerModel.setCheckPwnedPasswords($0) })
                )
                .settingsSearchTarget(.pwnedPasswords)
                Toggle(
                    L10n.prefItemCheckPwnedServicesTitle,
                    isOn: boolBinding({ s.checkPwnedServices }, { watchtowerModel.setCheckPwnedServices($0) })
                )
                .settingsSearchTarget(.pwnedServices)
                hibpTokenRow
                    .settingsSearchTarget(.hibpToken)
            } header: {
                Text(L10n.prefItemHibpHeaderTitle)
            } footer: {
                poweredByFooter("haveibeenpwned.com", url: KeyguardUrls.shared.HAVE_I_BEEN_PWNED)
            }
            Section {
                Toggle(
                    L10n.prefItemCheckInactive2faTitle,
                    isOn: boolBinding({ s.checkTwoFa }, { watchtowerModel.setCheckTwoFa($0) })
                )
                .settingsSearchTarget(.twoFa)
            } header: {
                Text(L10n.tfaDirectoryTitle)
            } footer: {
                poweredByFooter("2fa.directory", url: KeyguardUrls.shared.TWO_FA_DIRECTORY)
            }
            Section {
                Toggle(
                    L10n.prefItemCheckInactivePasskeysTitle,
                    isOn: boolBinding({ s.checkPasskeys }, { watchtowerModel.setCheckPasskeys($0) })
                )
                .settingsSearchTarget(.passkeys)
            } header: {
                Text(L10n.passkeysDirectoryTitle)
            } footer: {
                poweredByFooter("passkeys.directory", url: KeyguardUrls.shared.PASSKEYS_DIRECTORY)
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { watchtowerModel.startWatchtowerSettingsObservation() },
            stop: { watchtowerModel.stopWatchtowerSettingsObservation() }
        )
        .sheet(isPresented: $editingToken) {
            HibpTokenEditor(
                currentToken: s.hibpApiToken ?? "",
                isValidToken: watchtowerModel.isValidHibpApiToken
            ) { newToken in
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

/// An empty token clears the key.
private struct HibpTokenEditor: View {
    let currentToken: String
    let isValidToken: @MainActor (String) -> Bool
    let onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var token: String = ""

    private var trimmed: String { token.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var isValid: Bool { isValidToken(trimmed) }

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
                    TextField(L10n.prefItemHibpApiTokenFieldLabel, text: $token)
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
                                L10n.prefItemHibpApiTokenErrorPlural(
                                    Int(KeyguardConstants.shared.HIBP_API_TOKEN_LENGTH)),
                                systemImage: "exclamationmark.triangle"
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
            get: { get() },
            set: { value in set(value) }
        )
    }

    var body: some View {
        SettingsForm(ready: s.loaded) {
            Section {
                optionPicker(L10n.prefItemLocaleTitle, options: s.localeOptions, currentTitle: s.localeTitle) {
                    preferencesModel.setLocale($0)
                }
                .settingsSearchTarget(.locale)
            } header: {
                Text(L10n.prefItemLocaleTitle)
            } footer: {
                Text(L10n.prefItemAppearanceRestartNote)
            }
            Section(L10n.prefItemColorSchemeSelectionTitle) {
                optionPicker(L10n.prefItemColorSchemeTitle, options: s.themeOptions, currentTitle: s.themeTitle) {
                    preferencesModel.setTheme($0)
                }
                .settingsSearchTarget(.theme)
                optionPicker(L10n.prefItemColorAccentTitle, options: s.accentOptions, currentTitle: s.accentTitle) {
                    preferencesModel.setColors($0)
                }
                .settingsSearchTarget(.accent)
            }
            Section(L10n.settingsNavigationHeaderTitle) {
                NavigationLink {
                    NavigationItemsSettingsView()
                } label: {
                    Label(L10n.settingsNavigationItemsHeaderTitle, systemImage: "list.bullet.rectangle")
                }
                .settingsSearchTarget(.navigation)
                Toggle(
                    L10n.prefItemNavLabelShortTitle,
                    isOn: boolBinding({ s.navLabel }, { preferencesModel.setNavLabel($0) })
                )
                .settingsSearchTarget(.navigationLabels)
                Toggle(
                    L10n.prefItemRenderMarkdownTitle,
                    isOn: boolBinding({ s.markdown }, { preferencesModel.setMarkdown($0) })
                )
                .settingsSearchTarget(.markdown)
            }
            Section(L10n.settingsIconsHeaderTitle) {
                Toggle(
                    L10n.prefItemLoadWebsiteIconsTitle,
                    isOn: boolBinding({ s.websiteIcons }, { securityModel.setWebsiteIcons($0) })
                )
                .settingsSearchTarget(.websiteIcons)
                Toggle(
                    L10n.prefItemLoadGravatarIconsTitle,
                    isOn: boolBinding({ s.gravatar }, { securityModel.setGravatar($0) })
                )
                .settingsSearchTarget(.gravatar)
            }
            Section(L10n.settingsExperienceHeaderTitle) {
                #if os(iOS)
                Toggle(
                    L10n.prefItemOpenLinksInExternalBrowserTitle,
                    isOn: boolBinding({ s.useExternalBrowser }, { preferencesModel.setUseExternalBrowser($0) })
                )
                .disabled(!s.loaded)
                .settingsSearchTarget(.externalBrowser)
                Toggle(
                    L10n.prefItemKeepScreenOnTitle,
                    isOn: boolBinding({ s.keepScreenOn }, { preferencesModel.setKeepScreenOn($0) })
                )
                .disabled(!s.loaded)
                .settingsSearchTarget(.keepAwake)
                #else
                Toggle(
                    L10n.prefItemMinimizeOnCopyTitle,
                    isOn: boolBinding({ s.minimizeOnCopy }, { preferencesModel.setMinimizeOnCopy($0) })
                )
                .settingsSearchTarget(.minimize)
                Toggle(
                    L10n.prefItemCloseToMenuBarTitle,
                    isOn: boolBinding({ s.closeToTray }, { preferencesModel.setCloseToTray($0) })
                )
                .settingsSearchTarget(.closeToTray)
                #endif
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { preferencesModel.startAppearanceSettingsObservation() },
            stop: { preferencesModel.stopAppearanceSettingsObservation() }
        )
    }
}

/// Forwards the chosen opaque option id; falls back to a static row until the options have loaded.
@MainActor
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
