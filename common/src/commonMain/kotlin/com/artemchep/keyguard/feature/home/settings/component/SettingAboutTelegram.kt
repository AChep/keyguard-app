package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

fun settingAboutTelegramProvider(
    directDI: DirectDI,
) = settingAboutTelegramProvider()

fun settingAboutTelegramProvider(): SettingComponent = kotlin.run {
    // Do not render the field if there's nothing
    // to show its full content in.
    if (!CurrentPlatform.hasBrowser()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "about",
                "reddit",
                "forum",
                "discussion",
                "feedback",
                "report",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingAboutTelegram(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://www.reddit.com/r/keyguard/",
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingAboutTelegram(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Forum,
        subIcon = Icons.Outlined.KeyguardWebsite,
        title = stringResource(Res.string.pref_item_reddit_community_title),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
