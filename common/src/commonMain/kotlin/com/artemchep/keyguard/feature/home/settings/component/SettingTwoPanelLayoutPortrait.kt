package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
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
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.PhoneAndroid),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_allow_two_panel_layout_in_portrait_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
