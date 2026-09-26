package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

fun settingScreenDelay(
    koinScope: Scope,
) = settingScreenDelay(
    getDebugScreenDelay = koinScope.get(),
    putDebugScreenDelay = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
    LocalSettingPaneComponents.current.KgSwitch(
        title = "Delay screen loading",
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
