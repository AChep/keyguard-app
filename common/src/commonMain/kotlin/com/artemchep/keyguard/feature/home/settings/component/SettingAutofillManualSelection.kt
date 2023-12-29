package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillManualSelectionProvider(
    directDI: DirectDI,
) = settingAutofillManualSelectionProvider(
    getAutofillManualSelection = directDI.instance(),
    putAutofillManualSelection = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
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
    FlatItem(
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_manual_selection_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_manual_selection_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
