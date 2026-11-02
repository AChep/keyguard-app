package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetUiScale
import com.artemchep.keyguard.common.usecase.PutUiScale
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_ui_scale_title
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope
import kotlin.math.roundToInt

/**
 * Available interface scale steps, in tenths: 0.5x to 2.0x
 * with 0.1x increments.
 */
private val uiScaleSteps = 5..20

fun settingUiScaleProvider(
    koinScope: Scope,
) = settingUiScaleProvider(
    getUiScale = koinScope.get(),
    putUiScale = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingUiScaleProvider(
    getUiScale: GetUiScale,
    putUiScale: PutUiScale,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent {
    // Search consumes these providers directly, without the pane's platform filter.
    if (CurrentPlatform !is Platform.Desktop) return flowOf(null)

    return getUiScale().map { uiScale ->
        val currentStep = (uiScale * 10f).roundToInt()
        val dropdown = uiScaleSteps.map { step ->
            FlatItemAction(
                id = "settings.uiScale.$step",
                title = TextHolder.Value(formatUiScale(step)),
                selected = step == currentStep,
                onClick = {
                    putUiScale(step / 10f)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

        SettingIi(
            search = SettingIi.Search(
                group = "layout",
                tokens = listOf(
                    "scale",
                    "zoom",
                    "density",
                    "size",
                ),
            ),
        ) {
            LocalSettingPaneComponents.current.KgPicker(
                icon = Icons.Outlined.ZoomIn,
                title = stringResource(Res.string.pref_item_ui_scale_title),
                text = formatUiScale(currentStep),
                dropdown = dropdown,
            )
        }
    }
}

private fun formatUiScale(step: Int): String {
    val whole = step / 10
    val fraction = step % 10
    return "$whole.${fraction}x"
}
