package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillPasskeysEnabledProvider(
    directDI: DirectDI,
) = settingAutofillPasskeysEnabledProvider(
    getAutofillPasskeysEnabled = directDI.instance(),
    putAutofillPasskeysEnabled = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillPasskeysEnabledProvider(
    getAutofillPasskeysEnabled: GetAutofillPasskeysEnabled,
    putAutofillPasskeysEnabled: PutAutofillPasskeysEnabled,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillPasskeysEnabled().map { passkeysEnabled ->
    val onCheckedChange = { shouldPasskeysEnabled: Boolean ->
        putAutofillPasskeysEnabled(shouldPasskeysEnabled)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "passkeys",
                "enable",
            ),
        ),
    ) {
        SettingAutofillPasskeysEnabled(
            checked = passkeysEnabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillPasskeysEnabled(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = null,
        title = stringResource(Res.string.pref_item_autofill_passkeys_enabled_title),
        text = stringResource(Res.string.pref_item_autofill_passkeys_enabled_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
