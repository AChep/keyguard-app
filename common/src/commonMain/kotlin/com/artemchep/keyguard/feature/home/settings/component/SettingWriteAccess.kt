package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.ui.FlatItem
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingWriteAccessProvider(
    directDI: DirectDI,
) = settingWriteAccessProvider(
    getWriteAccess = directDI.instance(),
    putWriteAccess = directDI.instance(),
    getPurchased = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingWriteAccessProvider(
    getWriteAccess: GetWriteAccess,
    putWriteAccess: PutWriteAccess,
    getPurchased: GetPurchased,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getPurchased(),
    getWriteAccess(),
) { purchased, writeAccess ->
    val onCheckedChange = { shouldHaveWriteAccess: Boolean ->
        putWriteAccess(shouldHaveWriteAccess)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi {
        SettingWriteAccess(
            checked = writeAccess,
            onCheckedChange = onCheckedChange.takeIf { purchased },
        )
    }
}

@Composable
private fun SettingWriteAccess(
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
            Text("Save edited changes")
        },
        text = {
            Text("May cause a partial data loss, be careful!")
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
