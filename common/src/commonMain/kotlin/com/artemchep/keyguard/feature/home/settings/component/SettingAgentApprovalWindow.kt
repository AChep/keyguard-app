package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_agent_approval_window_always_ask
import com.artemchep.keyguard.res.pref_item_agent_approval_window_title
import com.artemchep.keyguard.res.pref_item_agent_approval_window_until_lock
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How long an agent remembers an approval, as the settings name it. */
suspend fun agentApprovalWindowTitle(
    duration: Duration,
    context: LeContext,
): String = when (duration) {
    Duration.ZERO -> textResource(
        Res.string.pref_item_agent_approval_window_always_ask,
        context,
    )

    Duration.INFINITE -> textResource(
        Res.string.pref_item_agent_approval_window_until_lock,
        context,
    )

    else -> duration.format(context)
}

/**
 * The picker of an agent's approval window.
 *
 * @param title The name of the setting; by default, the key approvals of a key agent.
 * @param actionIdPrefix Identifies each option as `<prefix>.<seconds>`, if set.
 */
@Suppress("LongParameterList")
internal fun settingAgentApprovalWindowProvider(
    approvalWindow: Flow<Duration>,
    approvalWindowVariants: Flow<List<Duration>>,
    putApprovalWindow: (Duration) -> IO<Unit>,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
    platformClasses: List<KClass<out Platform>>,
    searchTokens: List<String>,
    title: StringResource = Res.string.pref_item_agent_approval_window_title,
    actionIdPrefix: String? = null,
): SettingComponent = combine(
    approvalWindow,
    approvalWindowVariants,
) { current, variants ->
    val text = agentApprovalWindowTitle(current, context)
    val dropdown = variants
        .map { duration ->
            FlatItemAction(
                id = actionIdPrefix?.let { "$it.${duration.inWholeSeconds}" },
                title = TextHolder.Value(agentApprovalWindowTitle(duration, context)),
                selected = duration == current,
                onClick = {
                    putApprovalWindow(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClasses = platformClasses,
        search = SettingIi.Search(
            group = "security",
            tokens = searchTokens,
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            icon = Icons.Outlined.Timer,
            title = stringResource(title),
            text = text,
            dropdown = dropdown,
        )
    }
}
