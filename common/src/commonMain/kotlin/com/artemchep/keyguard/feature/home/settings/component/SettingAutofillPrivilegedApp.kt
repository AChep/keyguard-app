package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillPrivilegedAppProvider(
    directDI: DirectDI,
) = settingAutofillPrivilegedAppProvider(
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillPrivilegedAppProvider(
    windowCoroutineScope: WindowCoroutineScope,
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
                    route = PrivilegedAppListRoute,
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
    FlatItemLayoutExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(main = Icons.Outlined.Apps, secondary = Icons.Outlined.KeyguardWebsite),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_privileged_apps_title),
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.pref_item_autofill_privileged_apps_text),
                    )
                },
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
