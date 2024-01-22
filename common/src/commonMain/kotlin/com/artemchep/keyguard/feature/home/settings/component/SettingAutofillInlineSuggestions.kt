package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillInlineSuggestionsProvider(
    directDI: DirectDI,
) = settingAutofillInlineSuggestionsProvider(
    getAutofillInlineSuggestions = directDI.instance(),
    putAutofillInlineSuggestions = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillInlineSuggestionsProvider(
    getAutofillInlineSuggestions: GetAutofillInlineSuggestions,
    putAutofillInlineSuggestions: PutAutofillInlineSuggestions,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillInlineSuggestions().map { inlineSuggestions ->
    val onCheckedChange = { shouldInlineSuggestions: Boolean ->
        putAutofillInlineSuggestions(shouldInlineSuggestions)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "suggestions",
            ),
        ),
    ) {
        SettingAutofillInlineSuggestions(
            checked = inlineSuggestions,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillInlineSuggestions(
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
                text = stringResource(Res.strings.pref_item_autofill_inline_suggestions_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_inline_suggestions_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
