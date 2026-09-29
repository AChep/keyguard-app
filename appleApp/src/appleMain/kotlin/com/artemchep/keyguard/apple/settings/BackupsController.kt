package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupSetupDraft
import com.artemchep.keyguard.common.service.backup.verifyAndSaveBackupSetup
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.util.webdav.isValidWebDavCollectionUrl
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsSettingsState
import com.artemchep.keyguard.feature.home.settings.backups.automaticBackupsSettingsStateProducer
import com.artemchep.keyguard.apple.add.AddItemController
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_automatic_backups_wizard_destination_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * Automatic Backups. Runs the shared [automaticBackupsSettingsStateProducer]
 * headlessly for the saved config + status. The native setup wizard owns a
 * complete in-memory draft, including retention, until destination verification
 * succeeds. The folder picker reuses [AddItemController]'s file-picker bridge.
 */
internal class BackupsController(
    private val ctx: CoreContext,
    private val addItemController: AddItemController,
) {
    // BackupConfigRepository is session-scoped; resolved per-session in
    // observeBackupSettings and cached for disableBackup.
    private var backupConfigRepositoryRef: BackupConfigRepository? = null

    private var manualRun: Job? = null

    private var latestBackupState: AutomaticBackupsSettingsState? = null
    private var backupOnChange: ((BackupSettingsSnapshot) -> Unit)? = null
    private var backupSetupDraft: BackupSetupDraft? = null
    private var testBackupLocationRef: TestBackupLocation? = null
    private var setupGeneration = 0L
    private var setupSaveRevision = 0L
    private var setupSaveJob: Job? = null
    private var setupSaving = false
    private var setupError: String? = null

    private fun invalidateSetup() {
        setupGeneration += 1
        setupSaveJob?.cancel()
        setupSaveJob = null
        setupSaving = false
        setupError = null
    }

    fun observeBackupSettings(
        onChange: (BackupSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        backupOnChange = onChange
        val subscription = ctx.launchSessionObserver(
            onLocked = {
                manualRun?.cancel()
                manualRun = null
                latestBackupState = null
                invalidateSetup()
                backupSetupDraft = null
                testBackupLocationRef = null
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
                    testBackupLocationRef = sessionKoin.get<TestBackupLocation>()
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
                    invalidateSetup()
                    backupSetupDraft = null
                    testBackupLocationRef = null
                    backupConfigRepositoryRef = null
                    onChange(BackupSettingsSnapshot(initializationFailed = true))
                }
                return@launchSessionObserver
            }
            var initializedSetup = false
            producer.collectOnMain { loadable ->
                // Draft mutations and snapshots are confined to the main thread.
                val s = loadable.getOrNull()
                if (s != null && !initializedSetup) {
                    initializedSetup = true
                    backupSetupDraft = BackupSetupDraft(s.config)
                }
                latestBackupState = s
                onChange(projectBackup(s))
            }
        }
        return KeyguardCancellable {
            subscription.cancel()
            latestBackupState = null
            invalidateSetup()
            backupSetupDraft = null
            testBackupLocationRef = null
            backupConfigRepositoryRef = null
            backupOnChange = null
        }
    }

    private fun projectBackup(state: AutomaticBackupsSettingsState?): BackupSettingsSnapshot {
        if (state == null) return BackupSettingsSnapshot.empty
        val config = state.config
        val savedStore = config.store
        val draft = backupSetupDraft?.config ?: config
        val setupStore = draft.store
        return BackupSettingsSnapshot(
            loaded = true,
            enabled = config.enabled,
            isTestingLocation = setupSaving,
            setupError = setupError,
            storeKind = if (savedStore is BackupStoreConfig.WebDav) "webdav" else "local",
            localPath = (savedStore as? BackupStoreConfig.Local)?.path,
            webDavUrl = (savedStore as? BackupStoreConfig.WebDav)?.url,
            webDavUsername = (savedStore as? BackupStoreConfig.WebDav)?.username,
            hasPassword = config.password != null,
            includeAttachments = config.includeAttachments,
            retentionMaxSnapshots = config.retention.maxSnapshots,
            setupStoreKind = if (setupStore is BackupStoreConfig.WebDav) "webdav" else "local",
            setupLocalPath = (setupStore as? BackupStoreConfig.Local)?.path,
            setupWebDavUrl = (setupStore as? BackupStoreConfig.WebDav)?.url,
            setupWebDavUsername = (setupStore as? BackupStoreConfig.WebDav)?.username,
            setupHasWebDavPassword = (setupStore as? BackupStoreConfig.WebDav)?.password != null,
            setupHasPassword = draft.password != null,
            setupIncludeAttachments = draft.includeAttachments,
            setupRetentionMaxSnapshots = draft.retention.maxSnapshots,
            setupSaveRevision = setupSaveRevision,
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

    /** Starts a new editor transaction using saved configuration, including its opaque secrets. */
    fun beginBackupSetup() {
        val saved = latestBackupState?.config ?: return
        invalidateSetup()
        backupSetupDraft?.reset(saved)
        reemitBackup()
    }

    /** Cancels verification and invalidates pending folder picker callbacks before discarding edits. */
    fun cancelBackupSetup() = beginBackupSetup()

    private fun editSetup(block: BackupSetupDraft.() -> Unit) {
        if (setupSaving) return
        backupSetupDraft?.block() ?: return
        setupError = null
        reemitBackup()
    }

    fun setBackupIncludeAttachments(value: Boolean) = editSetup { setIncludeAttachments(value) }

    fun setBackupPassword(text: String) = editSetup { setPassword(text) }

    fun restoreBackupSetupPassword() = editSetup {
        restorePassword(latestBackupState?.config ?: return@editSetup)
    }

    fun setBackupStoreKind(kind: String) = editSetup {
        val storeKind = when (kind) {
            "local" -> BackupStoreKind.Local
            "webdav" -> BackupStoreKind.WebDav
            else -> return@editSetup
        }
        setStoreKind(storeKind)
    }

    fun setBackupStoreLocalPath(path: String) = editSetup {
        setStore(BackupStoreConfig.Local(path = path.trim()))
    }

    fun setBackupStoreWebDav(url: String, username: String, password: String) = editSetup {
        setWebDav(url, username, password)
    }

    fun isValidBackupWebDavUrl(url: String): Boolean = isValidWebDavCollectionUrl(url)

    fun setBackupSetupRetention(maxSnapshots: Int) = editSetup { setRetention(maxSnapshots) }

    /** Opens the native folder picker, retaining the selected folder's security-scoped access token. */
    fun pickBackupLocation() {
        if (setupSaving) return
        val draft = backupSetupDraft ?: return
        val generation = setupGeneration
        addItemController.handleFilePickerIntent(
            FilePickerIntent.OpenDirectory(
                readUriPermission = true,
                writeUriPermission = true,
            ) { result ->
                val path = result?.uri?.toString() ?: return@OpenDirectory
                if (generation != setupGeneration || backupSetupDraft !== draft || setupSaving) return@OpenDirectory
                if (draft.config.store !is BackupStoreConfig.Local) return@OpenDirectory
                editSetup {
                    setStore(
                        BackupStoreConfig.Local(
                            path = path,
                            accessToken = result.accessToken?.let(::FileAccessToken),
                        ),
                    )
                }
            },
        )
    }

    /** Verifies and commits one immutable draft; status updates never signal setup completion. */
    fun enableBackup() {
        if (setupSaving) return
        val draft = backupSetupDraft
        val repository = backupConfigRepositoryRef
        val testLocation = testBackupLocationRef
        if (draft == null || repository == null || testLocation == null) return
        val config = draft.config.copy(enabled = true)
        val generation = setupGeneration
        setupError = null
        setupSaving = true
        reemitBackup()
        setupSaveJob = ctx.scope.launch {
            try {
                // Verification failures are arbitrary storage errors shown to the user.
                runCatchingNonFatal {
                    if (!config.canRun()) {
                        setupError = getString(Res.string.pref_item_automatic_backups_wizard_destination_title)
                        return@launch
                    }
                    val saved = verifyAndSaveBackupSetup(
                        config = config,
                        verify = { testLocation(it).invoke() },
                        save = { repository.setConfig(it).invoke() },
                        isCurrent = { generation == setupGeneration },
                    )
                    if (saved) {
                        setupSaveRevision += 1
                    }
                }.onFailure { e ->
                    if (generation == setupGeneration) {
                        setupError = e.message ?: e::class.simpleName
                    }
                }
            } finally {
                if (generation == setupGeneration) {
                    setupSaving = false
                    setupSaveJob = null
                    reemitBackup()
                }
            }
        }
    }

    fun triggerBackupNow() {
        val state = latestBackupState ?: return
        if (state.status.currentRun != null || manualRun?.isActive == true) return
        manualRun = ctx.koin.get<RunBackupNow>()().launchIn(ctx.backgroundScope).also { job ->
            job.invokeOnCompletion { ctx.scope.launch { reemitBackup() } }
        }
        reemitBackup()
    }

    fun setBackupRetention(maxSnapshots: Int) {
        // The legacy immediate-save control also updates the draft so a later
        // configuration save cannot restore its previous retention value.
        backupSetupDraft?.setRetention(maxSnapshots)
        latestBackupState?.onRetentionChange?.invoke(maxSnapshots)
        reemitBackup()
    }

    /** Disables automatic backups by clearing the saved config. */
    fun disableBackup() {
        invalidateSetup()
        backupConfigRepositoryRef?.setConfig(BackupConfig())?.launchIn(ctx.scope)
    }
}
