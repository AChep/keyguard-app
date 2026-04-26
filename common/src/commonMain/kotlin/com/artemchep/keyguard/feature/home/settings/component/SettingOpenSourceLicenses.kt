package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.license.LicenseRouteFactory
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingOpenSourceLicensesProvider(
    directDI: DirectDI,
) = settingOpenSourceLicensesProvider(
    licenseRouteFactory = directDI.instance(),
)

fun settingOpenSourceLicensesProvider(
    licenseRouteFactory: LicenseRouteFactory,
): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "code",
                "license",
                "open",
                "source",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingOpenSourceLicenses(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    licenseRouteFactory.create(),
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingOpenSourceLicenses(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.AutoMirrored.Outlined.TextSnippet,
        title = stringResource(Res.string.pref_item_open_source_licenses_title),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
