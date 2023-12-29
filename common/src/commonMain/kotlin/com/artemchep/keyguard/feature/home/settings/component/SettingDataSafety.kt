package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.datasafety.DataSafetyRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingDataSafetyProvider(
    directDI: DirectDI,
) = settingDataSafetyProvider()

fun settingDataSafetyProvider(): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "safety",
                "data",
                "how",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingPermissionDetails(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = DataSafetyRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingPermissionDetails(
    onClick: (() -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.PrivacyTip),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_data_safety_title),
            )
        },
        onClick = onClick,
    )
}
