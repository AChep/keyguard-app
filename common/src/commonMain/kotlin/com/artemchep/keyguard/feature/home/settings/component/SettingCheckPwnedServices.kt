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
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingCheckPwnedServicesProvider(
    directDI: DirectDI,
) = settingCheckPwnedServicesProvider(
    getCheckPwnedServices = directDI.instance(),
    putCheckPwnedServices = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
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
                    text = stringResource(Res.string.pref_item_check_pwned_services_title),
                )
            },
            text = {
                val text = stringResource(Res.string.watchtower_item_vulnerable_accounts_text)
                Text(text)
            },
            onClick = onCheckedChange?.partially1(!checked),
        )
        PoweredByHaveibeenpwned(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
}
