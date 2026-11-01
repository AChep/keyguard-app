package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.home.settings.backups.pickBackupLocation
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Asks for a file to import as [format], then opens it.
 */
internal fun ImportSourceFlow.pickFile(
    format: ImportFormat,
    filePicker: EventFlow<FilePickerIntent<*>>,
) {
    val intent = FilePickerIntent.OpenDocument(
        mimeTypes = FilePickerIntent.mimeTypesAll,
    ) { file ->
        if (file != null) {
            open(
                ref = ImportSourceRef.File(format, file),
                password = null,
            )
        }
    }
    filePicker.emit(intent)
}

/**
 * Asks for a location of the same kind as [store], starting from its values,
 * then opens the backup there.
 */
internal fun RememberStateFlowScope.pickBackup(
    sourceFlow: ImportSourceFlow,
    store: BackupStoreConfig,
    filePicker: EventFlow<FilePickerIntent<*>>,
) {
    pickBackupLocation(
        current = store,
        filePicker = filePicker,
        write = false,
    ) { picked ->
        if (picked != null) {
            sourceFlow.open(
                ref = ImportSourceRef.Backup(picked),
                password = null,
            )
        }
    }
}

/**
 * Opens the backup configured in Settings › Backups, with its password.
 */
internal fun RememberStateFlowScope.openConfiguredBackup(
    sourceFlow: ImportSourceFlow,
    steps: ImportSteps,
    backupConfigRepository: BackupConfigRepository,
) {
    val loading = Step.Loading()
    steps.set(loading)
    screenScope.launch {
        val config = backupConfigRepository.getConfig().first()
        if (!config.store.isConfigured) {
            steps.move(loading, Step.Start)
            return@launch
        }
        sourceFlow.open(
            ref = ImportSourceRef.Backup(config.store),
            password = config.password,
            retry = Retry.ConfiguredBackup,
            expectedStep = loading,
        )
    }
}
