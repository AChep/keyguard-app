package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAboutAppBuildDateProvider(
    koinScope: Scope,
) = settingAboutAppBuildDateProvider(
    getAppBuildDate = koinScope.get(),
)

fun settingAboutAppBuildDateProvider(
    getAppBuildDate: GetAppBuildDate,
): SettingComponent = getAppBuildDate()
    .map { buildDate ->
        // composable
        SettingIi(
            search = SettingIi.Search(
                group = "about",
                tokens = listOf(
                    "about",
                    "app",
                    "build",
                    "date",
                ),
            ),
        ) {
            SettingAboutAppBuildDate(
                buildDate = buildDate,
            )
        }
    }

@Composable
private fun SettingAboutAppBuildDate(
    buildDate: String,
) {
    SettingListItem(
        title = stringResource(Res.string.pref_item_app_build_date_title),
        text = buildDate,
    )
}
