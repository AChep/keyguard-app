import SwiftUI
import KeyguardShared

struct AutomaticBackupsSettingsView: View {
    @Environment(BackupSettingsModel.self) private var backupsModel
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
                        backupsModel.retryBackupSettingsObservation()
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
        .sheet(isPresented: $configuring) {
            BackupSetupWizard(
                makeSession: backupsModel.makeBackupSetupSession,
                isValidWebDavURL: backupsModel.isValidBackupWebDavURL
            )
        }
        .onChange(of: s.loaded) { _, loaded in
            if !loaded { configuring = false }
        }
    }

    private func configure() {
        configuring = true
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

    private var savedLocation: String? {
        BackupLocationLabel.location(
            kind: s.storeKind, localPath: s.localPath, webDavUrl: s.webDavUrl, s3Location: s.s3Location,
            s3EndpointHost: s.s3EndpointHost)
    }

    private var configurationSection: some View {
        Section {
            BackupLocationLabel(kind: s.storeKind, location: savedLocation)
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
                .settingsSearchTarget(.backupConfig)
            Button(L10n.prefItemAutomaticBackupsDisableAction, role: .destructive) { confirmingDisable = true }
                .settingsSearchTarget(.backupDisable)
        }
    }

    private func backupStepTitle(_ step: String) -> String {
        switch step {
        case "OpeningRepository": L10n.backupStepOpeningRepository
        case "ExportingVault": L10n.backupStepExportingVault
        case "ScanningAttachments": L10n.backupStepScanningAttachments
        case "BackingUpAttachments": L10n.backupStepBackingUpAttachments
        case "WritingIndex": L10n.backupStepWritingIndex
        case "WritingSnapshot": L10n.backupStepWritingSnapshot
        case "ApplyingRetention": L10n.backupStepApplyingRetention
        default: L10n.backupStepPreparing
        }
    }

    private static func formatDate(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}
