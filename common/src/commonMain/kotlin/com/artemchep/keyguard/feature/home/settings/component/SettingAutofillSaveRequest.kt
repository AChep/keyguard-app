package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAutofillSaveRequestProvider(
    koinScope: Scope,
) = settingAutofillSaveRequestProvider(
    getCanWrite = koinScope.get(),
    getAutofillSaveRequest = koinScope.get(),
    putAutofillSaveRequest = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_autofill_save_request_title),
        text = stringResource(Res.string.pref_item_autofill_save_request_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
