package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.HistoryRetention
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.GetHistoryRetentionVariants
import com.artemchep.keyguard.common.usecase.PutAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.PutGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope
import kotlin.time.Duration

fun settingAgentHistoryRetentionProvider(scope: Scope): SettingComponent = settingHistoryRetentionProvider(
    title = Res.string.pref_item_agent_history_retention_title,
    search = SettingIi.Search(
        group = "developer",
        tokens = listOf("request", "log", "history", "retention", "cleanup", "delete", "ssh", "gpg", "cli", "mcp"),
    ),
    getRetention = scope.get<GetAgentHistoryRetention>(),
    putRetention = scope.get<PutAgentHistoryRetention>(),
    getVariants = scope.get(),
    windowCoroutineScope = scope.get(),
    context = scope.get(),
)

fun settingGeneratorHistoryRetentionProvider(scope: Scope): SettingComponent {
    if (CurrentPlatform.hasWatch()) return flowOf(null)
    return settingHistoryRetentionProvider(
        title = Res.string.pref_item_generator_history_retention_title,
        search = SettingIi.Search(
            group = "security",
            tokens = listOf("generator", "password", "history", "retention", "cleanup", "delete"),
        ),
        getRetention = scope.get<GetGeneratorHistoryRetention>(),
        putRetention = scope.get<PutGeneratorHistoryRetention>(),
        getVariants = scope.get(),
        windowCoroutineScope = scope.get(),
        context = scope.get(),
    )
}

private fun settingHistoryRetentionProvider(
    title: StringResource,
    search: SettingIi.Search,
    getRetention: () -> Flow<Duration>,
    putRetention: (Duration) -> IO<Unit>,
    getVariants: GetHistoryRetentionVariants,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(getRetention(), getVariants()) { current, variants ->
    val text = formatHistoryRetention(current, context)
    val dropdown = variants
        .map { duration ->
            val actionTitle = formatHistoryRetention(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = duration == current,
                onClick = {
                    putRetention(duration)
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
        search = search,
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            icon = Icons.Outlined.AutoDelete,
            title = stringResource(title),
            text = text,
            dropdown = dropdown,
        )
    }
}

/**
 * Formats a history retention period, showing multiples
 * of [HistoryRetention.MONTH] in months.
 */
suspend fun formatHistoryRetention(
    duration: Duration,
    context: LeContext,
): String {
    val monthDays = HistoryRetention.MONTH.inWholeDays
    val inMonths = duration.isFinite() &&
        duration >= HistoryRetention.MONTH &&
        duration.inWholeDays % monthDays == 0L
    return if (inMonths) {
        val months = (duration.inWholeDays / monthDays).toInt()
        textResource(
            res = Res.plurals.months_plural,
            context = context,
            quantity = months,
            months.toString(),
        )
    } else {
        duration.format(context)
    }
}
