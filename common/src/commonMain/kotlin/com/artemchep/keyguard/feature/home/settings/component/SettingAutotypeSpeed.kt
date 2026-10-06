package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AutotypeSpeed
import com.artemchep.keyguard.common.usecase.GetAutotypeSpeed
import com.artemchep.keyguard.common.usecase.PutAutotypeSpeed
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_autotype_speed_fast
import com.artemchep.keyguard.res.pref_item_autotype_speed_normal
import com.artemchep.keyguard.res.pref_item_autotype_speed_slow
import com.artemchep.keyguard.res.pref_item_autotype_speed_title
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAutotypeSpeedProvider(
    koinScope: Scope,
) = settingAutotypeSpeedProvider(
    getAutotypeSpeed = koinScope.get(),
    putAutotypeSpeed = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

private val platformClasses = listOf(
    Platform.Desktop.MacOS::class,
    Platform.Desktop.Windows::class,
)

fun settingAutotypeSpeedProvider(
    getAutotypeSpeed: GetAutotypeSpeed,
    putAutotypeSpeed: PutAutotypeSpeed,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent {
    // Search consumes these providers directly, without the pane's platform filter.
    if (platformClasses.none { it.isInstance(CurrentPlatform) }) return flowOf(null)

    return getAutotypeSpeed().map { speed ->
        val dropdown = AutotypeSpeed.entries.map { entry ->
            FlatItemAction(
                id = "settings.autotypeSpeed.${entry.storageKey}",
                title = TextHolder.Res(entry.title()),
                selected = entry == speed,
                onClick = {
                    putAutotypeSpeed(entry)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

        SettingIi(
            platformClasses = platformClasses,
            search = SettingIi.Search(
                group = "about",
                tokens = listOf("autotype", "auto-type", "typing", "keyboard", "speed", "delay"),
            ),
        ) {
            LocalSettingPaneComponents.current.KgPicker(
                icon = Icons.Outlined.Keyboard,
                title = stringResource(Res.string.pref_item_autotype_speed_title),
                text = stringResource(speed.title()),
                dropdown = dropdown,
            )
        }
    }
}

private fun AutotypeSpeed.title(): StringResource = when (this) {
    AutotypeSpeed.Fast -> Res.string.pref_item_autotype_speed_fast
    AutotypeSpeed.Normal -> Res.string.pref_item_autotype_speed_normal
    AutotypeSpeed.Slow -> Res.string.pref_item_autotype_speed_slow
}
