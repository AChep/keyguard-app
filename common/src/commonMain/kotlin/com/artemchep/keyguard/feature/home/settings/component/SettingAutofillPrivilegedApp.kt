package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRouteFactory
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillPrivilegedAppProvider(
    directDI: DirectDI,
) = settingAutofillPrivilegedAppProvider(
    privilegedAppListRouteFactory = directDI.instance(),
)

fun settingAutofillPrivilegedAppProvider(
    privilegedAppListRouteFactory: PrivilegedAppListRouteFactory,
): SettingComponent = run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "save",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingAutofillPrivilegedApp(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = privilegedAppListRouteFactory.create(),
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingAutofillPrivilegedApp(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Apps,
        subIcon = Icons.Outlined.KeyguardWebsite,
        title = stringResource(Res.string.pref_item_autofill_privileged_apps_title),
        text = stringResource(Res.string.pref_item_autofill_privileged_apps_text),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
