package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCheckPwnedServicesProvider(
    koinScope: Scope,
) = settingCheckPwnedServicesProvider(
    getCheckPwnedServices = koinScope.get(),
    putCheckPwnedServices = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingCheckPwnedServicesProvider(
    getCheckPwnedServices: GetCheckPwnedServices,
    putCheckPwnedServices: PutCheckPwnedServices,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getCheckPwnedServices().map { checkPwnedServices ->
    val onCheckedChange = { shouldCheckPwnedServices: Boolean ->
        putCheckPwnedServices(shouldCheckPwnedServices)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi {
        SettingCheckPwnedServices(
            checked = checkPwnedServices,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingCheckPwnedServices(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_check_pwned_services_title),
        text = stringResource(Res.string.watchtower_item_vulnerable_accounts_text),
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
