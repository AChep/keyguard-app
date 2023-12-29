package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingLocalizationProvider(
    directDI: DirectDI,
): SettingComponent = settingLocalizationProvider()

fun settingLocalizationProvider(): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "localization",
                "language",
                "translate",
                "crowdin",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingLocalization(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://crowdin.com/project/keyguard",
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingLocalization(
    onClick: (() -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Translate, Icons.Outlined.KeyguardWebsite),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_crowdin_title),
            )
        },
        onClick = onClick,
    )
}
