package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.collections.map

fun settingAutofillDefaultMatchDetectionProvider(
    directDI: DirectDI,
) = settingAutofillDefaultMatchDetectionProvider(
    getAutofillDefaultMatchDetection = directDI.instance(),
    putAutofillDefaultMatchDetection = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillDefaultMatchDetectionProvider(
    getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    putAutofillDefaultMatchDetection: PutAutofillDefaultMatchDetection,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillDefaultMatchDetection().map { matchDetection ->
    val text = matchDetection.titleH()
    val variants = DSecret.Uri.MatchType.entries
    val dropdown = variants
        .map { entry ->
            val actionSelected = entry == matchDetection
            val actionTitleRes = entry.titleH()
            FlatItemAction(
                title = TextHolder.Res(actionTitleRes),
                selected = actionSelected,
                onClick = {
                    putAutofillDefaultMatchDetection(entry)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "match",
                "detection",
                "uri",
            ),
        ),
    ) {
        SettingAutofillDefaultMatchDetection(
            text = text,
            dropdown = dropdown,
        )
    }
}

@Composable
private fun SettingAutofillDefaultMatchDetection(
    text: StringResource,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.Domain),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_default_match_detection_title),
                    )
                },
                text = {
                    Text(
                        text = stringResource(text),
                    )
                },
            )
        },
        dropdown = dropdown,
    )
}
