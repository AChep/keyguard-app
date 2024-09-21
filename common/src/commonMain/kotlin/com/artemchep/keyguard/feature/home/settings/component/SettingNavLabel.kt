package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.PutNavLabel
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
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
    FlatItem(
        leading = icon<RowScope>(Icons.AutoMirrored.Outlined.Label),
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
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
                text = stringResource(Res.string.pref_item_nav_label_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
