package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.PutNavLabel
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

fun settingNavLabelProvider(
    directDI: DirectDI,
) = settingNavLabelProvider(
    getNavLabel = directDI.instance(),
    putNavLabel = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingNavLabelProvider(
    getNavLabel: GetNavLabel,
    putNavLabel: PutNavLabel,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getNavLabel().map { navLabel ->
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val onCheckedChange = { shouldNavLabel: Boolean ->
        putNavLabel(shouldNavLabel)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "navigation",
                "label",
            ),
        ),
    ) {
        SettingNavLabel(
            checked = navLabel,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingNavLabel(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.AutoMirrored.Outlined.Label,
        title = stringResource(Res.string.pref_item_nav_label_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
