package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.FlatItem
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingScreenDelay(
    directDI: DirectDI,
) = settingScreenDelay(
    getDebugScreenDelay = directDI.instance(),
    putDebugScreenDelay = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingScreenDelay(
    getDebugScreenDelay: GetDebugScreenDelay,
    putDebugScreenDelay: PutDebugScreenDelay,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getDebugScreenDelay().map { screenDelay ->
    val onCheckedChange = { shouldBeScreenDelay: Boolean ->
        putDebugScreenDelay(shouldBeScreenDelay)
            .launchIn(windowCoroutineScope)
        Unit
    }

    if (!isRelease) {
        SettingIi {
            SettingDebugScreenDelay(
                checked = screenDelay,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        null
    }
}

@Composable
private fun SettingDebugScreenDelay(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemSimpleExpressive(
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                Switch(
                    checked = checked,
                    enabled = onCheckedChange != null,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
        title = {
            Text("Delay screen loading")
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
