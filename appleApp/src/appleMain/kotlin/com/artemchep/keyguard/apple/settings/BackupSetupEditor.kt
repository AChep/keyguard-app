package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupSetupDraft
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.verifyAndSaveBackupSetup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-confined editor. Its scope is a child of the observed unlocked vault session. */
internal class BackupSetupEditor(
    initial: BackupConfig,
    private val scope: CoroutineScope,
    private val verify: suspend (BackupConfig) -> Unit,
    private val save: suspend (BackupConfig) -> Unit,
    private val invalidDestinationMessage: String,
    private val publish: (BackupSetupSnapshot) -> Unit,
    private val complete: () -> Unit,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var initial: BackupConfig? = initial
    private var draft: BackupSetupDraft? = BackupSetupDraft(initial)
    private var saveJob: Job? = null
    private var saving = false
    private var error: String? = null

    fun publish() {
        val config = draft?.config ?: return
        val store = config.store
        publish(
            BackupSetupSnapshot(
                loaded = true,
                enabled = config.enabled,
                isTestingLocation = saving,
                error = error,
                storeKind = if (store is BackupStoreConfig.WebDav) "webdav" else "local",
                localPath = (store as? BackupStoreConfig.Local)?.path,
                webDavUrl = (store as? BackupStoreConfig.WebDav)?.url,
                webDavUsername = (store as? BackupStoreConfig.WebDav)?.username,
                hasWebDavPassword = (store as? BackupStoreConfig.WebDav)?.password != null,
                hasPassword = config.password != null,
                includeAttachments = config.includeAttachments,
                retentionMaxSnapshots = config.retention.maxSnapshots,
            ),
        )
    }

    fun edit(block: BackupSetupDraft.() -> Unit) {
        if (saving) return
        draft?.block() ?: return
        error = null
        publish()
    }

    fun restorePassword() = edit { restorePassword(initial ?: return@edit) }

    fun submit() {
        if (saving) return
        val config = draft?.config?.copy(enabled = true) ?: return
        if (config.canRun()) {
            verifyAndSave(config)
        } else {
            error = invalidDestinationMessage
            publish()
        }
    }

    private fun verifyAndSave(config: BackupConfig) {
        error = null
        saving = true
        publish()
        if (draft == null) return
        saveJob = scope.launch {
            try {
                runCatchingNonFatal {
                    val saved = verifyAndSaveBackupSetup(
                        config = config,
                        verify = verify,
                        save = save,
                        isCurrent = { draft != null },
                        dispatcher = storageDispatcher,
                    )
                    if (saved) complete()
                }.onFailure { failure ->
                    error = failure.message ?: failure::class.simpleName
                }
            } finally {
                saving = false
                saveJob = null
                publish()
            }
        }
    }

    fun close() {
        draft = null
        initial = null
        saveJob?.cancel()
        saveJob = null
    }
}
