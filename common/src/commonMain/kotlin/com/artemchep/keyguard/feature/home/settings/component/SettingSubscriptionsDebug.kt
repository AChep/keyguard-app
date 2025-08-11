package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.FlatItem
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
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
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
            Text("Force premium status")
        },
        text = {
            val msg = "Enables premium features in test builds."
            Text(msg)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
