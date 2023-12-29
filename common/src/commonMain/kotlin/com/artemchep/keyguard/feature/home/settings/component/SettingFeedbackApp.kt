package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.feedback.FeedbackRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingFeedbackAppProvider(
    directDI: DirectDI,
) = settingFeedbackAppProvider()

fun settingFeedbackAppProvider(): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "contact",
                "feedback",
                "report",
                "issue",
                "bug",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingFeedbackAppItem(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = FeedbackRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
fun SettingFeedbackAppItem(
    onClick: (() -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Feedback),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_contact_us_title),
            )
        },
        onClick = onClick,
    )
}
