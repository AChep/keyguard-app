package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingCrashProvider(
    directDI: DirectDI,
): SettingComponent = run {
    val item = SettingIi {
        SettingCrash(
            onClick = {
                throw RuntimeException("Test crash.")
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingCrash(
    onClick: () -> Unit,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.BugReport,
        title = stringResource(Res.string.pref_item_crash_title),
        onClick = onClick,
    )
}
