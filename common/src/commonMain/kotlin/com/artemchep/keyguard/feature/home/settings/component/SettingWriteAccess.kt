package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.ui.icons.Stub
import kotlinx.coroutines.flow.combine
import org.koin.core.scope.Scope

fun settingWriteAccessProvider(
    koinScope: Scope,
) = settingWriteAccessProvider(
    getWriteAccess = koinScope.get(),
    putWriteAccess = koinScope.get(),
    getPurchased = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Stub,
        title = "Save edited changes",
        text = "May cause a partial data loss, be careful!",
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
