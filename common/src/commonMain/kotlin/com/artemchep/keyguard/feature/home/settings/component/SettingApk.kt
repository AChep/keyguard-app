package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
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
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.FileDownload),
        title = {
            Text(
                text = stringResource(Res.string.pref_item_download_apk_title),
            )
        },
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
