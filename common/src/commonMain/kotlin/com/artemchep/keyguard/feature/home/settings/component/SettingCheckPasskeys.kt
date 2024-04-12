package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.poweredby.PoweredByPasskeys
import com.artemchep.keyguard.ui.theme.Dimens
import dev.icerock.moko.resources.compose.stringResource
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
    Column {
        FlatItem(
            trailing = {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentEnforcement provides false,
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
                    text = stringResource(Res.strings.pref_item_check_inactive_passkeys_title),
                )
            },
            text = {
                val text = stringResource(Res.strings.watchtower_item_inactive_passkey_text)
                Text(text)
            },
            onClick = onCheckedChange?.partially1(!checked),
        )
        PoweredByPasskeys(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
}
