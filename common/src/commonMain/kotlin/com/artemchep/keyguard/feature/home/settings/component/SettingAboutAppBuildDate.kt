package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAboutAppBuildDateProvider(
    directDI: DirectDI,
) = settingAboutAppBuildDateProvider(
    getAppBuildDate = directDI.instance(),
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
        title = stringResource(Res.strings.pref_item_app_build_date_title),
        text = buildDate,
    )
}
