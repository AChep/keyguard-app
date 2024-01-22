package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.IconBox
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingConcealFieldsProvider(
    directDI: DirectDI,
) = settingConcealFieldsProvider(
    getConcealFields = directDI.instance(),
    putConcealFields = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingConcealFieldsProvider(
    getConcealFields: GetConcealFields,
    putConcealFields: PutConcealFields,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getConcealFields().map { concealFields ->
    val onCheckedChange = { shouldConcealFields: Boolean ->
        putConcealFields(shouldConcealFields)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "conceal",
            tokens = listOf(
                "conceal",
            ),
        ),
    ) {
        SettingScreenshotsFields(
            checked = concealFields,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingScreenshotsFields(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = {
            Crossfade(targetState = checked) {
                val imageVector =
                    if (it) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility
                IconBox(imageVector)
            }
        },
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
                text = stringResource(Res.strings.pref_item_conceal_fields_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.strings.pref_item_conceal_fields_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
