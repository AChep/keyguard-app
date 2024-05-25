package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillSaveRequestProvider(
    directDI: DirectDI,
) = settingAutofillSaveRequestProvider(
    getCanWrite = directDI.instance(),
    getAutofillSaveRequest = directDI.instance(),
    putAutofillSaveRequest = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillSaveRequestProvider(
    getCanWrite: GetCanWrite,
    getAutofillSaveRequest: GetAutofillSaveRequest,
    putAutofillSaveRequest: PutAutofillSaveRequest,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = run {
    val onCheckedChange = { shouldSaveRequest: Boolean ->
        putAutofillSaveRequest(shouldSaveRequest)
            .launchIn(windowCoroutineScope)
        Unit
    }

    combine(
        getCanWrite(),
        getAutofillSaveRequest(),
    ) { canWrite, saveRequest ->
        SettingIi(
            search = SettingIi.Search(
                group = "autofill",
                tokens = listOf(
                    "autofill",
                    "save",
                ),
            ),
        ) {
            SettingAutofillSaveRequest(
                checked = saveRequest,
                onCheckedChange = onCheckedChange.takeIf { canWrite },
            )
        }
    }
}

@Composable
private fun SettingAutofillSaveRequest(
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
            Text(
                text = stringResource(Res.string.pref_item_autofill_save_request_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.pref_item_autofill_save_request_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
