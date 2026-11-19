package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.browser.BrowserExtensionSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_pairings_count_plural
import com.artemchep.keyguard.res.browser_extension_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingBrowserExtensionSettingsProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionSettingsProvider(
        browserExtensionService = koinScope.get(),
    )
}

fun settingBrowserExtensionSettingsProvider(
    browserExtensionService: BrowserExtensionService,
): SettingComponent = browserExtensionService.state
    // Builds without the CLI agent, which relays the extensions, do not offer it.
    .map { state -> state.pairings.size.takeIf { state.supported } }
    .distinctUntilChanged()
    .map { pairings ->
        if (pairings == null) {
            return@map null
        }

        SettingIi(
            platformClasses = browserExtensionPlatformClasses,
            search = SettingIi.Search(
                group = "autofill",
                tokens = browserExtensionSearchTokens,
            ),
        ) {
            val navigation by rememberUpdatedState(LocalNavigationController.current)
            LocalSettingPaneComponents.current.KgAction(
                icon = Icons.Outlined.Extension,
                title = {
                    Text(stringResource(Res.string.browser_extension_title))
                },
                text = {
                    Text(
                        pluralStringResource(
                            Res.plurals.browser_extension_pairings_count_plural,
                            pairings,
                            pairings,
                        ),
                    )
                },
                trailing = { ChevronIcon() },
                onClick = {
                    navigation.queue(NavigationIntent.NavigateToRoute(BrowserExtensionSettingsRoute))
                },
            )
        }
    }
