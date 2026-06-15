package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.BackupSettings
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingBackupSettings(
    directDI: DirectDI,
) = settingBackupSettings(
    backupSettings = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingBackupSettings(
    backupSettings: BackupSettings,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    val onClick = {
        backupSettings()
            .launchIn(windowCoroutineScope)
        Unit
    }

    val state = if (!isRelease) {
        SettingIi {
            SettingBackupSettings(
                onClick = onClick,
            )
        }
    } else {
        null
    }
    emit(state)
}

@Composable
private fun SettingBackupSettings(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Backup,
        title = "Backup settings",
        onClick = onClick,
    )
}
