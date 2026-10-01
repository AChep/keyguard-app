import SwiftUI
import KeyguardShared

struct AutomaticBackupsSettingsView: View {
    @Environment(BackupSettingsModel.self) private var backupsModel
    @Environment(FilePickerModel.self) private var filePickerModel
    let item: SettingsItemSnapshot

    @State private var confirmingDisable = false
    @State private var configuring = false

    private var s: BackupSettingsSnapshot { backupsModel.backupSettings }

    var body: some View {
        // Keep observation on a stable container while loading and editing.
        VStack(spacing: 0) {
            if s.initializationFailed {
                ContentUnavailableView {
                    Label(L10n.prefItemAutomaticBackupsUnavailableTitle, systemImage: "externaldrive.badge.xmark")
                } description: {
                    Text(L10n.prefItemAutomaticBackupsInitializationError)
                } actions: {
                    Button(L10n.retry) {
                        backupsModel.stopBackupSettingsObservation()
                        backupsModel.startBackupSettingsObservation()
                    }
                }
            } else if !s.loaded {
                ProgressView()
            } else {
                SettingsForm(
                    aliases: s.enabled
                        ? [.backupSetup: .backupConfig]
                        : [
                            .backupRun: .backupSetup, .backupConfig: .backupSetup,
                            .backupAttachments: .backupSetup, .backupRetention: .backupSetup,
                            .backupDisable: .backupSetup,
                            .backupStatus: .backupSetup, .backupLastSuccess: .backupSetup,
                            .backupLocation: .backupSetup, .backupPassword: .backupSetup,
                        ]
                ) {
                    if s.enabled {
                        statusSection
                        configurationSection
                    } else {
                        Section {
                            VStack(alignment: .leading, spacing: 16) {
                                Image(systemName: "externaldrive.badge.timemachine")
                                    .font(.largeTitle)
                                    .foregroundStyle(.tint)
                                    .accessibilityHidden(true)
                                Text(L10n.prefItemAutomaticBackupsWizardReadyTitle)
                                    .font(.title2.bold())
                                Text(L10n.prefItemAutomaticBackupsWizardReadyDetail)
                                    .foregroundStyle(.secondary)
                                Button(L10n.prefItemAutomaticBackupsWizardTitle, action: configure)
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.large)
                            }
                            .padding(.vertical, 12)
                            .settingsSearchTarget(.backupSetup)
                        } footer: {
                            Text(L10n.prefItemAutomaticBackupsSetupIntro)
                        }
                    }
                }
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { backupsModel.startBackupSettingsObservation() },
            stop: { backupsModel.stopBackupSettingsObservation() }
        )
        .confirmationDialog(L10n.prefItemAutomaticBackupsDisableTitle, isPresented: $confirmingDisable) {
            Button(L10n.prefItemAutomaticBackupsDisableTitle, role: .destructive) { backupsModel.disableBackup() }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.prefItemAutomaticBackupsDisableMessage)
        }
        .sheet(isPresented: $configuring, onDismiss: endSetup) {
            BackupSetupWizard(initial: s)
        }
        .onChange(of: s.loaded) { _, loaded in
            if !loaded { configuring = false }
        }
    }

    private func configure() {
        backupsModel.beginBackupSetup()
        filePickerModel.beginBackupSetupPickerSession()
        configuring = true
    }

    private func endSetup() {
        filePickerModel.endBackupSetupPickerSession()
        backupsModel.cancelBackupSetup()
    }

    private var statusSection: some View {
        Section(L10n.prefItemAutomaticBackupsPanelStatusLabel) {
            Label(L10n.prefItemAutomaticBackupsEnabledTitle, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .settingsSearchTarget(.backupStatus)
            LabeledContent(
                L10n.prefItemAutomaticBackupsPanelLastSyncTitle,
                value: s.lastSuccessfulBackupAtMs.map { Self.formatDate($0.int64Value) } ?? L10n.expirationDateNever
            )
            .settingsSearchTarget(.backupLastSuccess)
            if s.isDirty {
                Text(L10n.prefItemAutomaticBackupsPendingChangesText)
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            if let step = s.runningStep {
                LabeledContent(L10n.statusRunning, value: backupStepTitle(step))
            }
            if let error = s.lastErrorMessage, !error.isEmpty {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.subheadline).foregroundStyle(.orange)
            }
            Button(L10n.prefItemAutomaticBackupsRunNowTitle) { backupsModel.triggerBackupNow() }
                .disabled(s.isRunning)
                .settingsSearchTarget(.backupRun)
        }
    }

    private var configurationSection: some View {
        Section {
            BackupLocationLabel(
                isWebDav: s.storeKind == "webdav", location: s.storeKind == "webdav" ? s.webDavUrl : s.localPath
            )
            .settingsSearchTarget(.backupLocation)
            Label(
                s.hasPassword ? L10n.prefItemAutomaticBackupsPasswordSet : L10n.prefItemAutomaticBackupsPasswordNotSet,
                systemImage: s.hasPassword ? "lock.shield" : "lock.open"
            )
            .settingsSearchTarget(.backupPassword)
            LabeledContent(
                L10n.prefItemAutomaticBackupsIncludeAttachmentsTitle,
                value: s.includeAttachments
                    ? L10n.prefItemAutomaticBackupsIncludeAttachmentsEnabledSummary
                    : L10n.prefItemAutomaticBackupsIncludeAttachmentsDisabledSummary
            )
            .settingsSearchTarget(.backupAttachments)
            LabeledContent(
                L10n.prefItemAutomaticBackupsRetentionLabel,
                value: s.retentionMaxSnapshots == 0
                    ? L10n.prefItemAutomaticBackupsRetentionKeepAll
                    : L10n.prefItemAutomaticBackupsRetentionKeepSnapshotCount(Int(s.retentionMaxSnapshots))
            )
            .settingsSearchTarget(.backupRetention)
            Button(L10n.prefItemAutomaticBackupsChangeConfigurationAction, action: configure)
                .disabled(s.isTestingLocation)
                .settingsSearchTarget(.backupConfig)
            Button(L10n.prefItemAutomaticBackupsDisableAction, role: .destructive) { confirmingDisable = true }
                .settingsSearchTarget(.backupDisable)
        }
    }

    private func backupStepTitle(_ step: String) -> String {
        switch step {
        case "OpeningRepository": L10n.prefItemAutomaticBackupsStepOpeningRepository
        case "ExportingVault": L10n.prefItemAutomaticBackupsStepExportingVault
        case "ScanningAttachments": L10n.prefItemAutomaticBackupsStepScanningAttachments
        case "BackingUpAttachments": L10n.prefItemAutomaticBackupsStepBackingUpAttachments
        case "WritingIndex": L10n.prefItemAutomaticBackupsStepWritingIndex
        case "WritingSnapshot": L10n.prefItemAutomaticBackupsStepWritingSnapshot
        case "ApplyingRetention": L10n.prefItemAutomaticBackupsStepApplyingRetention
        default: L10n.prefItemAutomaticBackupsStepPreparing
        }
    }

    private static func formatDate(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}
