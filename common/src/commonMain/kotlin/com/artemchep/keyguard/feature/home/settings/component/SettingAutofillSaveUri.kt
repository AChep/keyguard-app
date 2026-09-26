package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAutofillSaveUriProvider(
    koinScope: Scope,
) = settingAutofillSaveUriProvider(
    getCanWrite = koinScope.get(),
    getAutofillSaveUri = koinScope.get(),
    putAutofillSaveUri = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingAutofillSaveUriProvider(
    getCanWrite: GetCanWrite,
    getAutofillSaveUri: GetAutofillSaveUri,
    putAutofillSaveUri: PutAutofillSaveUri,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = run {
    val onCheckedChange = { shouldSaveRequest: Boolean ->
        putAutofillSaveUri(shouldSaveRequest)
            .launchIn(windowCoroutineScope)
        Unit
    }

    combine(
        getCanWrite(),
        getAutofillSaveUri(),
    ) { canWrite, saveUri ->
        SettingIi(
            search = SettingIi.Search(
                group = "autofill",
                tokens = listOf(
                    "autofill",
                    "save",
                ),
            ),
        ) {
            SettingAutofillSaveUri(
                checked = saveUri,
                onCheckedChange = onCheckedChange.takeIf { canWrite },
            )
        }
    }
}

@Composable
private fun SettingAutofillSaveUri(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_autofill_auto_save_source_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
