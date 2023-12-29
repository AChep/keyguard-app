package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAboutAppProvider(
    directDI: DirectDI,
) = settingAboutAppProvider(
    getAppVersion = directDI.instance(),
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
        title = stringResource(Res.strings.pref_item_app_version_title),
        text = appVersion,
    )
}
