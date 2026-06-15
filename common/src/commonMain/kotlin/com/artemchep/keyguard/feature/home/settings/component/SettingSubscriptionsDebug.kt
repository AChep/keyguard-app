package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSubscriptionsDebug(
    directDI: DirectDI,
) = settingSubscriptionsDebug(
    getDebugPremium = directDI.instance(),
    putDebugPremium = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingSubscriptionsDebug(
    getDebugPremium: GetDebugPremium,
    putDebugPremium: PutDebugPremium,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getDebugPremium().map { premium ->
    val onCheckedChange = { shouldBePremium: Boolean ->
        putDebugPremium(shouldBePremium)
            .launchIn(windowCoroutineScope)
        Unit
    }

    if (!isRelease) {
        SettingIi {
            SettingDebugPremium(
                checked = premium,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        null
    }
}

@Composable
private fun SettingDebugPremium(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = "Force premium status",
        text = "Enables premium features in test builds.",
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
