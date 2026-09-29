package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupSetupDraft
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.common.service.backup.verifyAndSaveBackupSetup
import com.artemchep.keyguard.util.webdav.isValidWebDavCollectionUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class BackupSetupStep {
    Destination,
    Protection,
    Contents,
    Review,
}

internal data class BackupSetupData(
    val config: BackupConfig,
    val step: BackupSetupStep = BackupSetupStep.Destination,
    val confirmationPassword: String = "",
    val hasEdits: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isEditable: Boolean
        get() = !isSaving && !confirmingDiscard

    val passwordMatches: Boolean
        get() = config.password == null || config.password.value == confirmationPassword

    val showPasswordMismatch: Boolean
        get() = confirmationPassword.isNotEmpty() && !passwordMatches

    val destinationValid: Boolean
        get() = when (val store = config.store) {
            is BackupStoreConfig.Local -> store.isConfigured
            is BackupStoreConfig.WebDav -> isValidWebDavCollectionUrl(store.url.orEmpty())
        }

    val canContinue: Boolean
        get() = isEditable && when (step) {
            BackupSetupStep.Destination -> destinationValid
            BackupSetupStep.Protection -> passwordMatches
            BackupSetupStep.Contents -> true
            BackupSetupStep.Review -> destinationValid && passwordMatches
        }
}

/** A memory-only editor. The owner confines actions to the UI dispatcher. */
internal class AutomaticBackupsSetupEditor(
    initial: BackupConfig,
    private val scope: CoroutineScope,
    private val verify: suspend (BackupConfig) -> Unit,
    private val save: suspend (BackupConfig) -> Unit,
    private val onClose: () -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val draft = BackupSetupDraft(initial)
    private val sink = MutableStateFlow(BackupSetupData(config = initial))
    val state = sink.asStateFlow()
    private var closed = false
    private var destinationRevision = 0L

    private val active: Boolean get() = !closed && scope.isActive
    private val editable: Boolean get() = active && sink.value.isEditable

    private fun edit(block: BackupSetupDraft.() -> Unit) {
        if (!editable) return
        draft.block()
        if (draft.config == sink.value.config) return
        sink.value = sink.value.copy(config = draft.config, hasEdits = true, error = null)
    }

    fun selectStore(kind: BackupStoreKind) {
        if (!editable) return
        destinationRevision += 1
        edit { setStoreKind(kind) }
    }

    /** Each picker request belongs to one destination selection and one live editor. */
    fun destinationReceiver(): (BackupStoreConfig?) -> Unit {
        val revision = ++destinationRevision
        val local = draft.config.store is BackupStoreConfig.Local
        return receive@ { store ->
            if (!editable || revision != destinationRevision) return@receive
            destinationRevision += 1
            if (store != null && local == (store is BackupStoreConfig.Local)) {
                edit { setStore(store) }
            }
        }
    }

    fun setPassword(value: String) = edit { setPassword(value) }

    fun setConfirmationPassword(value: String) {
        if (!editable || sink.value.confirmationPassword == value) return
        sink.value = sink.value.copy(confirmationPassword = value, hasEdits = true, error = null)
    }

    fun setIncludeAttachments(value: Boolean) = edit { setIncludeAttachments(value) }

    fun setRetention(value: Int) = edit { setRetention(value) }

    fun back() {
        if (!editable) return
        val current = sink.value
        if (current.step != BackupSetupStep.Destination) {
            sink.value = current.copy(step = BackupSetupStep.entries[current.step.ordinal - 1])
        } else if (current.hasEdits) {
            sink.value = current.copy(confirmingDiscard = true)
        } else {
            close()
        }
    }

    fun confirmDiscard(confirmed: Boolean) {
        if (!active || !sink.value.confirmingDiscard) return
        if (confirmed) {
            close()
        } else {
            sink.value = sink.value.copy(confirmingDiscard = false)
        }
    }

    fun next() {
        if (!editable || !sink.value.canContinue) return
        val current = sink.value
        if (current.step != BackupSetupStep.Review) {
            sink.value = current.copy(step = BackupSetupStep.entries[current.step.ordinal + 1])
            return
        }
        val config = draft.config.copy(enabled = true)
        sink.value = current.copy(isSaving = true, error = null)
        scope.launch {
            try {
                runCatchingNonFatal {
                    verifyAndSaveBackupSetup(
                        config = config,
                        verify = verify,
                        save = save,
                        isCurrent = { active },
                        dispatcher = workerDispatcher,
                    )
                }.onSuccess { saved ->
                    if (saved) close()
                }.onFailure { e ->
                    if (active) sink.value = sink.value.copy(error = e.message ?: e::class.simpleName)
                }
            } finally {
                if (active) sink.value = sink.value.copy(isSaving = false)
            }
        }
    }

    private fun close() {
        closed = true
        onClose()
    }
}
