package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshotsVariants
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.collections.map

fun settingScreenshotsProvider(
    directDI: DirectDI,
) = settingScreenshotsProvider(
    getAllowScreenshots = directDI.instance(),
    getAllowScreenshotsVariants = directDI.instance(),
    putAllowScreenshots = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingScreenshotsProvider(
    getAllowScreenshots: GetAllowScreenshots,
    getAllowScreenshotsVariants: GetAllowScreenshotsVariants,
    putAllowScreenshots: PutAllowScreenshots,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getAllowScreenshots(),
    getAllowScreenshotsVariants(),
) { allowScreenshots, variants ->
    val text = getAllowScreenshotsTitle(allowScreenshots, context)
    val dropdown = variants
        .map { variant ->
            val actionSelected = allowScreenshots == variant
            val actionTitle = getAllowScreenshotsTitle(variant, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putAllowScreenshots(variant)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClass = Platform.Mobile::class,
        search = SettingIi.Search(
            group = "conceal",
            tokens = listOf(
                "screenshot",
                "record",
                "block",
                "prevent",
            ),
        ),
    ) {
        SettingAllowScreenshots(
            text = text,
            checked = allowScreenshots >= AllowScreenshots.LIMITED,
            dropdown = dropdown,
        )
    }
}

private suspend fun getAllowScreenshotsTitle(allowScreenshots: AllowScreenshots, context: LeContext) = when (allowScreenshots) {
    AllowScreenshots.DISABLED -> textResource(
        Res.string.pref_item_allow_screenshots_mode_disabled_title,
        context,
    )
    AllowScreenshots.LIMITED -> textResource(
        Res.string.pref_item_allow_screenshots_mode_limited_title,
        context,
    )
    AllowScreenshots.FULL -> textResource(
        Res.string.pref_item_allow_screenshots_mode_full_title,
        context,
    )
}

@Composable
private fun SettingAllowScreenshots(
    text: String,
    checked: Boolean,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.Screenshot),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_allow_screenshots_title),
                    )
                },
                text = {
                    Text(text)
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                    val text = if (checked) {
                        stringResource(Res.string.pref_item_allow_screenshots_text_on)
                    } else {
                        stringResource(Res.string.pref_item_allow_screenshots_text_off)
                    }
                    Text(
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        text = text,
                    )
                },
            )
        },
        dropdown = dropdown,
    )
}
