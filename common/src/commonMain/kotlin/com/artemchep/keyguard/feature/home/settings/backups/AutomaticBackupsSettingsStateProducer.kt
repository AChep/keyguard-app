package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val KEY_BACKUP_PASSWORD = "backup_password"

@Composable
fun produceAutomaticBackupsSettingsScreenState(): Loadable<AutomaticBackupsSettingsState> =
    with(localDI().direct) {
        produceAutomaticBackupsSettingsScreenState(
            backupConfigRepository = instance(),
            runBackupNow = instance(),
            testBackupLocation = instance(),
            confirmationRouteFactory = instance(),
        )
    }

@Composable
fun produceAutomaticBackupsSettingsScreenState(
    backupConfigRepository: BackupConfigRepository,
    runBackupNow: RunBackupNow,
    testBackupLocation: TestBackupLocation,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Loadable<AutomaticBackupsSettingsState> = produceScreenState(
    key = "settings_automatic_backups",
    initial = Loadable.Loading,
    args = arrayOf(
        backupConfigRepository,
        runBackupNow,
        testBackupLocation,
        confirmationRouteFactory,
    ),
) {
    val setupExecutor = screenExecutor()
    val setupErrorSink = MutableStateFlow<String?>(null)
    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()
    val setup = AutomaticBackupsSettingsState.Setup(
        store = mutableStateOf<BackupStoreConfig>(BackupStoreConfig.Local()),
        password = mutableStateOf(""),
        includeAttachments = mutableStateOf(true),
    )

    fun putConfig(
        block: (BackupConfig) -> BackupConfig,
    ) {
        val io = ioEffect {
            val config = backupConfigRepository
                .getConfig()
                .first()
            backupConfigRepository
                .setConfig(
                    block(config).sanitized(),
                )
                .bind()
        }
        io.launchIn(appScope)
    }

    fun onLocationClick() {
        filePickerIntentSink.emit(
            FilePickerIntent.OpenDirectory(
                readUriPermission = true,
                writeUriPermission = true,
                persistableUriPermission = CurrentPlatform is Platform.Mobile.Android,
            ) { result ->
                val path = result?.uri?.toString()
                    ?: return@OpenDirectory
                setup.store.value = BackupStoreConfig.Local(
                    path = path,
                )
                setupErrorSink.value = null
            },
        )
    }

    fun onWebDavLocationClick() {
        val store = setup.store.value as? BackupStoreConfig.WebDav
        val route = registerRouteResultReceiver(
            route = WebDavSettingsRoute(
                args = WebDavSettingsRoute.Args(
                    url = store?.url.orEmpty(),
                    username = store?.username.orEmpty(),
                    password = store?.password?.value.orEmpty(),
                ),
            ),
        ) { result ->
            setup.store.value = BackupStoreConfig.WebDav(
                url = result.url,
                username = result.username,
                password = result.password
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::Password),
            )
            setupErrorSink.value = null
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    suspend fun onPasswordClick() {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Password),
            item = ConfirmationRoute.Args.Item.StringItem(
                key = KEY_BACKUP_PASSWORD,
                value = setup.password.value,
                title = translate(Res.string.pref_item_automatic_backups_password_title),
                type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
                canBeEmpty = true,
            ),
            title = translate(Res.string.pref_item_automatic_backups_password_title),
            message = translate(Res.string.pref_item_automatic_backups_password_message),
        ) { password ->
            setup.password.value = password
        }
        navigate(intent)
    }

    fun buildSetupConfig(
        enabled: Boolean,
    ): BackupConfig {
        val password = setup.password.value
            .takeIf { it.isNotEmpty() }
            ?.let(::Password)
        return BackupConfig(
            enabled = enabled,
            store = setup.store.value.sanitized(),
            password = password,
            includeAttachments = setup.includeAttachments.value,
        ).sanitized()
    }

    fun onEnableClick() {
        val config = buildSetupConfig(enabled = true)
        if (!config.canRun()) {
            setupErrorSink.value = "Choose a backup location first."
            return
        }

        val io = ioEffect {
            try {
                testBackupLocation(config).bind()
                backupConfigRepository
                    .setConfig(config)
                    .bind()
                setupErrorSink.value = null
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                setupErrorSink.value = e.message ?: e::class.simpleName
                throw e
            }
        }
        setupExecutor.execute(io)
    }

    fun disableAutomaticBackups() {
        setup.store.value = BackupStoreConfig.Local()
        setup.password.value = ""
        setup.includeAttachments.value = true
        setupErrorSink.value = null
        backupConfigRepository
            .setConfig(BackupConfig())
            .launchIn(appScope)
    }

    suspend fun onDisableClick() {
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    title = translate(Res.string.pref_item_automatic_backups_disable_title),
                    message = translate(Res.string.pref_item_automatic_backups_disable_message),
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                disableAutomaticBackups()
            }
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    combine(
        backupConfigRepository.getConfig(),
        backupConfigRepository.getStatus(),
        setupErrorSink,
        setupExecutor.isExecutingFlow,
    ) { config, status, setupError, isTestingLocation ->
        AutomaticBackupsSettingsState(
            config = config.sanitized(),
            status = status,
            setup = setup,
            filePickerIntentFlow = filePickerIntentSink,
            setupError = setupError,
            isTestingLocation = isTestingLocation,
            onLocationClick = ::onLocationClick,
            onWebDavLocationClick = ::onWebDavLocationClick,
            onPasswordClick = onClick(::onPasswordClick),
            onEnableClick = ::onEnableClick,
            onRetentionChange = { maxSnapshots ->
                putConfig {
                    it.copy(
                        retention = it.retention.copy(
                            maxSnapshots = maxSnapshots,
                        ),
                    )
                }
            },
            onRunNow = {
                runBackupNow()
                    .launchIn(appScope)
            },
            onDisableClick = onClick(::onDisableClick),
        )
    }
        .map { state ->
            Loadable.Ok(state)
        }
        .stateIn(screenScope)
}

private fun BackupConfig.sanitized(): BackupConfig = copy(
    store = store.sanitized(),
    retention = BackupRetention(
        maxSnapshots = retention.maxSnapshots.sanitizedRetentionMaxSnapshots(),
    ),
)

private fun BackupStoreConfig.sanitized(): BackupStoreConfig = when (this) {
    is BackupStoreConfig.Local -> copy(
        path = path?.trim(),
    )

    is BackupStoreConfig.WebDav -> copy(
        url = url?.trim(),
        username = username?.trim()?.takeIf { it.isNotEmpty() },
        password = password?.takeIf { it.value.isNotEmpty() },
    )
}

private fun Int.sanitizedRetentionMaxSnapshots(): Int =
    if (this <= BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS) {
        BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS
    } else {
        coerceAtMost(BackupRetention.MAX_SNAPSHOTS_LIMIT)
    }
