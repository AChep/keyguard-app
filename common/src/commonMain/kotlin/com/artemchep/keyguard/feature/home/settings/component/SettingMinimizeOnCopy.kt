package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingMinimizeOnCopyProvider(
    directDI: DirectDI,
) = settingMinimizeOnCopyProvider(
    getMinimizeOnCopy = directDI.instance(),
    putMinimizeOnCopy = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingMinimizeOnCopyProvider(
    getMinimizeOnCopy: GetMinimizeOnCopy,
    putMinimizeOnCopy: PutMinimizeOnCopy,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getMinimizeOnCopy().map { minimizeOnCopy ->
    val onCheckedChange = { shouldMinimizeOnCopy: Boolean ->
        putMinimizeOnCopy(shouldMinimizeOnCopy)
            .launchIn(windowCoroutineScope)
        Unit
    }

    if (CurrentPlatform is Platform.Desktop) {
        SettingIi(
            search = SettingIi.Search(
                group = "ux",
                tokens = listOf(
                    "minimize",
                    "copy",
                    "clipboard",
                ),
            ),
        ) {
            SettingMinimizeOnCopy(
                checked = minimizeOnCopy,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        null
    }
}

@Composable
private fun SettingMinimizeOnCopy(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_minimize_on_copy_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
