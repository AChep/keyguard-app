package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.experimental.ExperimentalSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
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
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Biotech,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_experimental_title),
        onClick = onClick,
    )
}
