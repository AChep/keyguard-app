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
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingNavLabelProvider(
    koinScope: Scope,
) = settingNavLabelProvider(
    getNavLabel = koinScope.get(),
    putNavLabel = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
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
