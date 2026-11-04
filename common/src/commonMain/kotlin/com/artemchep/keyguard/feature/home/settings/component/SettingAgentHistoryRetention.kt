package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetentionVariants
import com.artemchep.keyguard.common.usecase.PutAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope
import kotlin.time.Duration

fun settingAgentHistoryRetentionProvider(
    koinScope: Scope,
) = settingAgentHistoryRetentionProvider(
    getAgentHistoryRetention = koinScope.get(),
    getAgentHistoryRetentionVariants = koinScope.get(),
    putAgentHistoryRetention = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
    context = koinScope.get(),
)

fun settingAgentHistoryRetentionProvider(
    getAgentHistoryRetention: GetAgentHistoryRetention,
    getAgentHistoryRetentionVariants: GetAgentHistoryRetentionVariants,
    putAgentHistoryRetention: PutAgentHistoryRetention,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getAgentHistoryRetention(),
    getAgentHistoryRetentionVariants(),
) { retention, variants ->
    val text = formatAgentHistoryRetention(retention, context)
    val dropdown = variants
        .map { duration ->
            val actionTitle = formatAgentHistoryRetention(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = duration == retention,
                onClick = {
                    putAgentHistoryRetention(duration)
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
            group = "developer",
            tokens = listOf(
                "request",
                "log",
                "history",
                "retention",
                "cleanup",
                "delete",
                "ssh",
                "gpg",
                "cli",
                "mcp",
            ),
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            icon = Icons.Outlined.AutoDelete,
            title = stringResource(Res.string.pref_item_agent_history_retention_title),
            text = text,
            dropdown = dropdown,
        )
    }
}

/**
 * Formats a request log retention period, showing multiples
 * of [AgentHistoryRetention.MONTH] in months.
 */
suspend fun formatAgentHistoryRetention(
    duration: Duration,
    context: LeContext,
): String {
    val monthDays = AgentHistoryRetention.MONTH.inWholeDays
    val inMonths = duration.isFinite() &&
        duration >= AgentHistoryRetention.MONTH &&
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
