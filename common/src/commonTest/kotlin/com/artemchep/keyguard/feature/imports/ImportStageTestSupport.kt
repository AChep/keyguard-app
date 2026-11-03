package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.platform.LeContext

/**
 * [ImportStageActions] that do nothing, except for the ones a test passes.
 */
internal fun importStageActions(
    onImport: (LeContext) -> Unit = {},
    onLearnMore: () -> Unit = {},
    onPickFile: (ImportFormat) -> Unit = {},
    onPickBackup: (BackupStoreConfig) -> Unit = {},
    onOpenConfiguredBackup: () -> Unit = {},
    onSnapshotSelect: (Step.Snapshots, String) -> Unit = { _, _ -> },
    onSnapshotConfirm: (Step.Snapshots) -> Unit = {},
    onShowSnapshots: (Step.Snapshots) -> Unit = {},
    onItemSelectionChange: (Step.Review, Int, Boolean) -> Unit = { _, _, _ -> },
) = ImportStageActions(
    onImport = onImport,
    onLearnMore = onLearnMore,
    onPickFile = onPickFile,
    onPickBackup = onPickBackup,
    onOpenConfiguredBackup = onOpenConfiguredBackup,
    onPasswordConfirm = {},
    onPasswordCancel = {},
    onSnapshotSelect = onSnapshotSelect,
    onSnapshotConfirm = onSnapshotConfirm,
    onSnapshotCancel = {},
    onShowSnapshots = onShowSnapshots,
    onConfirm = {},
    onCancel = {},
    onItemSelectionChange = onItemSelectionChange,
    onToggleNote = {},
    onClose = {},
)

/**
 * [Step.toStage] on a platform that offers the credential transfer, with no
 * formats, no open notes and no held payload unless a test passes them.
 */
internal fun Step.toTestStage(
    actions: ImportStageActions = importStageActions(),
    credentialExchange: Boolean = true,
    formats: List<ImportFormat> = emptyList(),
    onSavePayload: (() -> Unit)? = null,
): ImportState.Stage = toStage(
    actions = actions,
    credentialExchange = credentialExchange,
    formats = formats,
    password = null,
    passwordText = null,
    expandedNoteIds = emptySet(),
    onSavePayload = onSavePayload,
)
