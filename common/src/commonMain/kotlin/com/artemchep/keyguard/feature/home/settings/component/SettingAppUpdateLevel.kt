package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import com.artemchep.keyguard.common.usecase.PutAppUpdateLevel
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingAppUpdateLevelProvider(
    koinScope: Scope,
) = settingAppUpdateLevelProvider(
    getAppUpdateLevel = koinScope.get(),
    putAppUpdateLevel = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingAppUpdateLevelProvider(
    getAppUpdateLevel: GetAppUpdateLevel,
    putAppUpdateLevel: PutAppUpdateLevel,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAppUpdateLevel().map { level ->
    val dropdown = AppUpdateLevel.entries.map { entry ->
        FlatItemAction(
            id = "settings.appUpdateLevel.${entry.key}",
            title = TextHolder.Res(entry.title),
            selected = entry == level,
            onClick = {
                putAppUpdateLevel(entry)
                    .launchIn(windowCoroutineScope)
            },
        )
    }

    SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf("app", "update", "release", "version", "new", "notify", "notification"),
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            title = stringResource(Res.string.pref_item_app_update_level_title),
            text = stringResource(level.title),
            dropdown = dropdown,
        )
    }
}
