package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgKeyserverAutoRefreshProvider(
    directDI: DirectDI,
) = settingGpgKeyserverAutoRefreshProvider(
    getGpgKeyserverAutoRefresh = directDI.instance(),
    getGpgKeyserverLastRefresh = directDI.instance(),
    putGpgKeyserverAutoRefresh = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGpgKeyserverAutoRefreshProvider(
    getGpgKeyserverAutoRefresh: GetGpgKeyserverAutoRefresh,
    getGpgKeyserverLastRefresh: GetGpgKeyserverLastRefresh,
    putGpgKeyserverAutoRefresh: PutGpgKeyserverAutoRefresh,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getGpgKeyserverAutoRefresh(),
    getGpgKeyserverLastRefresh(),
) { autoRefresh, _ ->
    val onCheckedChange = { shouldAutoRefresh: Boolean ->
        putGpgKeyserverAutoRefresh(shouldAutoRefresh)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
            Platform.Desktop.Other::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "keyserver",
                "refresh",
                "auto",
                "openpgp",
            ),
        ),
    ) {
        SettingGpgKeyserverAutoRefresh(
            checked = autoRefresh,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingGpgKeyserverAutoRefresh(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Sync,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_gpg_keyserver_auto_refresh_title),
            )
        },
        text = {
            Column {
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    text = stringResource(Res.string.pref_item_gpg_keyserver_auto_refresh_note),
                )
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
