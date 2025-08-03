package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.BackupSettings
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_autofill_auto_save_source_title
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flow
import org.jetbrains.compose.resources.stringResource
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
    FlatItemLayoutExpressive(
        content = {
            FlatItemTextContent(
                title = {
                    Text("Backup settings")
                },
            )
        },
        leading = icon<RowScope>(Icons.Outlined.Backup),
        onClick = onClick,
    )
}
