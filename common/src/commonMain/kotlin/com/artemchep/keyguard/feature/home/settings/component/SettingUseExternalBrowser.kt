package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingUseExternalBrowserProvider(
    directDI: DirectDI,
) = settingUseExternalBrowserProvider(
    getUseExternalBrowser = directDI.instance(),
    putUseExternalBrowser = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingUseExternalBrowserProvider(
    getUseExternalBrowser: GetUseExternalBrowser,
    putUseExternalBrowser: PutUseExternalBrowser,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getUseExternalBrowser().map { useExtenalBrowser ->
    val onCheckedChange = { shouldUseExternalBrowser: Boolean ->
        putUseExternalBrowser(shouldUseExternalBrowser)
            .launchIn(windowCoroutineScope)
        Unit
    }

    if (CurrentPlatform is Platform.Mobile) {
        SettingIi(
            search = SettingIi.Search(
                group = "ux",
                tokens = listOf(
                    "browser",
                    "links",
                    "chrome",
                    "tabs",
                ),
            ),
        ) {
            SettingUseExternalBrowser(
                checked = useExtenalBrowser,
                onCheckedChange = onCheckedChange,
            )
        }
    } else {
        null
    }
}

@Composable
private fun SettingUseExternalBrowser(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.OpenInBrowser),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_open_links_in_external_browser_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
