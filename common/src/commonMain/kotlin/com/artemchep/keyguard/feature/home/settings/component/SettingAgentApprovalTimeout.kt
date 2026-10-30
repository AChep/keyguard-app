package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_agent_approval_timeout_title
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource

/**
 * The approval timeout is not configurable on Android, where the
 * Termux helpers give up on a request after a fixed time.
 */
internal val agentApprovalTimeoutPlatformClasses = listOf(Platform.Desktop::class)

/** How long an agent's approval prompt waits for the user, as the settings name it. */
suspend fun agentApprovalTimeoutTitle(
    duration: Duration,
    context: LeContext,
): String = duration.format(context)

/**
 * The picker of how long an agent's approval prompt waits for the user.
 *
 * @param actionIdPrefix Identifies each option as `<prefix>.<seconds>`.
 */
@Suppress("LongParameterList")
internal fun settingAgentApprovalTimeoutProvider(
    approvalTimeout: Flow<Duration>,
    approvalTimeoutVariants: Flow<List<Duration>>,
    putApprovalTimeout: (Duration) -> IO<Unit>,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
    platformClasses: List<KClass<out Platform>>,
    searchTokens: List<String>,
    actionIdPrefix: String,
): SettingComponent = combine(
    approvalTimeout,
    approvalTimeoutVariants,
) { current, variants ->
    val text = agentApprovalTimeoutTitle(current, context)
    val dropdown = variants
        .map { duration ->
            FlatItemAction(
                id = "$actionIdPrefix.${duration.inWholeSeconds}",
                title = TextHolder.Value(agentApprovalTimeoutTitle(duration, context)),
                selected = duration == current,
                onClick = {
                    putApprovalTimeout(duration)
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
            icon = Icons.Outlined.HourglassEmpty,
            title = stringResource(Res.string.pref_item_agent_approval_timeout_title),
            text = text,
            dropdown = dropdown,
        )
    }
}
