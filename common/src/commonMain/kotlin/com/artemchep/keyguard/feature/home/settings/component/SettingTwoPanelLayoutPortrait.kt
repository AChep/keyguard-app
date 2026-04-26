package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingTwoPanelLayoutPortraitProvider(
    directDI: DirectDI,
) = settingTwoPanelLayoutPortraitProvider(
    getAllowTwoPanelLayoutInPortrait = directDI.instance(),
    putAllowTwoPanelLayoutInPortrait = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingTwoPanelLayoutPortraitProvider(
    getAllowTwoPanelLayoutInPortrait: GetAllowTwoPanelLayoutInPortrait,
    putAllowTwoPanelLayoutInPortrait: PutAllowTwoPanelLayoutInPortrait,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAllowTwoPanelLayoutInPortrait().map { allow ->
    // Screen size is too small for the feature
    // to work properly.
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val onCheckedChange = { shouldAllow: Boolean ->
        putAllowTwoPanelLayoutInPortrait(shouldAllow)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "layout",
            tokens = listOf(
                "layout",
                "two",
                "panel",
                "portrait",
            ),
        ),
    ) {
        SettingTwoPanelLayoutPortrait(
            checked = allow,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingTwoPanelLayoutPortrait(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.PhoneAndroid,
        title = stringResource(Res.string.pref_item_allow_two_panel_layout_in_portrait_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
