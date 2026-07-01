package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

fun settingSshAgentApprovalWindowProvider(
    directDI: DirectDI,
) = settingSshAgentApprovalWindowProvider(
    getSshAgentApprovalWindow = directDI.instance(),
    getSshAgentApprovalWindowVariants = directDI.instance(),
    putSshAgentApprovalWindow = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingSshAgentApprovalWindowProvider(
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getSshAgentApprovalWindowVariants: GetSshAgentApprovalWindowVariants,
    putSshAgentApprovalWindow: PutSshAgentApprovalWindow,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getSshAgentApprovalWindow(),
    getSshAgentApprovalWindowVariants(),
) { approvalWindow, variants ->
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) {
        return@combine null
    }

    val text = getSshAgentApprovalWindowTitle(approvalWindow, context)
    val dropdown = variants
        .map { duration ->
            val actionSelected = duration == approvalWindow
            val actionTitle = getSshAgentApprovalWindowTitle(duration, context)
            FlatItemAction(
                id = "settings.sshAgentApprovalWindow.${duration.inWholeSeconds}",
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putSshAgentApprovalWindow(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
                "approval",
                "authorization",
                "sign",
                "remember",
                "timeout",
            ),
        ),
    ) {
        SettingSshAgentApprovalWindow(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getSshAgentApprovalWindowTitle(
    duration: Duration,
    context: LeContext,
) = when (duration) {
    Duration.ZERO -> textResource(
        Res.string.pref_item_ssh_agent_approval_window_always_ask,
        context,
    )

    Duration.INFINITE -> textResource(
        Res.string.pref_item_ssh_agent_approval_window_until_lock,
        context,
    )

    else -> duration.format(context)
}

@Composable
private fun SettingSshAgentApprovalWindow(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    LocalSettingPaneComponents.current.KgPicker(
        icon = Icons.Outlined.Timer,
        title = stringResource(Res.string.pref_item_ssh_agent_approval_window_title),
        text = text,
        dropdown = dropdown,
    )
}
