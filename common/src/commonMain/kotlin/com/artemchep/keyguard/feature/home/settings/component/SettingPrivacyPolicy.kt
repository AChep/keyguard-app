package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
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

fun settingPrivacyPolicyProvider(
    directDI: DirectDI,
): SettingComponent = settingPrivacyPolicyProvider()

fun settingPrivacyPolicyProvider(): SettingComponent = kotlin.run {
    // Do not render the field if there's nothing
    // to show its full content in.
    if (!CurrentPlatform.hasBrowser()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "privacy",
                "policy",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingPrivacyPolicy(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://gist.github.com/AChep/1fd4e019a4ad8f9647ba3b4694b5dc1c",
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingPrivacyPolicy(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.PrivacyTip,
        subIcon = Icons.Outlined.KeyguardWebsite,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_privacy_policy_title),
        onClick = onClick,
    )
}
