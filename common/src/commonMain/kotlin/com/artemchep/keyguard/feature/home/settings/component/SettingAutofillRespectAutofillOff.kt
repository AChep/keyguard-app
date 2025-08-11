package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillRespectAutofillOffProvider(
    directDI: DirectDI,
) = settingAutofillRespectAutofillOffProvider(
    getAutofillRespectAutofillOff = directDI.instance(),
    putAutofillRespectAutofillOff = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillRespectAutofillOffProvider(
    getAutofillRespectAutofillOff: GetAutofillRespectAutofillOff,
    putAutofillRespectAutofillOff: PutAutofillRespectAutofillOff,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillRespectAutofillOff().map { respectAutofillOff ->
    val onCheckedChange = { shouldRespectAutofillOff: Boolean ->
        putAutofillRespectAutofillOff(shouldRespectAutofillOff)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "flag",
            ),
        ),
    ) {
        SettingAutofillRespectAutofillOff(
            checked = respectAutofillOff,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillRespectAutofillOff(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemLayoutExpressive(
        shapeState = LocalSettingItemShape.current,
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                Switch(
                    checked = checked,
                    enabled = onCheckedChange != null,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_respect_autofill_disabled_title),
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_respect_autofill_disabled_text),
                    )
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                    Text(
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        text = stringResource(Res.string.pref_item_autofill_respect_autofill_disabled_note),
                    )
                },
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
