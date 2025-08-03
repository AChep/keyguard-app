package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
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
    FlatItemLayoutExpressive(
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_app_build_ref_title),
                    )
                },
                text = {
                    Text(buildRef)
                },
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = run {
                val url =
                    "https://github.com/AChep/keyguard-app/tree/$buildRef"
                NavigationIntent.NavigateToBrowser(url)
            }
            controller.queue(intent)
        },
    )
}
