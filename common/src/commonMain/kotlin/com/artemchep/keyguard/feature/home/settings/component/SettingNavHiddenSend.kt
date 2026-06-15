package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetNavForceHiddenSend
import com.artemchep.keyguard.common.usecase.PutNavForceHiddenSend
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingNavHiddenSendProvider(
    directDI: DirectDI,
) = settingNavHiddenSendProvider(
    getNavForceHiddenSend = directDI.instance(),
    putNavForceHiddenSend = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingNavHiddenSendProvider(
    getNavForceHiddenSend: GetNavForceHiddenSend,
    putNavForceHiddenSend: PutNavForceHiddenSend,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getNavForceHiddenSend().map { navHiddenSend ->
    val onCheckedChange = { shouldNavHiddenSend: Boolean ->
        putNavForceHiddenSend(shouldNavHiddenSend)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "navigation",
                "send",
            ),
        ),
    ) {
        SettingNavHiddenSend(
            checked = navHiddenSend,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingNavHiddenSend(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.AutoMirrored.Outlined.Send,
        title = stringResource(Res.string.pref_item_nav_hidden_send_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
