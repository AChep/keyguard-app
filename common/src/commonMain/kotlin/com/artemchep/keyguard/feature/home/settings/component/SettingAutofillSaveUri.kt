package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillSaveUriProvider(
    directDI: DirectDI,
) = settingAutofillSaveUriProvider(
    getCanWrite = directDI.instance(),
    getAutofillSaveUri = directDI.instance(),
    putAutofillSaveUri = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
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
    FlatItemLayoutExpressive(
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_auto_save_source_title),
                    )
                },
            )
        },
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
        onClick = onCheckedChange?.partially1(!checked),
    )
}
