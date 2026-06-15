package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAboutAppBuildRefProvider(
    directDI: DirectDI,
) = settingAboutAppBuildRefProvider(
    getAppBuildRef = directDI.instance(),
)

fun settingAboutAppBuildRefProvider(
    getAppBuildRef: GetAppBuildRef,
): SettingComponent = getAppBuildRef()
    .map { buildRef ->
        if (buildRef.isBlank()) {
            return@map null
        }

        // composable
        SettingIi(
            search = SettingIi.Search(
                group = "about",
                tokens = listOf(
                    "about",
                    "app",
                    "build",
                    "ref",
                ),
            ),
        ) {
            SettingAboutAppBuildRef(
                buildRef = buildRef,
            )
        }
    }

@Composable
private fun SettingAboutAppBuildRef(
    buildRef: String,
) {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val hasBrowser = CurrentPlatform
        .hasBrowser()
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = stringResource(Res.string.pref_item_app_build_ref_title),
        text = buildRef,
        trailing = if (hasBrowser) {
            // composable
            {
                ChevronIcon()
            }
        } else {
            null
        },
        onClick = if (hasBrowser) {
            // lambda
            {
                val intent = run {
                    val url =
                        "https://github.com/AChep/keyguard-app/tree/$buildRef"
                    NavigationIntent.NavigateToBrowser(url)
                }
                controller.queue(intent)
            }
        } else {
            null
        },
    )
}
