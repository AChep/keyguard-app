package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.util.webdav.isValidWebDavCollectionUrl
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsSettingsState
import com.artemchep.keyguard.feature.home.settings.backups.automaticBackupsSettingsStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_automatic_backups_wizard_destination_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString

/** Shared saved configuration and backup status; each setup wizard owns its editor. */
internal class BackupsController(
    private val ctx: CoreContext,
) {
    // BackupConfigRepository is session-scoped; resolved per-session in
    // observeBackupSettings and cached for disableBackup.
    private var backupConfigRepositoryRef: BackupConfigRepository? = null

    private var manualRun: Job? = null

    private var latestBackupState: AutomaticBackupsSettingsState? = null
    private var backupOnChange: ((BackupSettingsSnapshot) -> Unit)? = null

    fun observeBackupSettings(
        onChange: (BackupSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        backupOnChange = onChange
        val subscription = ctx.launchSessionObserver(
            onLocked = {
                manualRun?.cancel()
                manualRun = null
                latestBackupState = null
                backupConfigRepositoryRef = null
                onChange(BackupSettingsSnapshot.empty)
            },
        ) { state ->
            // Configuration belongs to the unlocked vault; the shared use cases
            // resolve platform storage from its parent DI.
            val sessionKoin = state.sessionKoin
            val producer = try {
                val repository = sessionKoin.get<BackupConfigRepository>()
                ctx.publishOnMain {
                    backupConfigRepositoryRef = repository
                }
                ctx.koin.newHeadlessStateFlowScope("settings_automatic_backups", this)
                    .automaticBackupsSettingsStateProducer(
                        backupConfigRepository = sessionKoin.get(),
                        runBackupNow = sessionKoin.get(),
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.koin.get<LogRepository>()
                    .post(
                        tag = "BackupsController",
                        message = "Backup initialization failed: ${e::class.simpleName}",
                        level = LogLevel.ERROR,
                    )
                ctx.publishOnMain {
                    latestBackupState = null
                    backupConfigRepositoryRef = null
                    onChange(BackupSettingsSnapshot(initializationFailed = true))
                }
                return@launchSessionObserver
            }
            producer.collectOnMain { loadable ->
                val s = loadable.getOrNull()
                latestBackupState = s
                onChange(projectBackup(s))
            }
        }
        return KeyguardCancellable {
            subscription.cancel()
            latestBackupState = null
            backupConfigRepositoryRef = null
            backupOnChange = null
        }
    }

    private fun projectBackup(state: AutomaticBackupsSettingsState?): BackupSettingsSnapshot {
        if (state == null) return BackupSettingsSnapshot.empty
        val config = state.config
        val savedStore = config.store
        return BackupSettingsSnapshot(
            loaded = true,
            enabled = config.enabled,
            storeKind = if (savedStore is BackupStoreConfig.WebDav) "webdav" else "local",
            localPath = (savedStore as? BackupStoreConfig.Local)?.path,
            webDavUrl = (savedStore as? BackupStoreConfig.WebDav)?.url,
            webDavUsername = (savedStore as? BackupStoreConfig.WebDav)?.username,
            hasPassword = config.password != null,
            includeAttachments = config.includeAttachments,
            retentionMaxSnapshots = config.retention.maxSnapshots,
            lastSuccessfulBackupAtMs = state.status.lastSuccessfulBackupAt?.toEpochMilliseconds(),
            lastErrorMessage = state.status.lastErrorMessage,
            isDirty = state.status.isDirty,
            runningStep = state.status.currentRun?.step?.name,
            isRunning = state.status.currentRun != null || manualRun?.isActive == true,
        )
    }

    private fun reemitBackup() {
        backupOnChange?.invoke(projectBackup(latestBackupState))
    }

    fun makeBackupSetupSession(): BackupSetupSession = BackupSetupSession { publish, complete ->
        var editor: BackupSetupEditor? = null
        val subscription = ctx.launchSessionObserver(onLocked = { complete() }) { state ->
            try {
                val repository = state.sessionKoin.get<BackupConfigRepository>()
                val testLocation = state.sessionKoin.get<TestBackupLocation>()
                val initial = repository.getConfig().first()
                val invalidDestinationMessage = getString(
                    Res.string.pref_item_automatic_backups_wizard_destination_title,
                )
                ctx.publishOnMain {
                    val activeEditor = BackupSetupEditor(
                        initial = initial,
                        scope = this,
                        verify = { testLocation(it).invoke() },
                        save = { repository.setConfig(it).invoke() },
                        invalidDestinationMessage = invalidDestinationMessage,
                        publish = { publish(it, editor) },
                        complete = complete,
                    )
                    editor = activeEditor
                    try {
                        activeEditor.publish()
                        awaitCancellation()
                    } finally {
                        activeEditor.close()
                        editor = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.koin.get<LogRepository>().post(
                    tag = "BackupsController",
                    message = "Backup setup initialization failed: ${e::class.simpleName}",
                    level = LogLevel.ERROR,
                )
                ctx.publishOnMain { publish(BackupSetupSnapshot(initializationFailed = true), null) }
            }
        }
        KeyguardCancellable {
            // Invalidate actions synchronously, before coroutine cancellation finishes.
            editor?.close()
            editor = null
            subscription.cancel()
        }
    }

    fun isValidBackupWebDavUrl(url: String): Boolean = isValidWebDavCollectionUrl(url)

    fun triggerBackupNow() {
        val state = latestBackupState ?: return
        if (state.status.currentRun != null || manualRun?.isActive == true) return
        manualRun = ctx.koin.get<RunBackupNow>()().launchIn(ctx.backgroundScope).also { job ->
            job.invokeOnCompletion { ctx.scope.launch { reemitBackup() } }
        }
        reemitBackup()
    }

    fun setBackupRetention(maxSnapshots: Int) {
        latestBackupState?.onRetentionChange?.invoke(maxSnapshots)
        reemitBackup()
    }

    fun disableBackup() {
        backupConfigRepositoryRef?.setConfig(BackupConfig())?.launchIn(ctx.scope)
    }
}
