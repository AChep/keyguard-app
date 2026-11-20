package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.info
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAboutAppProvider(
    koinScope: Scope,
) = settingAboutAppProvider(
    getAppVersion = koinScope.get(),
    getAppUpdate = koinScope.get(),
)

fun settingAboutAppProvider(
    getAppVersion: GetAppVersion,
    getAppUpdate: GetAppUpdate,
): SettingComponent = combine(
    getAppVersion(),
    getAppUpdate(),
) { appVersion, appUpdate ->
    // composable
    SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "about",
                "app",
                "version",
                "update",
                "release",
            ),
        ),
    ) {
        SettingAboutApp(
            appVersion = appVersion,
            appUpdate = appUpdate,
        )
    }
}

@Composable
private fun SettingAboutApp(
    appVersion: String,
    appUpdate: DAppUpdate?,
) {
    val onClick = rememberAppUpdateOnClick(appUpdate)
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = {
            Text(stringResource(Res.string.pref_item_app_version_title))
        },
        text = {
            Column {
                Text(appVersion)
                if (appUpdate != null) {
                    Text(
                        text = stringResource(
                            Res.string.pref_item_app_version_update_text,
                            appUpdate.version,
                        ),
                        color = MaterialTheme.colorScheme.info,
                    )
                }
            }
        },
        trailing = if (onClick != null) {
            // composable
            {
                ChevronIcon()
            }
        } else {
            null
        },
        onClick = onClick,
    )
}

/**
 * Opens the link of the [update]: the release page, or the store
 * listing on store builds. `null` if there is no update or no link
 * the app can open, see [DAppUpdate.url].
 */
@Composable
internal fun rememberAppUpdateOnClick(
    update: DAppUpdate?,
): (() -> Unit)? {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val url = update?.url
    return remember(url) {
        url?.let {
            // lambda
            {
                val intent = NavigationIntent.NavigateToBrowser(url)
                controller.queue(intent)
            }
        }
    }
}
