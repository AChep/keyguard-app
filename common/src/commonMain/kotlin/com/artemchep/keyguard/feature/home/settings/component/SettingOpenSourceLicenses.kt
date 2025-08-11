package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.license.LicenseRoute
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

fun settingOpenSourceLicensesProvider(
    directDI: DirectDI,
): SettingComponent = settingOpenSourceLicensesProvider()

fun settingOpenSourceLicensesProvider(): SettingComponent = kotlin.run {
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
                val intent = NavigationIntent.NavigateToRoute(LicenseRoute)
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
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.AutoMirrored.Outlined.TextSnippet),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_open_source_licenses_title),
            )
        },
        onClick = onClick,
    )
}
