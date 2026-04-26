package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingCheckPwnedPasswordsProvider(
    directDI: DirectDI,
) = settingCheckPwnedPasswordsProvider(
    getCheckPwnedPasswords = directDI.instance(),
    putCheckPwnedPasswords = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingCheckPwnedPasswordsProvider(
    getCheckPwnedPasswords: GetCheckPwnedPasswords,
    putCheckPwnedPasswords: PutCheckPwnedPasswords,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getCheckPwnedPasswords().map { checkPwnedPasswords ->
    val onCheckedChange = { shouldCheckPwnedPasswords: Boolean ->
        putCheckPwnedPasswords(shouldCheckPwnedPasswords)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi {
        SettingCheckPwnedPasswords(
            checked = checkPwnedPasswords,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingCheckPwnedPasswords(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_check_pwned_passwords_title),
        text = stringResource(Res.string.watchtower_item_pwned_passwords_text),
        footer = {
            PoweredByHaveibeenpwned(
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
