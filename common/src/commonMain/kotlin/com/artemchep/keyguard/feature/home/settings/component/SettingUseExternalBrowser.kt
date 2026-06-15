package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
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
): SettingComponent = getUseExternalBrowser().map { useExternalBrowser ->    // Screen size is too small for the feature
    if (!CurrentPlatform.hasBrowser()) {
        return@map null
    }

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
                checked = useExternalBrowser,
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
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.OpenInBrowser,
        title = stringResource(Res.string.pref_item_open_links_in_external_browser_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
