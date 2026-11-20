package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelay
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelayVariants
import com.artemchep.keyguard.common.usecase.PutAppUpdateDelay
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope
import kotlin.time.Duration

fun settingAppUpdateDelayProvider(
    koinScope: Scope,
) = settingAppUpdateDelayProvider(
    getAppUpdateDelay = koinScope.get(),
    getAppUpdateDelayVariants = koinScope.get(),
    putAppUpdateDelay = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
    context = koinScope.get(),
)

fun settingAppUpdateDelayProvider(
    getAppUpdateDelay: GetAppUpdateDelay,
    getAppUpdateDelayVariants: GetAppUpdateDelayVariants,
    putAppUpdateDelay: PutAppUpdateDelay,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getAppUpdateDelay(),
    getAppUpdateDelayVariants(),
) { delay, variants ->
    val text = formatAppUpdateDelay(delay, context)
    val dropdown = variants.map { duration ->
        val actionTitle = formatAppUpdateDelay(duration, context)
        FlatItemAction(
            id = "settings.appUpdateDelay.${duration.inWholeSeconds}",
            title = TextHolder.Value(actionTitle),
            selected = duration == delay,
            onClick = {
                putAppUpdateDelay(duration)
                    .launchIn(windowCoroutineScope)
            },
        )
    }

    SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf("app", "update", "release", "version", "new", "delay", "wait"),
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            title = stringResource(Res.string.pref_item_app_update_delay_title),
            text = text,
            dropdown = dropdown,
        )
    }
}

/**
 * Formats the delay of an update notification:
 * "Immediately" for zero, the duration otherwise.
 */
suspend fun formatAppUpdateDelay(
    duration: Duration,
    context: LeContext,
): String = if (duration == Duration.ZERO) {
    textResource(Res.string.pref_item_app_update_delay_immediately_text, context)
} else {
    duration.format(context)
}
