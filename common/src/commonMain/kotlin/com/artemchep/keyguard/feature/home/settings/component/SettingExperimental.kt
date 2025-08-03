package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.experimental.ExperimentalSettingsRoute
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingExperimentalProvider(
    directDI: DirectDI,
) = settingExperimentalProvider()

fun settingExperimentalProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingExperimental(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = ExperimentalSettingsRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingExperimental(
    onClick: (() -> Unit)?,
) {
    FlatItemSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.Biotech),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_experimental_title),
            )
        },
        onClick = onClick,
    )
}
