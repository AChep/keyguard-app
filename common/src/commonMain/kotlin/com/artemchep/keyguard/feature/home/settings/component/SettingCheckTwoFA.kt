package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.poweredby.PoweredBy2factorauth
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingCheckTwoFAProvider(
    directDI: DirectDI,
) = settingCheckTwoFAProvider(
    getCheckTwoFA = directDI.instance(),
    putCheckTwoFA = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingCheckTwoFAProvider(
    getCheckTwoFA: GetCheckTwoFA,
    putCheckTwoFA: PutCheckTwoFA,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getCheckTwoFA().map { checkTwoFA ->
    val onCheckedChange = { shouldCheckTwoFA: Boolean ->
        putCheckTwoFA(shouldCheckTwoFA)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi {
        SettingCheckTwoFA(
            checked = checkTwoFA,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingCheckTwoFA(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_check_inactive_2fa_title),
        text = stringResource(Res.string.watchtower_item_inactive_2fa_text),
        footer = {
            PoweredBy2factorauth(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.contentPadding,
                        vertical = 4.dp,
                    ),
            )
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
