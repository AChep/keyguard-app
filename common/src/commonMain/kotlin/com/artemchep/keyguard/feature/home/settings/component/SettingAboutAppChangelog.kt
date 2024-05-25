package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetVersionLog
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAboutAppChangelogProvider(
    directDI: DirectDI,
) = settingAboutAppChangelogProvider(
    getVersionLog = directDI.instance(),
)

fun settingAboutAppChangelogProvider(
    getVersionLog: GetVersionLog,
): SettingComponent = getVersionLog()
    .map { log ->
        if (log.size < 2) {
            return@map null
        }

        val newRef = log[0].ref
        val oldRef = log[1].ref

        // composable
        SettingIi(
            search = SettingIi.Search(
                group = "about",
                tokens = listOf(
                    "about",
                    "app",
                    "changelog",
                ),
            ),
        ) {
            SettingAboutAppChangelog(
                newRef = newRef,
                oldRef = oldRef,
            )
        }
    }

@Composable
private fun SettingAboutAppChangelog(
    newRef: String,
    oldRef: String,
) {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    FlatItem(
        title = {
            Text(
                text = stringResource(Res.string.pref_item_app_changelog_title),
            )
        },
        text = {
            Row {
                Text(newRef)
                Text("...")
                Text(oldRef)
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = run {
                val url =
                    "https://github.com/AChep/keyguard-app/compare/$oldRef...$newRef"
                NavigationIntent.NavigateToBrowser(url)
            }
            controller.queue(intent)
        },
    )
}
