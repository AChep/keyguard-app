package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAboutAppProvider(
    koinScope: Scope,
) = settingAboutAppProvider(
    getAppVersion = koinScope.get(),
)

fun settingAboutAppProvider(
    getAppVersion: GetAppVersion,
): SettingComponent = getAppVersion()
    .map { appVersion ->
        // composable
        SettingIi(
            search = SettingIi.Search(
                group = "about",
                tokens = listOf(
                    "about",
                    "app",
                ),
            ),
        ) {
            SettingAboutApp(
                appVersion = appVersion,
            )
        }
    }

@Composable
private fun SettingAboutApp(
    appVersion: String,
) {
    SettingListItem(
        title = stringResource(Res.string.pref_item_app_version_title),
        text = appVersion,
    )
}
