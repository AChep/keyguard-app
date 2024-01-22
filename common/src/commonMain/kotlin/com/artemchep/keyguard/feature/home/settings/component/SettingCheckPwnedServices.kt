package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.ui.FlatItem
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
            Text("Check for compromised websites")
        },
        text = {
            val text = "Check saved websites for recent security breaches."
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
