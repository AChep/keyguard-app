package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.poweredby.PoweredBy2factorauth
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
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
            Text(
                text = stringResource(Res.string.pref_item_check_inactive_2fa_title),
            )
        },
        text = {
            val text = stringResource(Res.string.watchtower_item_inactive_2fa_text)
            Text(text)
        },
        footer = {
            PoweredBy2factorauth(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.contentPadding,
                        vertical = 4.dp,
                    ),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
