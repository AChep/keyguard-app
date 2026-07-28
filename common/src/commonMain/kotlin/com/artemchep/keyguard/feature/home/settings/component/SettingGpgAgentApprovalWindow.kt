package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
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
import kotlin.time.Duration

fun settingGpgAgentApprovalWindowProvider(
    directDI: DirectDI,
) = settingGpgAgentApprovalWindowProvider(
    getGpgAgentApprovalWindow = directDI.instance(),
    getGpgAgentApprovalWindowVariants = directDI.instance(),
    putGpgAgentApprovalWindow = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingGpgAgentApprovalWindowProvider(
    getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    getGpgAgentApprovalWindowVariants: GetGpgAgentApprovalWindowVariants,
    putGpgAgentApprovalWindow: PutGpgAgentApprovalWindow,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getGpgAgentApprovalWindow(),
    getGpgAgentApprovalWindowVariants(),
) { approvalWindow, variants ->
    val text = getGpgAgentApprovalWindowTitle(approvalWindow, context)
    val dropdown = variants
        .map { duration ->
            val actionTitle = getGpgAgentApprovalWindowTitle(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = duration == approvalWindow,
                onClick = {
                    putGpgAgentApprovalWindow(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "gnupg",
                "agent",
                "approval",
                "sign",
                "remember",
            ),
        ),
    ) {
        LocalSettingPaneComponents.current.KgPicker(
            icon = Icons.Outlined.Timer,
            title = stringResource(Res.string.pref_item_gpg_agent_approval_window_title),
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getGpgAgentApprovalWindowTitle(
    duration: Duration,
    context: LeContext,
) = when (duration) {
    Duration.ZERO -> textResource(
        Res.string.pref_item_gpg_agent_approval_window_always_ask,
        context,
    )

    Duration.INFINITE -> textResource(
        Res.string.pref_item_gpg_agent_approval_window_until_lock,
        context,
    )

    else -> duration.format(context)
}
