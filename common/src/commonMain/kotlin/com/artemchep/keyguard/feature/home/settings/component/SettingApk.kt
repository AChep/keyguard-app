package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingApkProvider(
    directDI: DirectDI,
) = settingApkProvider(
    getPurchased = directDI.instance(),
)

fun settingApkProvider(
    getPurchased: GetPurchased,
): SettingComponent = getPurchased()
    .map { premium ->
        if (premium) {
            // composable
            SettingIi(
                search = SettingIi.Search(
                    group = "subscription",
                    tokens = listOf(
                        "apk",
                        "download",
                    ),
                ),
            ) {
                SettingApk()
            }
        } else {
            null
        }
    }

@Composable
private fun SettingApk() {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.FileDownload,
        title = stringResource(Res.string.pref_item_download_apk_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = run {
                val url =
                    "https://github.com/AChep/keyguard-app/releases"
                NavigationIntent.NavigateToBrowser(url)
            }
            controller.queue(intent)
        },
    )
}
