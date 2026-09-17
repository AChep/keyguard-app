package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.PutAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAutofillPasswordsEnabledProvider(
    koinScope: Scope,
) = settingAutofillPasswordsEnabledProvider(
    getAutofillPasswordsEnabled = koinScope.get(),
    putAutofillPasswordsEnabled = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingAutofillPasswordsEnabledProvider(
    getAutofillPasswordsEnabled: GetAutofillPasswordsEnabled,
    putAutofillPasswordsEnabled: PutAutofillPasswordsEnabled,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillPasswordsEnabled().map { passwordsEnabled ->
    val onCheckedChange = { shouldPasswordsEnabled: Boolean ->
        putAutofillPasswordsEnabled(shouldPasswordsEnabled)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "passwords",
                "enable",
            ),
        ),
    ) {
        SettingAutofillPasswordsEnabled(
            checked = passwordsEnabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillPasswordsEnabled(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = null,
        title = stringResource(Res.string.pref_item_autofill_passwords_enabled_title),
        text = stringResource(Res.string.pref_item_autofill_passwords_enabled_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
