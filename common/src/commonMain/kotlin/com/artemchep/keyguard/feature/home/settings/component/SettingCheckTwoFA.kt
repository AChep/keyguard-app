package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.ui.FlatItem
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
            Text("Check for two-factor authentication")
        },
        text = {
            val text = "Check for login items that support two-factor authentication."
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
