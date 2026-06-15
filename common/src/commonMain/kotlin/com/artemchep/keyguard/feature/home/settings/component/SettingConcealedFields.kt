package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
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
    val icon = if (checked) {
        Icons.Outlined.VisibilityOff
    } else Icons.Outlined.Visibility
    LocalSettingPaneComponents.current.KgSwitch(
        icon = icon,
        title = stringResource(Res.string.pref_item_conceal_fields_title),
        text = stringResource(Res.string.pref_item_conceal_fields_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
