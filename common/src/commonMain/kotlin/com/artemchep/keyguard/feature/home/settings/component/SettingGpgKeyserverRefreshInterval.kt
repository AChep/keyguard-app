package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshIntervalVariants
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgKeyserverRefreshIntervalProvider(
    directDI: DirectDI,
) = settingGpgKeyserverRefreshIntervalProvider(
    getGpgKeyserverRefreshInterval = directDI.instance(),
    getGpgKeyserverRefreshIntervalVariants = directDI.instance(),
    putGpgKeyserverRefreshInterval = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingGpgKeyserverRefreshIntervalProvider(
    getGpgKeyserverRefreshInterval: GetGpgKeyserverRefreshInterval,
    getGpgKeyserverRefreshIntervalVariants: GetGpgKeyserverRefreshIntervalVariants,
    putGpgKeyserverRefreshInterval: PutGpgKeyserverRefreshInterval,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getGpgKeyserverRefreshInterval(),
    getGpgKeyserverRefreshIntervalVariants(),
) { interval, variants ->
    val text = interval.format(context)
    val dropdown = variants
        .map { duration ->
            val actionTitle = duration.format(context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = duration == interval,
                onClick = {
                    putGpgKeyserverRefreshInterval(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
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
                "interval",
                "openpgp",
            ),
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            icon = Icons.Outlined.Timer,
            title = stringResource(Res.string.pref_item_gpg_keyserver_refresh_interval_title),
            text = text,
            dropdown = dropdown,
        )
    }
}
