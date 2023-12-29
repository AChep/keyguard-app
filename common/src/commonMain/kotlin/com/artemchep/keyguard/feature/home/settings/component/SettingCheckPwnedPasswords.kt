package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.ui.FlatItem
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
    FlatItem(
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text("Check for vulnerable passwords")
        },
        text = {
            val text = "Check saved passwords for recent security breaches."
            Text(text)
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
