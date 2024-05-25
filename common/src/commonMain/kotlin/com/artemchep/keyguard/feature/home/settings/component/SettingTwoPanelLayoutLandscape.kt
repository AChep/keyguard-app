package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingTwoPanelLayoutLandscapeProvider(
    directDI: DirectDI,
) = settingTwoPanelLayoutLandscapeProvider(
    getAllowTwoPanelLayoutInLandscape = directDI.instance(),
    putAllowTwoPanelLayoutInLandscape = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingTwoPanelLayoutLandscapeProvider(
    getAllowTwoPanelLayoutInLandscape: GetAllowTwoPanelLayoutInLandscape,
    putAllowTwoPanelLayoutInLandscape: PutAllowTwoPanelLayoutInLandscape,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAllowTwoPanelLayoutInLandscape().map { allow ->
    val onCheckedChange = { shouldAllow: Boolean ->
        putAllowTwoPanelLayoutInLandscape(shouldAllow)
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
                "landscape",
            ),
        ),
    ) {
        SettingTwoPanelLayoutLandscape(
            checked = allow,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingTwoPanelLayoutLandscape(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Tablet),
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
                text = stringResource(Res.string.pref_item_allow_two_panel_layout_in_landscape_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
