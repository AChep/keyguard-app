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
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.poweredby.PoweredBy2factorauth
import com.artemchep.keyguard.ui.poweredby.PoweredByPasskeys
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingCheckPasskeysProvider(
    directDI: DirectDI,
) = settingCheckPasskeysProvider(
    getCheckPasskeys = directDI.instance(),
    putCheckPasskeys = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingCheckPasskeysProvider(
    getCheckPasskeys: GetCheckPasskeys,
    putCheckPasskeys: PutCheckPasskeys,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getCheckPasskeys().map { checkPasskeys ->
    val onCheckedChange = { shouldCheckPasskeys: Boolean ->
        putCheckPasskeys(shouldCheckPasskeys)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi {
        SettingCheckPasskeys(
            checked = checkPasskeys,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingCheckPasskeys(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemLayoutExpressive(
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_check_inactive_passkeys_title),
                    )
                },
                text = {
                    val text = stringResource(Res.string.watchtower_item_inactive_passkey_text)
                    Text(text)
                },
            )
        },
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
        footer = {
            PoweredByPasskeys(
                modifier = Modifier
                    .padding(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
