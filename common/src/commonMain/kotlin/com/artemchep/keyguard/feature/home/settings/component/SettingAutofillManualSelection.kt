package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAutofillManualSelectionProvider(
    koinScope: Scope,
) = settingAutofillManualSelectionProvider(
    getAutofillManualSelection = koinScope.get(),
    putAutofillManualSelection = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingAutofillManualSelectionProvider(
    getAutofillManualSelection: GetAutofillManualSelection,
    putAutofillManualSelection: PutAutofillManualSelection,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillManualSelection().map { manualSelection ->
    val onCheckedChange = { shouldManualSelection: Boolean ->
        putAutofillManualSelection(shouldManualSelection)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "manual",
            ),
        ),
    ) {
        SettingAutofillManualSelection(
            checked = manualSelection,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillManualSelection(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_autofill_manual_selection_title),
        text = stringResource(Res.string.pref_item_autofill_manual_selection_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
