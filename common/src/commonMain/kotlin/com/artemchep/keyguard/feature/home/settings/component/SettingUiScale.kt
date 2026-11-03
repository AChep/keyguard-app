package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.UI_SCALE_MAX
import com.artemchep.keyguard.common.model.UI_SCALE_MIN
import com.artemchep.keyguard.common.model.formatUiScale
import com.artemchep.keyguard.common.model.uiScaleToStep
import com.artemchep.keyguard.common.usecase.GetUiScale
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.uiscale.UiScaleRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_ui_scale_title
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingUiScaleProvider(
    koinScope: Scope,
) = settingUiScaleProvider(
    getUiScale = koinScope.get(),
)

fun settingUiScaleProvider(
    getUiScale: GetUiScale,
): SettingComponent {
    // Search consumes these providers directly, without the pane's platform filter.
    if (CurrentPlatform !is Platform.Desktop) return flowOf(null)

    return getUiScale().map { uiScale ->
        val step = uiScaleToStep(uiScale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX))
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
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingUiScale(
                text = formatUiScale(step),
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(UiScaleRoute)
                    navigationController.queue(intent)
                },
            )
        }
    }
}

@Composable
private fun SettingUiScale(
    text: String,
    onClick: () -> Unit,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.ZoomIn,
        title = stringResource(Res.string.pref_item_ui_scale_title),
        text = text,
        onClick = onClick,
    )
}
