package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.sanitized
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.currentKoinScope

@Composable
fun produceAutomaticBackupsSettingsScreenState(): Loadable<AutomaticBackupsSettingsState> =
    with(currentKoinScope()) {
        produceAutomaticBackupsSettingsScreenState(
            backupConfigRepository = get(),
            runBackupNow = get(),
        )
    }

@Composable
fun produceAutomaticBackupsSettingsScreenState(
    backupConfigRepository: BackupConfigRepository,
    runBackupNow: RunBackupNow,
): Loadable<AutomaticBackupsSettingsState> = produceScreenState(
    key = "settings_automatic_backups",
    initial = Loadable.Loading,
    args = arrayOf(
        backupConfigRepository,
        runBackupNow,
    ),
) {
    automaticBackupsSettingsStateProducer(
        backupConfigRepository = backupConfigRepository,
        runBackupNow = runBackupNow,
    )
}

suspend fun RememberStateFlowScope.automaticBackupsSettingsStateProducer(
    backupConfigRepository: BackupConfigRepository,
    runBackupNow: RunBackupNow,
): Flow<Loadable<AutomaticBackupsSettingsState>> {
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

    suspend fun confirmDisable() {
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    title = translate(Res.string.pref_item_automatic_backups_disable_title),
                    message = translate(Res.string.pref_item_automatic_backups_disable_message),
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                backupConfigRepository
                    .setConfig(BackupConfig())
                    .launchIn(appScope)
            }
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    // Created once so status ticks during a run do not change callback identity.
    val onSetupClick = {
        navigate(NavigationIntent.NavigateToRoute(AutomaticBackupsSetupRoute))
    }
    val onRetentionChange = { maxSnapshots: Int ->
        putConfig {
            it.copy(
                retention = it.retention.copy(
                    maxSnapshots = maxSnapshots,
                ),
            )
        }
    }
    val onRunNow: () -> Unit = {
        runBackupNow()
            .launchIn(appScope)
    }
    val onDisableClick = onClick(::confirmDisable)
    return combine(
        backupConfigRepository.getConfig(),
        backupConfigRepository.getStatus(),
    ) { config, status ->
        AutomaticBackupsSettingsState(
            config = config.sanitized(),
            status = status,
            onSetupClick = onSetupClick,
            onRetentionChange = onRetentionChange,
            onRunNow = onRunNow,
            onDisableClick = onDisableClick,
        )
    }
        .map { state ->
            Loadable.Ok(state)
        }
        .stateIn(screenScope)
}
